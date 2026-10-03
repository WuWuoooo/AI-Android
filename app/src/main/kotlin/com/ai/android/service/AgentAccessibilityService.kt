package com.ai.android.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务：提供屏幕读取、点击、输入、滑动、截图等能力，
 * 由 AccessibilityTool 桥接调用。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ==================== 手势 ====================

    fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build(), null, null
        )
    }

    fun longPress(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 600))
                .build(), null, null
        )
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, durationMs.coerceIn(100, 3000).toLong()
                    )
                )
                .build(), null, null
        )
    }

    // ==================== 节点查找 ====================

    private fun findNode(node: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (runCatching { pred(node) }.getOrDefault(false)) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), pred)?.let { return it }
        }
        return null
    }

    private fun findNodes(node: AccessibilityNodeInfo?, limit: Int, out: MutableList<AccessibilityNodeInfo>, pred: (AccessibilityNodeInfo) -> Boolean) {
        if (node == null || out.size >= limit) return
        if (runCatching { pred(node) }.getOrDefault(false)) out.add(node)
        for (i in 0 until node.childCount) {
            findNodes(node.getChild(i), limit, out, pred)
        }
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { n ->
            (n.text?.toString()?.contains(text, ignoreCase = true) == true) ||
                (n.contentDescription?.toString()?.contains(text, ignoreCase = true) == true)
        } ?: return false

        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        tap(rect.centerX(), rect.centerY())
        return true
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: findNode(root) { it.isEditable }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ==================== 全局动作 ====================

    fun globalBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun globalNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findNode(root) { it.isScrollable }
        if (scrollable != null) {
            val ok = scrollable.performAction(
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
            if (ok) return true
        }
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2
        val top = (dm.heightPixels * 0.3f).toInt()
        val bottom = (dm.heightPixels * 0.7f).toInt()
        if (down) swipe(cx, bottom, cx, top, 400) else swipe(cx, top, cx, bottom, 400)
        return true
    }

    // ==================== 屏幕描述 ====================

    fun describeScreen(): String {
        val root = rootInActiveWindow
            ?: return "（无法读取当前窗口。请确认无障碍服务已连接，或在受限 App 中改用 screenshot 视觉方案）"
        val dm = resources.displayMetrics
        val sb = StringBuilder()
        sb.append("当前应用: ").append(root.packageName?.toString().orEmpty()).append('\n')
        sb.append("屏幕: ").append(dm.widthPixels).append('x').append(dm.heightPixels).append('\n')
        sb.append("--- 可见控件 ---\n")
        var count = 0
        walk(root, sb, 0, 150) { count++ }
        if (count == 0) sb.append("（未读取到可见控件，可尝试截图方案）\n")
        sb.append("--- 共 ").append(count).append(" 个控件 ---")
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, limit: Int, onEach: () -> Unit) {
        if (node == null || depth > 30 || sb.length > 16_000) return
        val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
        if (visible) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = text.ifEmpty { desc }
            val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
            val editable = runCatching { node.isEditable }.getOrDefault(false)
            val clickable = runCatching { node.isClickable }.getOrDefault(false)
            val scrollable = runCatching { node.isScrollable }.getOrDefault(false)

            if (label.isNotEmpty() || editable || (clickable && sb.length < 8_000) || scrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    sb.append("- ")
                    if (label.isNotEmpty()) sb.append('「').append(label.take(80)).append("」 ")
                    else sb.append('[').append(cls).append("] ")
                    sb.append('(').append(rect.centerX()).append(',').append(rect.centerY()).append(')')
                    if (editable) sb.append(" <可输入>")
                    if (clickable) sb.append(" <可点>")
                    if (scrollable) sb.append(" <可滚动>")
                    sb.append('\n')
                    onEach()
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (sb.length > 16_000) break
            walk(node.getChild(i), sb, depth + 1, limit, onEach)
        }
    }

    fun describeWindows(): String {
        val ws = runCatching { windows }.getOrNull() ?: return "（无窗口信息）"
        if (ws.isEmpty()) return "（无窗口信息）"
        return ws.joinToString("\n") { w ->
            val pkg = runCatching { w.root?.packageName?.toString().orEmpty() }.getOrDefault("")
            "窗口 ${w.id}: type=${w.type} pkg=$pkg active=${w.isActive}"
        }
    }

    // ==================== 启动应用 ====================

    /** 启动应用：优先用包名，其次按名称模糊匹配 */
    fun launchApp(packageName: String, appName: String): String {
        val pm = packageManager
        val target: String? = when {
            packageName.isNotBlank() -> packageName
            appName.isNotBlank() -> {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val apps = pm.queryIntentActivities(intent, 0)
                apps.firstOrNull {
                    runCatching { it.loadLabel(pm).toString().contains(appName, ignoreCase = true) }
                        .getOrDefault(false)
                }?.activityInfo?.packageName
            }
            else -> null
        }
        if (target.isNullOrBlank()) return "未找到应用（package=$packageName, name=$appName）"
        val launch = pm.getLaunchIntentForPackage(target)
            ?: return "应用 $target 没有启动入口（可能是系统应用）"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(launch)
            "已启动应用: $target"
        }.getOrElse { "启动失败: ${it.message}" }
    }

    // ==================== 截图（Android 11+） ====================

    fun takeScreenshot(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "当前系统低于 Android 11，不支持无障碍截图；请使用 action=screen 读取控件"
        }
        return screenshotApi30()
    }

    @SuppressLint("NewApi")
    private fun screenshotApi30(): String {
        val latch = CountDownLatch(1)
        var result = "截图失败"
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    runCatching {
                        val hb = screenshot.hardwareBuffer
                        val w = hb.width
                        val h = hb.height
                        val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        val dir = File("/sdcard/AiAndroid/screenshots").apply { mkdirs() }
                        val f = File(dir, "shot_${System.currentTimeMillis()}.png")
                        FileOutputStream(f).use { fos -> bmp?.compress(Bitmap.CompressFormat.PNG, 90, fos) }
                        bmp?.recycle()
                        hb.close()
                        result = "截图已保存: ${f.absolutePath}（${w}x${h}）"
                    }.onFailure { result = "截图处理失败: ${it.message}" }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    result = "截图失败，错误码: $errorCode（可能是敏感界面或未授权）"
                    latch.countDown()
                }
            }
        )
        latch.await(6, TimeUnit.SECONDS)
        return result
    }

    /** 无障碍截图不依赖投屏；此方法用于任务收尾时清理状态（保留接口） */
    fun stopProjection() {
        // 无障碍方案无需停止投屏；此处保留作未来 MediaProjection 扩展
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }
}
