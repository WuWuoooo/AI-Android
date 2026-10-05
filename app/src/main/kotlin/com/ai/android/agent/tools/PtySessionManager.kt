package com.ai.android.agent.tools

import android.content.Context
import com.ai.android.termux.ProotRunner
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ⭐ v1.0.0-Stable：按对话隔离的 PTY 会话池（com.termux.terminal.TerminalSession，真 pty）。
 *
 * AI 的 terminal_exec / terminal_read 与终端 UI（PtyTerminalScreen）共用同一会话（按 convId 绑定），
 * 因此 AI 跑的命令能在终端屏实时看到，反之亦然（"与 AI 共享"）。
 *
 * 底层用 NDK 编译的 libtermux.so（forkpty），Ctrl-C/D/Z 是 kernel 真信号
 * （SIGINT / EOF / SIGTSTP），支持 jobs / fg / bg、vim / top、ANSI 彩色、宽字符。
 *
 * ⚠️ com.termux 的 TerminalSession 内部用主线程 Handler，构造 / initializeEmulator / write
 *    都必须在主线程执行；本类所有 public suspend 方法都用 withContext(Dispatchers.Main)。
 *    由于 Dispatchers.Main 是单线程，所有 ensureStarted / exec 串行化，不会并发 forkpty。
 */
object PtySessionManager {
    private val sessions = ConcurrentHashMap<String, PtyTerminalSession>()

    fun get(convId: String, context: Context): PtyTerminalSession =
        sessions.computeIfAbsent(convId.ifBlank { "default" }) { PtyTerminalSession(context) }

    fun remove(convId: String) {
        sessions.remove(convId.ifBlank { "default" })?.dispose()
    }
}

/** 单个对话的常驻 PTY 会话（包 com.termux.terminal.TerminalSession） */
class PtyTerminalSession(private val context: Context) {

    companion object {
        private const val TAG_MARK = "__AI_DONE"
        // 后台 PTY 的固定几何（终端 UI attach 后 onSizeChanged 会 updateSize 覆盖成真实尺寸）
        private const val INIT_COLS = 80
        private const val INIT_ROWS = 24
        private const val CELL_W = 8
        private const val CELL_H = 16
    }

