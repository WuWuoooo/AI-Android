package com.ai.android.plugin

import android.content.Context
import android.util.Log

/**
 * 插件注册表：登记挂件 / 主题 / 事件钩子，并向插件广播 Agent 事件。
 *
 * beta5：空壳 + 静态挂件注册（不加载 JS）；
 * beta6：在此加载 JS 插件（WebView/JsBridge），并把 [AgentEventHook] 事件分发给插件。
 */
class PluginRegistry(private val context: Context) {

    private val tag = "PluginRegistry"

    private val widgets = mutableListOf<WidgetSlot>()
    private val themes = mutableListOf<ThemeProvider>()
    private val hooks = mutableListOf<AgentEventHook>()
    private val listeners = mutableListOf<AgentEventListener>()

    private val maxHookLog = 50

    fun registerWidget(w: WidgetSlot) {
        synchronized(this) { if (w !in widgets) widgets.add(w) }
    }

    fun registerTheme(t: ThemeProvider) {
        synchronized(this) { if (t !in themes) themes.add(t) }
    }

    /** 事件记录（保留最近 [maxHookLog] 条，供调试 / beta6 回放） */
    fun registerHook(h: AgentEventHook) {
        synchronized(this) {
            hooks.add(h)
            while (hooks.size > maxHookLog) hooks.removeAt(0)
        }
    }

    fun addListener(l: AgentEventListener) {
        synchronized(this) { if (l !in listeners) listeners.add(l) }
    }

    fun allWidgets(): List<WidgetSlot> = synchronized(this) { widgets.toList() }
    fun allThemes(): List<ThemeProvider> = synchronized(this) { themes.toList() }
    fun allHooks(): List<AgentEventHook> = synchronized(this) { hooks.toList() }
    fun allListeners(): List<AgentEventListener> = synchronized(this) { listeners.toList() }

    /** 向所有监听者广播事件（宿主在 Agent 关键节点调用） */
    fun broadcast(event: AgentEventHook) {
        registerHook(event)
        synchronized(this) { listeners.toList() }.forEach { l ->
            runCatching { l.onEvent(event) }
                .onFailure { Log.w(tag, "插件事件分发失败", it) }
        }
    }

        /**
     * 扫描已导入且已启用的插件，注册静态挂件（beta5）。
     * 在 MainApp.onCreate 调用一次；设置页插件变更后调用 refresh() 重建。
     */
    fun init() {
        val enabled = PluginManager.list(context).filter { PluginManager.isEnabled(context, it.id) }
        enabled.forEach { m ->
            if ("WIDGET" in m.capabilities) {
                registerWidget(StaticWidgetSlot(m, context.applicationContext))
            }
        }
        Log.d(tag, "PluginRegistry initialized, ${enabled.size} plugins")
    }

    /** 清空并重建全部挂件注册（设置页增删启停插件后调用） */
    fun refresh() {
        synchronized(this) {
            widgets.forEach { runCatching { it.onUnbind() } }
            widgets.clear()
        }
        val enabled = PluginManager.list(context).filter { PluginManager.isEnabled(context, it.id) }
        enabled.forEach { m ->
            if ("WIDGET" in m.capabilities) {
                registerWidget(StaticWidgetSlot(m, context.applicationContext))
            }
        }
        Log.d(tag, "PluginRegistry refresh: ${enabled.size} plugins re-registered")
    }
}

/**
 * beta5 静态挂件：把插件 manifest.iconPath 图标渲染进悬浮窗，点击触发宿主面板展开。
 * JS 动态挂件由 beta6 的插件实现 [WidgetSlot] 提供。
 */
class StaticWidgetSlot(
    override val manifest: PluginManifest,
    private val appContext: Context,
) : WidgetSlot {

    override fun render(context: Context, onExpand: () -> Unit): android.view.View =
        PluginManager.renderStaticWidget(context, manifest, onExpand)
}
