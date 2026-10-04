package com.ai.android.plugin

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonPrimitive

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
    private val styleProviders = mutableListOf<ThemeStyleProvider>()
    private val hooks = mutableListOf<AgentEventHook>()
    private val listeners = mutableListOf<AgentEventListener>()

    private val maxHookLog = 50

        /** ⭐ 当前生效的悬浮窗样式（无 THEME 插件时返回默认 AndLua 白卡风格） */
    @Volatile var activeFloatingStyle: FloatingStyle = FloatingStyle.DEFAULT

    /** ⭐ 第5项：当前生效的 App 主题（无 THEME 插件时返回默认，不改变内置配色） */
    @Volatile var activeAppTheme: com.ai.android.ui.theme.AppThemeStyle =
        com.ai.android.ui.theme.AppThemeStyle.DEFAULT

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; encodeDefaults = true
    }

        /** 解析插件目录内可选的 theme.json → FloatingStyle；缺省返回默认样式 */
    private fun loadThemeStyle(manifest: PluginManifest): FloatingStyle {
        val obj = readThemeJson(manifest) ?: return FloatingStyle.DEFAULT
        fun ii(k: String): Int? = obj[k]?.let { it.jsonPrimitive.content.toIntOrNull() }
        return FloatingStyle(
            panelBg = ii("panelBg") ?: FloatingStyle.DEFAULT.panelBg,
            panelText = ii("panelText") ?: FloatingStyle.DEFAULT.panelText,
            panelTextSecondary = ii("panelTextSecondary") ?: FloatingStyle.DEFAULT.panelTextSecondary,
            panelActionText = ii("panelActionText") ?: FloatingStyle.DEFAULT.panelActionText,
            accent = ii("accent") ?: FloatingStyle.DEFAULT.accent,
            divider = ii("divider") ?: FloatingStyle.DEFAULT.divider,
            realtimeText = ii("realtimeText") ?: FloatingStyle.DEFAULT.realtimeText,
            ballBg = ii("ballBg") ?: FloatingStyle.DEFAULT.ballBg,
            ballText = ii("ballText") ?: FloatingStyle.DEFAULT.ballText,
            cornerRadius = ii("cornerRadius") ?: FloatingStyle.DEFAULT.cornerRadius,
            panelTitle = obj["panelTitle"]?.jsonPrimitive?.content
                ?: FloatingStyle.DEFAULT.panelTitle,
            ballLabel = obj["ballLabel"]?.jsonPrimitive?.content
                ?: FloatingStyle.DEFAULT.ballLabel,
        )
    }

    /** ⭐ 第5项：解析 theme.json 的 "app" 节点 → AppThemeStyle（软件主题风格）；缺省返回默认 */
    private fun loadAppTheme(manifest: PluginManifest): com.ai.android.ui.theme.AppThemeStyle {
        val obj = readThemeJson(manifest) ?: return com.ai.android.ui.theme.AppThemeStyle.DEFAULT
        val app = obj["app"] as? kotlinx.serialization.json.JsonObject
            ?: return com.ai.android.ui.theme.AppThemeStyle.DEFAULT
        fun ss(k: String) = app[k]?.jsonPrimitive?.content
        fun ii(k: String): Int? = app[k]?.jsonPrimitive?.content?.toIntOrNull()
        return com.ai.android.ui.theme.AppThemeStyle(
            mode = ss("mode") ?: "follow",
            primary = ii("primary"),
            background = ii("background"),
            surface = ii("surface"),
        )
    }

    /** 读插件目录 theme.json（解析成 JsonObject），不存在/失败返回 null */
    private fun readThemeJson(manifest: PluginManifest): kotlinx.serialization.json.JsonObject? {
        val f = java.io.File(
            com.ai.android.plugin.PluginManager.pluginsDir(context),
            "${manifest.id}/theme.json",
        )
        if (!f.exists()) return null
        return runCatching {
            json.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonObject
        }.getOrNull()
    }

    fun registerWidget(w: WidgetSlot) {
        synchronized(this) { if (w !in widgets) widgets.add(w) }
    }

    fun registerTheme(t: ThemeProvider) {
        synchronized(this) { if (t !in themes) themes.add(t) }
    }

    /** ⭐ 登记插件提供的悬浮窗样式（THEME 能力） */
    fun registerStyleProvider(p: ThemeStyleProvider) {
        synchronized(this) { if (p !in styleProviders) styleProviders.add(p) }
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
     * 扫描已导入且已启用的插件，注册静态挂件 + 悬浮窗样式。
     * 在 MainApp.onCreate 调用一次；设置页插件变更后调用 refresh() 重建。
     */
    fun init() {
        applyEnabledPlugins()
        Log.d(tag, "PluginRegistry initialized, ${styleProviders.size + widgets.size} entries")
    }

    /** 清空并重建全部挂件 + 样式注册（设置页增删启停插件后调用） */
    fun refresh() {
        synchronized(this) {
            widgets.forEach { runCatching { it.onUnbind() } }
            widgets.clear()
            styleProviders.clear()
        }
        applyEnabledPlugins()
        Log.d(tag, "PluginRegistry refresh: re-registered ${styleProviders.size + widgets.size} entries")
    }

        /** 扫描启用插件，登记 WIDGET 挂件 + THEME 样式，并取首个 THEME 样式为 active */
    private fun applyEnabledPlugins() {
        val enabled = PluginManager.list(context).filter { PluginManager.isEnabled(context, it.id) }
        enabled.forEach { m ->
            if ("WIDGET" in m.capabilities) {
                registerWidget(StaticWidgetSlot(m, context.applicationContext))
            }
            if ("THEME" in m.capabilities) {
                registerStyleProvider(StaticThemeStyleProvider(m, loadThemeStyle(m)))
            }
        }
        // ⭐ 首个 THEME 插件的样式生效；无则回默认 AndLua 白卡风格
        activeFloatingStyle = synchronized(this) { styleProviders.firstOrNull()?.style() }
            ?: FloatingStyle.DEFAULT
        // ⭐ 第5项：首个带 "app" 主题节点的 THEME 插件生效；无则回默认
        activeAppTheme = enabled.firstOrNull { it.id == firstThemePluginId() }
            ?.let { loadAppTheme(it) }
            ?: com.ai.android.ui.theme.AppThemeStyle.DEFAULT
    }

    /** 首个带 THEME 能力且 theme.json 含 "app" 节点的启用插件 id */
    private fun firstThemePluginId(): String? {
        val enabled = PluginManager.list(context).filter { PluginManager.isEnabled(context, it.id) }
        return enabled.firstOrNull { m ->
            "THEME" in m.capabilities && readThemeJson(m)?.let { "app" in it } == true
        }?.id
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
