package com.ai.android.agent.tools

import android.content.Context
import android.util.Log
import com.ai.android.storage.SettingsRepository
import com.ai.android.termux.BootstrapInstaller
import com.ai.android.termux.ProotRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * 终端会话（按对话隔离）：
 * - 每个对话（convId）拥有独立的常驻 shell 进程（proot/bash 或系统 sh）。
 * - AI 的 terminal_exec / terminal_read 与该对话的终端 UI 共用同一会话（"与 AI 共享"）。
 * - 历史保留：除非执行 `clear`，否则输出缓冲持续累积（按 [MAX_LINES] 环切）。
 */
class TerminalSession(
    private val context: Context?,
    private val settings: SettingsRepository?,
) {

    companion object {
        const val MAX_LINES = 3000
        const val TAG_MARK = "__AI_DONE"
    }

    private class Line(val no: Long, val text: String)

    private val buffer = ArrayDeque<Line>()
    private val bufferLock = Any()
    private val ioLock = Any()
                private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var seq = 0L
    private var cmdSeq = 0L
    private var lastReadNo = 0L  // ⭐ 记录上次 read 读到的行号（since_last 用）

    /** ⭐ 输出变更流：每次缓冲变化推送"最近 500 行"快照（replay=1，新订阅者拿到最新一帧） */
    private val _outputFlow = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val outputFlow: Flow<String> = _outputFlow.asSharedFlow()

    /** 取最近 N 行原始快照（空时返回空串，区别于 read() 的"暂无输出"文案） */
    private fun rawTail(n: Int = 500): String = synchronized(bufferLock) {
        buffer.toList().takeLast(n).joinToString("\n") { it.text }
    }

    private fun emitOutput() {
        _outputFlow.tryEmit(rawTail())
    }

    private fun append(text: String) {
        synchronized(bufferLock) {
            seq++
            buffer.addLast(Line(seq, text))
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        emitOutput()
    }

    private fun since(no: Long): List<String> = synchronized(bufferLock) {
        buffer.filter { it.no > no }.map { it.text }
    }

        /**
     * 取当前缓冲最近 N 行（用于终端 UI 展示 / AI terminal_read）。
     * @param linesCount 读取行数
     * @param sinceLast true 时只返回上次调用以来新增的行（terminal_read 的 since_last 参数）
     */
    fun read(linesCount: Int, sinceLast: Boolean = false): String {
        val lines = synchronized(bufferLock) { buffer.toList() }
        if (lines.isEmpty()) return "（终端暂无输出）"
        val start = if (sinceLast) {
            val idx = lines.indexOfFirst { it.no > lastReadNo }
            if (idx < 0) { lastReadNo = lines.lastOrNull()?.no ?: 0L; return "（自上次读取后没有新输出）" }
            idx
        } else 0
        val slice = lines.subList(start.coerceAtLeast(0), lines.size).takeLast(linesCount.coerceIn(1, 500))
        // 更新 lastReadNo
        synchronized(bufferLock) { lastReadNo = lines.lastOrNull()?.no ?: 0L }
        return slice.joinToString("\n") { it.text }
    }

        /** 清空屏幕历史（用户输入 `clear` 时调用；AI 侧不影响进程本身） */
    fun clearScreen() {
        synchronized(bufferLock) { buffer.clear() }
        append("[已清屏]")
    }

    /**
     * ⭐ 交互式输入（终端 UI 回车）：把整行写进常驻 shell。
     * 特殊命令 `clear` 仅在 UI 层清屏，不写进 shell（避免污染会话）。
     * @return 是否写成功（进程存活）
     */
    fun writeInput(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed == "clear") { clearScreen(); return true }
        ensureHealthy()
        synchronized(ioLock) {
            val w = writer
            if (w == null) return false
            return runCatching {
                w.write(line + "\n")
                w.flush()
                append("❯ " + line)
            }.isSuccess
        }
    }

        /**
     * ⭐ 发送控制字符到常驻 shell（终端 UI 的 Ctrl-C / Ctrl-D / Ctrl-Z / Ctrl-L 等）。
     * - Ctrl-C = "\u0003"（SIGINT，中断前台进程）
     * - Ctrl-D = "\u0004"（EOF，退出 cat/Python 等）
     * - Ctrl-Z = "\u001A"（SIGTSTP，挂起进程，再 jobs/fg 恢复）
     * - Ctrl-L = "\u000C"（清屏红字标记，实际由 bash 渲染）
     * @return 是否写成功（进程存活）
     */
    fun sendControlChar(ch: String): Boolean {
        if (ch.isEmpty()) return false
        ensureHealthy()
        synchronized(ioLock) {
            val w = writer ?: return false
            return runCatching {
                w.write(ch)
                w.flush()
                append("⏎ [Ctrl 信号已发送]")
            }.isSuccess
        }
    }

    fun ensureStarted() {
        synchronized(ioLock) {
            val p = process
            if (p != null && p.isAlive) return

            val (command, workDir) = buildStartCommand()
            val pb = ProcessBuilder(command)
            pb.directory(workDir)
            pb.redirectErrorStream(true)

            if (context != null) {
                val env = pb.environment()
                val nativeLib = context.applicationInfo.nativeLibraryDir
                val termuxPrefix = "/data/data/com.termux/files"
                val usrPath = "$termuxPrefix/usr"
                val loaderFile = File(nativeLib, "libproot_loader.so")
                if (loaderFile.exists()) env["PROOT_LOADER"] = loaderFile.absolutePath
                env["LD_LIBRARY_PATH"] = "$usrPath/lib:$nativeLib"
                val prootTmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }
                env["PROOT_TMP_DIR"] = prootTmp.absolutePath
                env["HOME"] = "/sdcard"
                env["PREFIX"] = usrPath
                env["PATH"] = "$usrPath/bin:/system/bin:$usrPath/bin/applets"
                env["TERM"] = "xterm-256color"
                env["LANG"] = "zh_CN.UTF-8"
                env["SHELL"] = "$usrPath/bin/bash"
                env["TMPDIR"] = "$usrPath/tmp"
                env["ANDROID_ROOT"] = "/system"
                env["ANDROID_DATA"] = "/data"
                env["EXTERNAL_STORAGE"] = "/sdcard"
            }

            val np = pb.start()
            process = np
            writer = BufferedWriter(OutputStreamWriter(np.outputStream, Charsets.UTF_8))
            append("[终端会话已启动]")
            append("[工作目录: ${workDir.absolutePath}]")
            append("[启动命令: ${command.joinToString(" ")}]")
            append("[PROOT_LOADER=${pb.environment()["PROOT_LOADER"]}]")
            append("[PATH=${pb.environment()["PATH"]}]")

            Thread {
                runCatching {
                    np.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { append(it) }
                    }
                }
                append("[终端进程已退出]")
                synchronized(ioLock) {
                    if (process === np) { process = null; writer = null }
                }
            }.apply { isDaemon = true }.start()

            val initScript = settings?.terminalInitScript().orEmpty()
            if (initScript.isNotBlank()) {
                runCatching {
                    writer?.write(initScript + "\n")
                    writer?.flush()
                    append("[已执行初始化脚本]")
                }
            }
        }
    }

        private fun buildStartCommand(): Pair<List<String>, File> {
        if (context != null && BootstrapInstaller.isInstalled(context)) {
                        val runner = ProotRunner(context)
            if (runner.isReady()) {
                // ⭐ 管道版保持非交互（bash -c bash，无 prompt 污染，稳定）；
                //    PTY 版（PtyTerminalController）才用 buildInteractiveCommand()（bash -i，真信号）
                val cmd = runner.buildCommand(userCommand = "bash", interactive = false)
                return cmd to File(context.filesDir, "termux")
            } else {
                append("[proot 未就绪，降级为系统 sh]")
            }
        }
        val home = settings?.terminalHome().orEmpty().ifBlank { "/sdcard" }
        val dir = File(home).takeIf { it.exists() && it.isDirectory } ?: File("/sdcard")
        return listOf("sh") to dir
    }

    /** 终端进程当前是否健康（存活且有可写流） */
    fun isHealthy(): Boolean {
        val p = process
        return p != null && p.isAlive && writer != null
    }

    /** 会话崩溃 / 未就绪时自动重启一次，尽量透明恢复 */
    private fun ensureHealthy(): Boolean {
        if (isHealthy()) return false
        Log.w("TerminalSession", "会话不健康，自动重启")
        restart()
        ensureStarted()
        return true
    }

    /** 单次执行（无重试） */
    private fun execOnce(command: String, timeoutSec: Int): String {
        val startNo: Long
        val mark: String
        synchronized(ioLock) {
            startNo = synchronized(bufferLock) { seq }
            cmdSeq++
            mark = "${TAG_MARK}_${cmdSeq}__"
            val w = writer ?: throw IllegalStateException("终端未就绪")
            runCatching {
                w.write(command + "\n")
                w.write("echo $mark\$?\n")
                w.flush()
            }.onFailure {
                throw IllegalStateException("写入命令失败（进程可能已退出）: ${it.message}")
            }
        }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val lines = since(startNo)
            val doneLine = lines.firstOrNull { it.contains(mark) }
            if (doneLine != null) {
                val exit = doneLine.substringAfter(mark).trim().ifEmpty { "?" }
                val body = lines.filter { !it.contains(mark) }.joinToString("\n").trim()
                return if (body.isBlank()) "✅ 退出码: $exit\n（无输出）"
                else "✅ 退出码: $exit\n$body"
            }
            if (!isHealthy()) break
            Thread.sleep(80)
        }

        val partial = since(startNo).joinToString("\n")
        if (isHealthy()) {
            return "⏱ 命令超时（${timeoutSec}s）但仍在后台运行，稍后可用 terminal_read 查看后续输出\n--- 当前输出 ---\n${partial.take(8000)}"
        }
        throw IllegalStateException("终端进程在执行期间退出，未收到结束标记")
    }

    /** 执行命令（带自动重启 + 自动重试），最多 [maxAttempts] 次 */
    fun exec(command: String, timeoutSec: Int, maxAttempts: Int = 2): String {
        ensureHealthy()
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            try {
                val result = execOnce(command, timeoutSec)
                if (attempt > 1) Log.i("TerminalSession", "第 $attempt 次尝试成功（自动重试）")
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w("TerminalSession", "第 $attempt 次失败: ${e.message}", e)
                if (attempt < maxAttempts.coerceAtLeast(1)) ensureHealthy()
            }
        }
        return if (isHealthy()) {
            "⏱ 命令超时（${timeoutSec}s）但仍在后台运行，稍后可用 terminal_read 查看后续输出"
        } else {
            "❌ 终端执行失败（已自动重启并重试 ${maxAttempts.coerceAtLeast(1)} 次仍失败）：${lastError?.message ?: "未知错误"}"
        }
    }

    fun restart() {
        synchronized(ioLock) {
            runCatching { process?.destroy() }
            process = null
            writer = null
        }
    }
}