        private val client = object : TerminalSessionClient {
                override fun onTextChanged(s: TerminalSession) {
            // ⭐ pty 每收到/产生新文本（打字、命令输出、回显）就通知 UI 重绘。
            //    对应 Termux 原版 TermuxTerminalSessionActivityClient.onTextChanged →
            //    TerminalView.onScreenUpdated()（invalidate → onDraw）。
            onScreenUpdate?.invoke()
        }
        override fun onTitleChanged(s: TerminalSession) {}
        override fun onSessionFinished(s: TerminalSession) {
            // 进程退出 → 标记不运行（UI 显示"重启"按钮；AI exec 下次 ensureStarted 会重建）
            _running.value = false
        }
        override fun onCopyTextToClipboard(s: TerminalSession, t: String) {}
        override fun onPasteTextFromClipboard(s: TerminalSession?) {}
        override fun onBell(s: TerminalSession) {}
        override fun onColorsChanged(s: TerminalSession) {}
        override fun onTerminalCursorStateChange(b: Boolean) {}
        override fun setTerminalShellPid(s: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(a: String, b: String) {}
        override fun logWarn(a: String, b: String) {}
        override fun logInfo(a: String, b: String) {}
        override fun logDebug(a: String, b: String) {}
        override fun logVerbose(a: String, b: String) {}
        override fun logStackTraceWithMessage(a: String, b: String, e: Exception) {}
        override fun logStackTrace(a: String, e: Exception) {}
    }

                        @Volatile private var session: TerminalSession? = null
    @Volatile private var lastReadPos = 0L   // ⭐ AI read(sinceLast=true) 的字符位置游标

    /** ⭐ UI 重绘回调：pty 文本变化（打字/输出/回显）时由 onTextChanged 触发，
     *  UI 侧绑定为 { view.onScreenUpdated() }（→ invalidate → onDraw），终端画面才实时刷新。
     *  主线程读写（client 回调与 UI 绑定都在主线程），用 @Volatile 保证可见性。 */
    @Volatile
    var onScreenUpdate: (() -> Unit)? = null

    /** ⭐ 会话是否仍在运行（true=存活可继续用；false=已退出，UI 显示"重启"按钮）。
     *  主线程更新（client 回调在主线程 Handler）。UI 用 asStateFlow 订阅。 */
        private val _running = MutableStateFlow(false)
    val runningFlow: StateFlow<Boolean> = _running.asStateFlow()

    /** ⭐ 当前会话对象流：AI 自愈重建 / restart 后，UI 订阅此流把 TerminalView 切到最新会话，
     *  避免终端屏继续显示已死的旧会话。 */
    private val _sessionFlow = MutableStateFlow<TerminalSession?>(null)
    val sessionFlow: StateFlow<TerminalSession?> = _sessionFlow.asStateFlow()

    /** 是否已初始化（emulator 已建）。注意：不能用 isRunning()——初始 shellPid=0 时它返回 true */
    private fun started(): Boolean = session?.getEmulator() != null

    /** 是否"已死"（emulator 建了但进程已退出）——需要重建 */
    private fun isDead(): Boolean = started() && session?.isRunning() == false

    /** 给终端 UI（TerminalView.attachSession）用的 com.termux 会话 */
    fun uiSession(): TerminalSession? = session

    /**
     * ⭐ AI 自愈（对齐管道版 ensureHealthy）：会话已死则重启重建，否则幂等确保已启动。
     * exec / sendCtrl / writeLine 前调用，bash 死亡后无需用户手动点"重启"即可自动恢复。
     */
    suspend fun ensureHealthy() = withContext(Dispatchers.Main) {
        if (isDead()) restart()
        ensureStarted()
    }

        /**
     * ⭐ 启动 PTY 会话（proot `bash -i` 接到真 pty）。必须在主线程；
     * 已初始化则直接返回（幂等）。
     */
    suspend fun ensureStarted() = withContext(Dispatchers.Main) {
        val cur = session
        if (started()) {
            // 若之前标记为已退出但 emulator 还在，重新标记运行中
            if (!_running.value) _running.value = cur?.isRunning() == true
            return@withContext
        }
                runCatching { cur?.finishIfRunning() }
        // ⭐ 优雅降级：proot 未安装/未就绪时 ProotRunner 会抛异常，捕获后保持"未启动"，
        //    让 exec/read 返回友好提示而不是让整个 AI 工具链崩溃
        val s = runCatching {
            val cmdList = ProotRunner(context).buildInteractiveCommand()
            val shell = cmdList.first()              // proot 可执行文件
            val args = cmdList.toTypedArray()         // 完整 argv（argv[0]=proot 路径）
            // cwd = guest 根 "/"：proot 的 -w 已设工作目录；chdir 到 host 侧路径会失败
            val t = TerminalSession(shell, "/", args, buildEnv(), null, client)
            runCatching { t.initializeEmulator(INIT_COLS, INIT_ROWS, CELL_W, CELL_H) }
            t
        }.onFailure {
            android.util.Log.w("PtyTerminalSession", "PTY 启动失败（proot 未安装/未就绪？）", it)
                }.getOrNull() ?: return@withContext
                                session = s
        _running.value = s.isRunning()
        _sessionFlow.value = s        // ⭐ 同步会话流：UI 订阅后把 TerminalView 切到新会话
        lastReadPos = 0L   // ⭐ 新会话：重置增量读取游标

        // ⭐ 第7项：注入 Termux 风格 PS1 提示符（`user@host:path$`，~ 表 HOME）
        //    让 PTY 终端"仿真"有 ~/.bash 提示符，而不是默认的 `bash-5.3$`
        runCatching {
            val ps1cmd = ProotRunner(context).ps1InitCommand()
            s.write(ps1cmd.toByteArray(), 0, ps1cmd.toByteArray().size)
        }
        Unit
    }

    /** 重启（SIGKILL 旧进程 + 重新 forkpty；调用方通常随后 ensureHealthy/ensureStarted 重建） */
    suspend fun restart() = withContext(Dispatchers.Main) {
        runCatching { session?.finishIfRunning() }
        session = null
        _running.value = false
        _sessionFlow.value = null     // ⭐ 旧会话失效，UI 会等待重建后重新 attach
        Unit
    }

    /** 向 pty 写控制字节（真 Ctrl-C/D/Z → SIGINT/EOF/SIGTSTP）；会话死掉会自愈重建 */
    suspend fun sendCtrl(byteValue: Int) = withContext(Dispatchers.Main) {
        ensureHealthy()
        runCatching { session?.write(byteArrayOf(byteValue.toByte()), 0, 1) }
    }

                /** 向 pty 写一行输入（终端 UI 回车）；会话死掉会自愈重建 */
    suspend fun writeLine(line: String) = withContext(Dispatchers.Main) {
        ensureHealthy()
        val bytes = (line + "\n").toByteArray()
        // ⭐ com.termux 的 write 只有 write(String) 和 write(byte[], offset, count)；用 3 参版本
        runCatching { session?.write(bytes, 0, bytes.size) }
    }

    /**
     * ⭐ 第9项：向 pty 写原始字节序列（不加换行）。
     * 用于终端底部文字快捷键：方向键 / HOME / END / PGUP / PGDN / ESC 等
     * 需发送 xterm 转义序列（↑=`\u001b[A` 等），而非整行命令。
     */
    suspend fun writeRaw(text: String) = withContext(Dispatchers.Main) {
        ensureHealthy()
        val bytes = text.toByteArray()
        runCatching { session?.write(bytes, 0, bytes.size) }
    }

    /**
     * ⭐ 第9项：向 AI 共享的 pty 发 Ctrl/Alt/Shift + 键的组合原始序列。
     * 用于"AI 也能使用这些快捷键"。组合规则（xterm）：
     *  - Ctrl+char：control char（ctrl-c=`\u0003`）
     *  - Alt/ESC 前缀：`\u001b` + key（M-x = ESC x）
     *  - 方向 / 翻页：`\u001b[A`(↑) `\u001b[B`(↓) `\u001b[C`(→) `\u001b[D`(←)
     *    PGUP=`\u001b[5~` PGDN=`\u001b[6~` HOME=`\u001b[H` END=`\u001b[F`
     *  - ESC 单独：`\u001b`
     */
    suspend fun sendKeySeq(name: String, ctrl: Boolean, alt: Boolean, shift: Boolean) =
        withContext(Dispatchers.Main) {
            val seq = buildKeySeq(name, ctrl, alt, shift)
            if (seq.isNotEmpty()) writeRaw(seq)
            Unit
        }

        /** 生成 xterm 按键序列字符串（纯函数，供 UI / AI 共用）。
     *  name 支持 "ctrl-x" / "alt-x" 前缀（剥离后并入对应修饰键）。 */
    fun buildKeySeq(name: String, ctrl: Boolean, alt: Boolean, shift: Boolean): String {
        // 解析 name 里的修饰前缀
        var key = name
        var c = ctrl; var a = alt
        if (key.startsWith("ctrl-")) { key = key.removePrefix("ctrl-"); c = true }
        if (key.startsWith("alt-")) { key = key.removePrefix("alt-"); a = true }

        fun altPrefix(s: String) = if (a) "\u001b" + s else s
        val base = when (key) {
            "up" -> "\u001b[A"; "down" -> "\u001b[B"
            "left" -> "\u001b[D"; "right" -> "\u001b[C"
            "pgup" -> "\u001b[5~"; "pgdn" -> "\u001b[6~"
            "home" -> "\u001b[H"; "end" -> "\u001b[F"
            "esc" -> "\u001b"
            "tab" -> if (c) "\u0001" else "\u0009"
            "enter" -> "\r"
            else -> {
                                val ch = key.firstOrNull()?.toString() ?: return ""
                val up = if (shift) ch.uppercase() else ch
                if (c && up.isNotEmpty()) {
                    // ctrl-a..ctrl-z → 0x01..0x1a；ctrl-l(0x0C) 清屏等
                    val n = up.lowercase()[0].code - 'a'.code
                    if (n in 0..25) (n + 1).toChar().toString() else ""
                } else up
            }
        }
        return altPrefix(base)
    }

    /**
     * AI exec：写命令 + 结束标记，轮询 transcript 直到出现标记或超时。
     * 超时后进程仍在后台跑，可用 read(sinceLast=true) 取后续输出。
     */
        suspend fun exec(command: String, timeoutSec: Int): String = withContext(Dispatchers.Main) {
        ensureHealthy()   // ⭐ 自愈：会话已死（bash 退出）则自动重建，无需用户手动点"重启"
        val s = session ?: return@withContext "❌ PTY 会话不可用（proot 未就绪？请到 设置 → Termux 环境 安装）"
        val mark = "__AI_DONE_PTY__${System.nanoTime()}__"
        val before = (s.getEmulator()?.getScreen()?.getTranscriptText()?.length ?: 0).toLong()

                val cmdBytes = (command + "\n").toByteArray()
        val markBytes = "echo $mark\$?\n".toByteArray()
        runCatching {
            // ⭐ com.termux 的 write 只有 write(String) 与 write(byte[], offset, count)；用 3 参版本
            s.write(cmdBytes, 0, cmdBytes.size)
            s.write(markBytes, 0, markBytes.size)
        }.onFailure {
            return@withContext "❌ 写入 PTY 失败：${it.message}"
        }

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val t = s.getEmulator()?.getScreen()?.getTranscriptText() ?: ""
            val idx = t.lastIndexOf(mark)
            if (idx >= 0) {
                val from = before.toInt().coerceIn(0, idx)
                val body = t.substring(from, idx).trim()
                val exit = t.substring(idx + mark.length).lineSequence().firstOrNull().orEmpty().trim().ifEmpty { "?" }
                return@withContext if (body.isBlank()) "✅ 退出码: $exit\n（无输出）"
                else "✅ 退出码: $exit\n$body"
            }
                        // 进程已退出（isRunning 在 mShellPid==-1 时为 false）且没等到标记 → 跳出
            if (!s.isRunning()) break
            delay(80)
        }
        val full = s.getEmulator()?.getScreen()?.getTranscriptText() ?: ""
        val partial = full.substring(before.toInt().coerceAtLeast(0)).take(8000)
        if (s.isRunning()) {
            "⏱ 命令超时（${timeoutSec}s）但仍在后台运行，可用 terminal_read since_last=true 查看后续输出\n--- 当前输出 ---\n$partial"
        } else "❌ PTY 会话在执行期间退出"
    }

