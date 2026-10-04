package com.ai.android.agent.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.android.service.AgentAccessibilityService
import com.ai.android.service.FloatingService
import com.ai.android.service.MediaProjectionBridge
import com.ai.android.service.MirrorService
import com.ai.android.storage.SettingsRepository
import kotlinx.serialization.json.JsonObject

/**
 * AI 操控手机前置守卫：
 * - 检查 aiControlEnabled / confirmBeforeControl
 * - 当前前台 App 不符时先 ask_user（若 confirmBeforeControl 开）
 * - 启动 / 停止屏幕镜像（MirrorService + 悬浮窗镜像面板）
 */
class AiControlGuard(
    private val context: Context,
    private val settings: SettingsRepository,
) {
        @Volatile private var mirrorActive = false
    /** ⭐ 本次 Agent 会话内"AI 操控"是否已确认过（首次操控确认后，同会话内不再逐次询问） */
    @Volatile private var sessionConfirmed = false
    private val tag = "AiControlGuard"

    /**
     * accessibility_control 工具执行前调用。
     * @param args 工具参数（含 action、package 等）
     * @param askUser 宿主注入的 suspend 提问回调
     * @return null = 继续执行；非 null = 中止原因（作为 ToolResult.output 返回）
     */
    suspend fun preCheck(
        args: JsonObject,
        askUser: suspend (String, String) -> String,
    ): String? {
        // 1. 总开关
        if (!settings.aiControlEnabled()) return "AI 操控功能已在设置中关闭"

        // 2. 确认操控（默认 confirmBeforeControl=false → 直接放行，不询问）
        //    即使开了确认，也只在会话内首次操控时问一次，之后同会话直接放行（避免逐次繁琐）
        if (settings.confirmBeforeControl() && !sessionConfirmed) {
            val currentPkg = runCatching { AgentAccessibilityService.instance?.currentPackage() }
                .getOrNull().orEmpty()
            val targetPkg = if (args.str("action") == "launch_app") {
                args.str("package")
            } else ""
            val answer = askUser(
                if (targetPkg.isNotBlank()) {
                    "AI 即将切换到「$targetPkg」进行操控（本次会话内后续操作不再询问）。当前：${currentPkg.ifBlank{"未知"}}"
                } else {
                    "AI 即将操控手机（本次会话内后续操作不再询问）。当前前台：${currentPkg.ifBlank{"未知"}}"
                },
                "确认本会话内允许,取消",
            )
            if (answer.trim().contains("取消") || answer.trim() == "不") {
                return "用户取消了 AI 操控"
            }
            sessionConfirmed = true
        }

        // 3. 启动镜像（悬浮窗有权限 + 设置开启）
        startMirror()
        return null
    }

    /** ⭐ Agent 会话开始时重置确认状态（每次新任务/继续生成/重试 都重新走一次"首次确认"） */
    fun beginSession() { sessionConfirmed = false }

        /** 启动屏幕镜像（幂等） */
    fun startMirror() {
        if (mirrorActive) return
        if (!settings.mirrorEnabled() || !settings.floatingEnabled()) {
            // 未启用镜像时不打日志误导；保持"仅后台操控"
            return
        }
        mirrorActive = true
        runCatching {
            val ctx = context.applicationContext
            if (!MirrorService.hasProjection()) {
                // 首次需要投屏授权 → 切主线程弹系统 MediaProjection 对话框（授权后回写 projection intent）
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        val i = Intent(ctx, MediaProjectionBridge::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    }
                }
            }
            // 启动 / 确认 MirrorService，并显示悬浮窗镜像面板
            FloatingService.setMirrorActive(ctx, true)
        }.onFailure { Log.e(tag, "startMirror failed", it) }
    }

    /** 停止屏幕镜像（幂等）；同时复位会话确认状态 */
    fun stopMirror() {
        if (!mirrorActive) { /* 即使未启动也复位会话 */ sessionConfirmed = false }
        mirrorActive = false
        sessionConfirmed = false
        runCatching {
            FloatingService.setMirrorActive(context.applicationContext, false)
        }
    }

    /** 供 MainViewModel 停止（用户点悬浮窗暂停 / Agent 结束） */
    fun stopAll() { stopMirror() }
}
