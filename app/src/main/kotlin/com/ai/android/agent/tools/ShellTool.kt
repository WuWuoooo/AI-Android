package com.ai.android.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 危险 shell 模式（命中即拦截，要求模型先 ask_user） */
private val DANGEROUS_SHELL = listOf(
    Regex("""\brm\s+-rf\s+/"""),
    Regex("""\bmkfs\b"""),
    Regex("""\bdd\s+if="""),
    Regex(""">\s*/dev/block"""),
    Regex("""(^|\s)su(\s|$)"""),
    Regex("""(^|\s)sudo(\s|$)"""),
    Regex("""\bpm\s+uninstall\b"""),
    Regex("""\bpm\s+clear\b"""),
)

internal fun checkDangerousShell(cmd: String) {
    DANGEROUS_SHELL.firstOrNull { it.containsMatchIn(cmd) }?.let {
        throw IllegalStateException(
            "检测到潜在危险命令（匹配：${it.pattern}）。请先用 ask_user 向用户确认，得到明确同意后再执行。"
        )
    }
}

/** 一次性 shell 命令 */
class ShellTool : ToolExecutor {
    override val name = "run_shell_command"
    override val dangerous = true

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val command = args.requireStrAny("command", "cmd", hint = "例如 ls /sdcard/Download")
        checkDangerousShell(command)
        val timeout = args.int("timeout_sec", 15).coerceIn(1, 60)

                // ⭐ v1.1.0 #2：优先走 Shizuku（shell uid 2000，突破 app 沙箱读写 Android/data）；
        //    未装/未授权时 ShizukuManager.executeShell 自动回退 app 沙箱本地 sh，并带提示前缀。
        // ⭐ v1.2.0-next #5 有效反馈：Shizuku(uid 2000) 与 Termux(u0_a348) 是不同 uid 沙箱，
        //    涉及 Termux 内部目录（/data/data/com.termux）必须走 terminal_exec，杀 Termux 进程同理。
        val out = runCatching {
            com.ai.android.service.ShizukuManager.executeShell(
                com.ai.android.MainApp.instance, command, timeout,
            )
        }.getOrElse {
            // Shizuku 反射不可用 → 兜底纯本地 sh
            runLocalShell(command, timeout)
        }
        out
    }

    /** 纯本地沙箱执行（Shizuku 反射彻底失败时的兜底） */
    private fun runLocalShell(command: String, timeout: Int): String {
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
            return "⏱ 命令超时（${timeout}s）已终止\n--- 部分输出 ---\n${partial.take(8000)}"
        }
                readerThread.join(2000)
        val exit = proc.exitValue()
        val text = synchronized(output) { output.toString() }.take(10_000)
        val head = if (exit == 0) "✅ 退出码: 0" else "⚠️ 退出码: $exit"
        return if (text.isBlank()) "$head\n（无输出）" else "$head\n$text"
    }
}

/** 获取当前日期时间 */
class GetCurrentTimeTool : ToolExecutor {
    override val name = "get_current_time"

    override suspend fun execute(args: JsonObject): String {
        val now = Date()
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(now)
        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
        return "当前时间: $full（$week）\n时间戳: ${System.currentTimeMillis()}"
    }
}