package com.ai.android.agent.tools

import com.ai.android.service.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 无障碍操控手机。
 * 实际能力由 AgentAccessibilityService 提供（系统无障碍服务实例）。
 */
class AccessibilityTool : ToolExecutor {

    override val name = "accessibility_control"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.Default) {
        val svc = AgentAccessibilityService.instance
            ?: throw IllegalStateException(
                "无障碍服务未连接。请引导用户到 系统设置 → 无障碍 →「AI Android 手机操控」中开启后重试"
            )

        when (val action = args.str("action")) {
            "screen" -> svc.describeScreen()

            "screenshot" -> svc.takeScreenshot()

            "tap" -> {
                val x = args.int("x"); val y = args.int("y")
                svc.tap(x, y)
                "已点击 ($x, $y)"
            }

            "long_press" -> {
                val x = args.int("x"); val y = args.int("y")
                svc.longPress(x, y)
                "已长按 ($x, $y)"
            }

            "swipe" -> {
                val x1 = args.int("x1"); val y1 = args.int("y1")
                val x2 = args.int("x2"); val y2 = args.int("y2")
                svc.swipe(x1, y1, x2, y2, args.int("duration_ms", 400))
                "已滑动 ($x1,$y1) → ($x2,$y2)"
            }

            "click_text" -> {
                val t = args.str("text")
                if (t.isBlank()) throw IllegalStateException("click_text 需要 text 参数")
                if (svc.clickText(t)) "已点击包含「$t」的控件" else "未找到包含「$t」的控件（可先 screen 查看当前界面）"
            }

            "input" -> {
                val t = args.str("text")
                if (t.isBlank()) throw IllegalStateException("input 需要 text 参数")
                if (svc.inputText(t)) "已输入文本: $t" else "输入失败：未找到聚焦的输入框（请先 tap 输入框）"
            }

            "back" -> { svc.globalBack(); "已执行返回" }
            "home" -> { svc.globalHome(); "已回到桌面" }
            "recents" -> { svc.globalRecents(); "已打开最近任务" }
            "notifications" -> { svc.globalNotifications(); "已打开通知栏" }

            "scroll" -> {
                val down = args.str("direction", "down") == "down"
                svc.scroll(down)
                "已向${if (down) "下" else "上"}滚动"
            }

            "launch_app" -> svc.launchApp(args.str("package"), args.str("app_name"))

            "sleep" -> {
                val ms = args.int("ms", 1000).coerceIn(0, 5000)
                delay(ms.toLong())
                "已等待 ${ms}ms"
            }

            "windows" -> svc.describeWindows()

            "stop_projection" -> { svc.stopProjection(); "已停止屏幕共享，释放投屏状态" }

            else -> throw IllegalStateException("未知 action: $action")
        }
    }
}