        /** 读 transcript：sinceLast=true 时只取上次之后新增（AI terminal_read，避免输出残留混杂） */
    suspend fun read(lines: Int, sinceLast: Boolean): String = withContext(Dispatchers.Main) {
        val full = session?.getEmulator()?.getScreen()?.getTranscriptText() ?: ""
        if (full.isBlank()) return@withContext "（终端暂无输出）"
                if (sinceLast) {
            // ⭐ 用字符 offset 增量读：取 lastReadPos 之后到末尾
            val from = lastReadPos.toInt().coerceIn(0, full.length)
            val tail = full.substring(from)
            lastReadPos = full.length.toLong()
            if (tail.isBlank()) "（自上次读取后没有新输出）"
            else tail.split('\n').takeLast(lines.coerceIn(1, 500)).joinToString("\n")
        } else {
            // sinceLast=false：给最后 lines 行（终端 UI / 全量查看）
            lastReadPos = full.length.toLong()
            full.split('\n').takeLast(lines.coerceIn(1, 500)).joinToString("\n")
        }
    }

        /** 释放资源（从会话池移除时） */
    fun dispose() {
        runCatching { session?.finishIfRunning() }
        session = null
        _sessionFlow.value = null
        _running.value = false
    }

    private fun buildEnv(): Array<String> {
        val usrPath = "/data/data/com.termux/files/usr"
        val nativeLib = context.applicationInfo.nativeLibraryDir
        val loader = File(nativeLib, "libproot_loader.so")
        val tmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }
        return buildList {
            if (loader.exists()) add("PROOT_LOADER=" + loader.absolutePath)
            add("LD_LIBRARY_PATH=$usrPath/lib:$nativeLib")
            add("PROOT_TMP_DIR=${tmp.absolutePath}")
            add("HOME=/sdcard")
            add("PREFIX=$usrPath")
            add("PATH=$usrPath/bin:/system/bin:$usrPath/bin/applets")
            add("TERM=xterm-256color")
            add("LANG=zh_CN.UTF-8")
            add("SHELL=$usrPath/bin/bash")
            add("TMPDIR=$usrPath/tmp")
            add("ANDROID_ROOT=/system")
            add("ANDROID_DATA=/data")
            add("EXTERNAL_STORAGE=/sdcard")
        }.toTypedArray()
    }
}
