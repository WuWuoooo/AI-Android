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

    /** ⭐ v1.1.0 #3：当前生效的聊天气泡样式（无 THEME 插件时返回默认） */
    @Volatile var activeChatBubbleStyle: ChatBubbleStyle = ChatBubbleStyle.DEFAULT

    /** ⭐ v1.1.0 #3：插件声明的启用功能集（空 = 全默认启用） */
    @Volatile var activeFeatures: List<String> = emptyList()

    /** ⭐ v1.1.0 #3：查插件是否启用某功能（空清单 = 全启用） */
    fun isFeatureEnabled(feature: String): Boolean =
        activeFeatures.isEmpty() || feature in activeFeatures

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; encodeDefaults = true
    }

                /** 解析插件目录内可选的 theme.json → FloatingStyle；缺省返回默认样式 */
    private fun loadThemeStyle(manifest: PluginManifest): FloatingStyle {
        val obj = readThemeJson(manifest) ?: return FloatingStyle.DEFAULT
                fun ii(k: String): Int? = obj[k]?.let { it.jsonPrimitive.content.toIntOrNull() }
        fun ssAll(k: String): String = obj[k]?.jsonPrimitive?.content ?: ""
        fun boolList(k: String): List<String> =
            obj[k]?.let { el ->
                when (el) {
                    is kotlinx.serialization.json.JsonArray ->
                        el.mapNotNull { it.jsonPrimitive.content }
                    else -> emptyList()
                }
            } ?: emptyList()
        val d = FloatingStyle.DEFAULT
        // 聊天气泡（可选 "chatBubble" 节点）
        val bubbleObj = obj["chatBubble"] as? kotlinx.serialization.json.JsonObject
        val chatBubble = if (bubbleObj != null) {
            fun bi(k: String) = bubbleObj[k]?.jsonPrimitive?.content?.toIntOrNull()
            ChatBubbleStyle(
                cornerRadius = bi("cornerRadius") ?: d.chatBubble.cornerRadius,
                userBg = bi("userBg"),
                assistantBg = bi("assistantBg"),
                textColor = bi("textColor"),
                fontSize = bi("fontSize") ?: d.chatBubble.fontSize,
                horizontalPadding = bi("horizontalPadding") ?: d.chatBubble.horizontalPadding,
                verticalPadding = bi("verticalPadding") ?: d.chatBubble.verticalPadding,
            )
        } else d.chatBubble
        return FloatingStyle(
            panelBg = ii("panelBg") ?: d.panelBg,
            panelText = ii("panelText") ?: d.panelText,
            panelTextSecondary = ii("panelTextSecondary") ?: d.panelTextSecondary,
            panelActionText = ii("panelActionText") ?: d.panelActionText,
            accent = ii("accent") ?: d.accent,
            divider = ii("divider") ?: d.divider,
            realtimeText = ii("realtimeText") ?: d.realtimeText,
            ballBg = ii("ballBg") ?: d.ballBg,
            ballText = ii("ballText") ?: d.ballText,
            cornerRadius = ii("cornerRadius") ?: d.cornerRadius,
            panelTitle = obj["panelTitle"]?.jsonPrimitive?.content ?: d.panelTitle,
            ballLabel = obj["ballLabel"]?.jsonPrimitive?.content ?: d.ballLabel,
            // ⭐ v1.1.0 #3 新增字段
            ballShape = ssAll("ballShape").ifBlank { d.ballShape },
            ballSize = ii("ballSize") ?: d.ballSize,
            ballIcon = ssAll("ballIcon"),
            panelLayout = ssAll("panelLayout").ifBlank { d.panelLayout },
            chatBubble = chatBubble,
            features = boolList("features"),
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

    /** ⭐ v1.1.0 #3：从 theme.json 解析 "features" 数组（功能开关清单），空 = 未声明 */
    private fun loadFeaturesFromJson(obj: kotlinx.serialization.json.JsonObject): List<String> =
        obj["features"]?.let { el ->
            when (el) {
                is kotlinx.serialization.json.JsonArray -> el.mapNotNull { it.jsonPrimitive.content }
                is kotlinx.serialization.json.JsonPrimitive -> el.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
        } ?: emptyList()

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
        // ⭐ v1.1.0 #3：同步聊天气泡样式（取自当前 active 悬浮窗样式）
        activeChatBubbleStyle = activeFloatingStyle.chatBubble
        // ⭐ v1.1.0 #3：功能开关——汇总 THEME / FUNCTION 插件在 theme.json 声明的 features
        activeFeatures = enabled
            .filter { "THEME" in it.capabilities || "FUNCTION" in it.capabilities }
            .mapNotNull { m -> readThemeJson(m)?.let { loadFeaturesFromJson(it) } }
            .firstOrNull { it.isNotEmpty() }
            ?: activeFloatingStyle.features
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

    /** ⭐ v1.1.0 #3：当前 active 悬浮窗样式所属的 THEME 插件 id（悬浮球图标定位用） */
    fun activeThemePluginId(): String? = synchronized(this) {
        styleProviders.firstOrNull()?.manifest?.id
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
