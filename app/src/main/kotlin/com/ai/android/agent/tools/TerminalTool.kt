package com.ai.android.agent.tools

import android.content.Context
import com.ai.android.storage.SettingsRepository
import com.ai.android.termux.BootstrapInstaller
import com.ai.android.termux.ProotRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

object TerminalSession {

    private const val MAX_LINES = 3000
    private const val TAG_MARK = "__AI_DONE"

    private class Line(val no: Long, val text: String)

    private val buffer = ArrayDeque<Line>()
    private val bufferLock = Any()
    private val ioLock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var seq = 0L
    private var cmdSeq = 0L

    private fun append(text: String) {
        synchronized(bufferLock) {
            seq++
            buffer.addLast(Line(seq, text))
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
    }

    private fun since(no: Long): List<String> = synchronized(bufferLock) {
        buffer.filter { it.no > no }.map { it.text }
    }

    fun ensureStarted(context: Context?, settings: SettingsRepository?) {
        synchronized(ioLock) {
            val p = process
            if (p != null && p.isAlive) return

            val (command, workDir) = buildStartCommand(context, settings)
            val pb = ProcessBuilder(command)
            pb.directory(workDir)
            pb.redirectErrorStream(true)

            if (context != null) {
                val env = pb.environment()
                val nativeLib = context.applicationInfo.nativeLibraryDir

                val termuxPrefix = "/data/data/com.termux/files"
                val usrPath = "$termuxPrefix/usr"

                // ⭐ PROOT_LOADER 指向我们的 loader 副本（host 侧路径，proot 自己用）
                val loaderFile = File(nativeLib, "libproot_loader.so")
                if (loaderFile.exists()) {
                    env["PROOT_LOADER"] = loaderFile.absolutePath
                }

                // ⭐ LD_LIBRARY_PATH 同时包含：
                //   - guest 侧 $PREFIX/lib（guest 的 ELF 用，比如 apt 找 libz.so.1）
                //   - host 侧 nativeLibraryDir（proot 自己用，加载 libtalloc.so 等）
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
            append("[PROOT_TMP_DIR=${pb.environment()["PROOT_TMP_DIR"]}]")
            append("[LD_LIBRARY_PATH=${pb.environment()["LD_LIBRARY_PATH"]}]")
            append("[PATH=${pb.environment()["PATH"]}]")

            Thread {
                runCatching {
                    np.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { append(it) }
                    }
                }
                append("[终端进程已退出]")
                synchronized(ioLock) {
                    if (process === np) {
                        process = null
                        writer = null
                    }
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

    private fun buildStartCommand(
        context: Context?,
        settings: SettingsRepository?,
    ): Pair<List<String>, File> {
        if (context != null && BootstrapInstaller.isInstalled(context)) {
            val runner = ProotRunner(context)
            if (runner.isReady()) {
                val cmd = runner.buildCommand(userCommand = "bash")
                return cmd to File(context.filesDir, "termux")
            } else {
                append("[proot 未就绪，降级为系统 sh]")
            }
        }
        val home = settings?.terminalHome().orEmpty().ifBlank { "/sdcard" }
        val dir = File(home).takeIf { it.exists() && it.isDirectory } ?: File("/sdcard")
        return listOf("sh") to dir
    }

    fun exec(command: String, timeoutSec: Int, context: Context?, settings: SettingsRepository?): String {
        ensureStarted(context, settings)
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
            }.onFailure { throw IllegalStateException("写入命令失败: ${it.message}") }
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
            Thread.sleep(80)
        }
        val partial = since(startNo).joinToString("\n")
        return "⏱ 命令超时（${timeoutSec}s）但仍在后台运行，稍后可用 terminal_read 查看后续输出\n--- 当前输出 ---\n${partial.take(8000)}"
    }

    fun read(linesCount: Int): String {
        val lines = synchronized(bufferLock) { buffer.toList() }
        if (lines.isEmpty()) return "（终端暂无输出）"
        return lines.takeLast(linesCount.coerceIn(1, 500)).joinToString("\n") { it.text }
    }

    fun restart() {
        synchronized(ioLock) {
            runCatching { process?.destroy() }
            process = null
            writer = null
        }
    }
}

class TerminalExecTool(
    private val context: Context,
    private val settings: SettingsRepository?,
) : ToolExecutor {
    override val name = "terminal_exec"
    override val dangerous = true
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val cmd = args.requireStrAny("command", "cmd", hint = "例如 pkg install python -y")
        checkDangerousShell(cmd)
        TerminalSession.exec(cmd, args.int("timeout_sec", 30).coerceIn(1, 120), context, settings)
    }
}

class TerminalReadTool : ToolExecutor {
    override val name = "terminal_read"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        TerminalSession.read(args.int("lines", 40))
    }
}