/**
 * ⭐ 按对话隔离的终端会话池：每个 convId 一个独立常驻进程。
 * AI 终端工具与终端 UI 都通过 [get] 取到同一会话（共享）。
 */
object TerminalSessionManager {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    fun get(convId: String, context: Context?, settings: SettingsRepository?): TerminalSession =
        sessions.computeIfAbsent(convId.ifBlank { "default" }) {
            TerminalSession(context, settings)
        }

    fun remove(convId: String) {
        val s = sessions.remove(convId.ifBlank { "default" })
        runCatching { s?.restart() }
    }
}

class TerminalExecTool(
    private val context: Context,
    private val settings: SettingsRepository?,
    private val convIdProvider: () -> String,
) : ToolExecutor {
    override val name = "terminal_exec"
    override val dangerous = true
    override suspend fun execute(args: JsonObject): String {
        val cmd = args.requireStrAny("command", "cmd", hint = "例如 pkg install python -y")
        checkDangerousShell(cmd)
                val convId = convIdProvider()
        // ⭐ v1.0.0-Stable：默认 60s（旧 30s 对 pkg/pip/长 Python 偏短，易"假超时"）；上限 120s
        val timeout = args.int("timeout_sec", 60).coerceIn(1, 120)
                // ⭐ 按设置：默认走完整 PTY 会话（与终端 UI 共享同一真 pty，Ctrl-C/D/Z 真信号）
        if (settings?.terminalUsePty() != false) {
            return PtySessionManager.get(convId, context).exec(cmd, timeout)
        }
        // 回退：稳定管道版
        return withContext(Dispatchers.IO) {
            val sess = TerminalSessionManager.get(convId, context, settings)
            sess.exec(cmd, timeout)
        }
    }
}

class TerminalReadTool(
    private val convIdProvider: () -> String,
    private val context: Context?,
    private val settings: SettingsRepository?,
) : ToolExecutor {
    override val name = "terminal_read"
    override suspend fun execute(args: JsonObject): String {
        val convId = convIdProvider()
        val lines = args.int("lines", 40)
        val sinceLast = args.bool("since_last", false)
                // ⭐ 按设置：默认走 PTY 会话 transcript；context 缺失时回退管道版
        if (settings?.terminalUsePty() != false && context != null) {
            return PtySessionManager.get(convId, context).read(lines, sinceLast)
        }
        return withContext(Dispatchers.IO) {
            val sess = TerminalSessionManager.get(convId, context, settings)
            sess.read(lines, sinceLast = sinceLast)
        }
    }
}
