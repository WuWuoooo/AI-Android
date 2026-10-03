package com.ai.android.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 在手机执行 shell 命令（普通应用权限，无 root） */
class ShellTool : ToolExecutor {
    override val name = "run_shell_command"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val command = args.str("command")
        if (command.isBlank()) throw IllegalStateException("command 不能为空")
        val timeout = args.int("timeout_sec", 15).coerceIn(1, 60)

        val proc = try {
            ProcessBuilder("sh", "-c", command)
                .directory(File("/sdcard"))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw IllegalStateException("启动命令失败: ${e.message}")
        }

        val output = StringBuilder()
        val readerThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < 20_000) output.append(line).append('\n')
                        }
                    }
                }
            }
        }
        readerThread.start()

        val finished = proc.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            readerThread.join(2000)
            val partial = synchronized(output) { output.toString() }
            return@withContext "⏱ 命令超时（${timeout}s）已终止\n--- 部分输出 ---\n${partial.take(8000)}"
        }
        readerThread.join(2000)
        val exit = proc.exitValue()
        val text = synchronized(output) { output.toString() }.take(10_000)
        val head = if (exit == 0) "✅ 退出码: 0" else "⚠️ 退出码: $exit"
        if (text.isBlank()) "$head\n（无输出）" else "$head\n$text"
    }
}

/** 获取当前日期时间 */
class GetCurrentTimeTool : ToolExecutor {
    override val name = "get_current_time"

    override suspend fun execute(args: JsonObject): String {
        val now = Date()
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(now)
        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
        val ms = System.currentTimeMillis()
        return "当前时间: $full（$week）\n时间戳: $ms"
    }
}
