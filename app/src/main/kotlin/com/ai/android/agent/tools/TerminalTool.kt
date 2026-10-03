package com.ai.android.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

/**
 * 常驻 sh 会话（与用户共享的内置终端）。
 * 命令逐条写入进程 stdin，通过结束标记识别命令完成。
 */
object TerminalSession {

    private const val MAX_LINES = 3000
    private const val TAG = "__AI_DONE"

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

    fun ensureStarted() {
        synchronized(ioLock) {
            val p = process
            if (p != null && p.isAlive) return
            val np = ProcessBuilder("sh")
                .directory(File("/sdcard"))
                .redirectErrorStream(true)
                .start()
            process = np
            writer = BufferedWriter(OutputStreamWriter(np.outputStream, Charsets.UTF_8))
            append("[终端会话已启动]")
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
        }
    }

    fun exec(command: String, timeoutSec: Int): String {
        ensureStarted()
        val startNo: Long
        val mark: String
        synchronized(ioLock) {
            startNo = synchronized(bufferLock) { seq }
            cmdSeq++
            mark = "${TAG}_${cmdSeq}__"
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
                return if (body.isBlank()) "✅ 退出码: $exit\n（无输出）" else "✅ 退出码: $exit\n$body"
            }
            Thread.sleep(80)
        }
        val partial = since(startNo).joinToString("\n")
        return "⏱ 命令超时（${timeoutSec}s）但仍在后台运行，稍后可用 terminal_read 查看后续输出\n--- 当前输出 ---\n${partial.take(8000)}"
    }

    fun read(linesCount: Int): String {
        ensureStarted()
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
        ensureStarted()
    }
}

/** 在内置终端执行命令 */
class TerminalExecTool : ToolExecutor {
    override val name = "terminal_exec"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val cmd = args.str("command")
        if (cmd.isBlank()) throw IllegalStateException("command 不能为空")
        TerminalSession.exec(cmd, args.int("timeout_sec", 30).coerceIn(1, 120))
    }
}

/** 读取终端最近输出 */
class TerminalReadTool : ToolExecutor {
    override val name = "terminal_read"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        TerminalSession.read(args.int("lines", 40))
    }
}
