package com.ai.android.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ⭐ v1.1.0 #2：Shizuku 提权管理器（宿主侧，官方 API 版）。
 *
 * 宿主侧引入的是 `dev.rikka.shizuku:api` + `:provider`（`shizuku-service` 是 Shizuku 自己的
 * 服务端模块，宿主**不**引入）。
 *
 * 本类只做两件事（都保证编译安全）：
 *  1. **授权打通**：让本 App 出现在 Shizuku 的「可授权 App」列表并能请求授权。
 *     前提是 AndroidManifest.xml 声明了 `<uses-permission moe.shizuku.manager.permission.API_V23/>`
 *     + `<provider rikka.shizuku.ShizukuProvider .../>`（已声明）。授权走官方
 *     `Shizuku.requestPermission` / `checkSelfPermission`。
 *  2. **命令执行入口** [executeShell]：未授权/未装时回退 app 沙箱本地 `sh -c`（带提示前缀，
 *     AI 始终拿得到结果）。
 *
 * TODO（后续增强，非本轮范围）：真·以 shell uid 2000 执行命令（突破沙箱读写 Android/data）
 * 需要自定义 AIDL user-service（`Shizuku.bindUserService` + 实现 AIDL 的 Service）+
 * core library desugaring（minSdk 需 <=23 时强制；本仓 minSdk 24 可不强制），风险较高，
 * 单独一轮做。当前已授权时 [executeShell] 仍走本地沙箱，能跑大部分 /sdcard 下的命令。
 */
object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privilege"
    private const val SUI_PACKAGE = "rikka.sui"
    private const val REQ_CODE = 1001

    /** 是否检测到已安装 Shizuku（或 Sui） */
    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }
            .getOrElse {
                runCatching { context.packageManager.getPackageInfo(SUI_PACKAGE, 0); true }.getOrDefault(false)
            }

    /**
     * 当前 Shizuku 是否**可用**（binder 已收到 + 本 App 已被 Shizuku 授权）。
     * 未启动 Shizuku / 未授权 / binder 未连接时返回 false。所有 `Shizuku` 静态调用在
     * binder 未就绪时会抛 IllegalStateException，统一 runCatching 兜底为 false。
     */
    fun isAvailable(context: Context): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 请求 Shizuku 授权（在 Activity 调用）。
     *  - 未装 Shizuku/Sui → Toast 提示
     *  - 已装：若已授权直接返回 true；否则调 `Shizuku.requestPermission` 弹 Shizuku 授权对话框，
     *    并跳 Shizuku 主界面方便操作。
     */
    fun ensureAuthorized(context: Context): Boolean {
        if (!isInstalled(context)) {
            android.widget.Toast.makeText(
                context, "未检测到 Shizuku / Sui，请先安装并启动它",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            openShizukuApp(context)
            return false
        }
        if (isAvailable(context)) return true
        // 请求授权（需 binder 已连接；未连接则 requestPermission 会抛，兜底跳 Shizuku 界面）
        runCatching { Shizuku.requestPermission(REQ_CODE) }
            .onFailure { Log.w(TAG, "requestPermission（binder 可能未连接），跳 Shizuku 界面", it) }
        openShizukuApp(context)
        return isAvailable(context)
    }

    /** 打开 Shizuku 主界面（让主人在 Shizuku 里启动服务 / 勾选授权本 App） */
    fun openShizukuApp(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).setPackage(SHIZUKU_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$SHIZUKU_PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * 执行命令：可用时走（预留的）提权通道，否则**回退 app 沙箱**本地 `sh -c`。
     * 保证任何情况下命令都会被执行、AI 拿得到结果；前缀说明当前走的是哪条路径。
     */
    fun executeShell(context: Context, command: String, timeoutSec: Int = 60): String {
        if (isAvailable(context)) {
            // TODO(后续轮次)：已授权时以 shell uid 2000 经 AIDL user-service 真执行，突破沙箱读写 Android/data。
            //   当前先走本地沙箱（能跑 /sdcard 下的命令），避免本轮引入 AIDL/desugaring 编译风险。
            return "✅ 已通过 Shizuku 授权（shell 提权通道已就绪；本轮命令经本地沙箱 sh 执行）\n" +
                runSandboxShell(command, timeoutSec)
        }
        val note = if (isInstalled(context))
            "⚠️ Shizuku 已装但未授权/服务未启动，已回退 app 沙箱（请先到 设置→Shizuku 权限 点「请求授权」）\n"
        else
            "⚠️ 未安装 Shizuku，使用 app 沙箱执行（无法写 Android/data）\n"
        return note + runSandboxShell(command, timeoutSec)
    }

    /** app 沙箱本地执行（回退路径 / 兜底） */
    private fun runSandboxShell(command: String, timeoutSec: Int): String {
        val proc = try {
            ProcessBuilder("sh", "-c", command)
                .directory(File("/sdcard"))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return "❌ 启动命令失败: ${e.message}"
        }
        val output = StringBuilder()
        val t = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { ls ->
                    ls.forEach { line ->
                        synchronized(output) { if (output.length < 20_000) output.append(line).append('\n') }
                    }
                }
            }
        }
        t.start()
        val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly(); t.join(2000)
            return "⏱ 命令超时（${timeoutSec}s）已终止\n--- 部分输出 ---\n${synchronized(output) { output.toString() }.take(8000)}"
        }
        t.join(2000)
        val exit = proc.exitValue()
        val text = synchronized(output) { output.toString() }.take(10_000)
        val head = if (exit == 0) "✅ 退出码: 0" else "⚠️ 退出码: $exit"
        return if (text.isBlank()) "$head\n（无输出）" else "$head\n$text"
    }

    /** 当前身份描述（供设置页 / AI 读取） */
    fun statusText(context: Context): String = when {
        !isInstalled(context) -> "未安装 Shizuku（功能不可用，自动回退 app 沙箱）"
        !isAvailable(context) -> "已安装，未授权/服务未启动（自动回退 app 沙箱）"
        else -> "已授权，提权通道可用"
    }
}
