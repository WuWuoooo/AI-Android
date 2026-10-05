package com.ai.android.plugin

/**
 * ⭐ 悬浮窗外观样式（可被 THEME 类插件覆盖）。
 *
 * 默认值遵循 AndLua MyApp1 示例（xfc.aly）的白卡风格：
 * - 白色圆角卡片、深色正文、灰色次要文字、蓝色 Token 强调色。
 *
 * 插件 manifest.capabilities 含 "THEME" 时，可经 [ThemeStyleProvider] 提供自定义样式，
 * 覆盖悬浮窗球 / 面板的颜色、字号、圆角等。
 *
 * ⭐ v1.1.0 #3 扩展：
 * - 悬浮球形状 / 尺寸 / 图标（ballShape / ballSize / ballIcon）
 * - 面板布局（panelLayout）
 * - 聊天气泡样式（chatBubble）
 * - 功能开关（features：插件可声明要启用的能力集，宿主按此裁剪工具 / 功能）
 */
data class FloatingStyle(
    /** 面板卡片背景（默认 0xFFFFFFFF 白） */
    val panelBg: Int = 0xFFFFFFFF.toInt(),
    /** 面板主文字（状态 / 标题） */
    val panelText: Int = 0xFF1F2937.toInt(),
    /** 面板次要文字（提示 / 工具行） */
    val panelTextSecondary: Int = 0xFF6B7280.toInt(),
    /** 标题栏右侧按钮（— ✕） */
    val panelActionText: Int = 0xFF9CA3AF.toInt(),
    /** 强调色（Token 数值） */
    val accent: Int = 0xFF2563EB.toInt(),
    /** 分割线 */
    val divider: Int = 0xFFE5E7EB.toInt(),
    /** 实时输出文字 */
    val realtimeText: Int = 0xFF374151.toInt(),
    /** 默认悬浮球背景 */
    val ballBg: Int = 0xFF2563EB.toInt(),
    /** 默认悬浮球文字 */
    val ballText: Int = 0xFFFFFFFF.toInt(),
    /** 面板圆角 dp */
    val cornerRadius: Int = 14,
    /** 面板标题文字（覆盖 "AI 助手"） */
    val panelTitle: String = "AI 助手",
    /** 悬浮球文字（覆盖 "AI"） */
    val ballLabel: String = "AI",

    // ==================== ⭐ v1.1.0 #3：新增字段（全部带默认值，旧插件零改动可用）====================

    /**
     * 悬浮球形状。取值："circle"（圆，默认）/ "rounded"（圆角矩形）/
     * "square"（直角方块）/ "capsule"（胶囊）。
     */
    val ballShape: String = "circle",
    /** 悬浮球尺寸 dp（默认 46） */
    val ballSize: Int = 46,
    /**
     * 悬浮球图标（插件自带 PNG 相对路径，相对插件目录）。
     * 非空时优先用图标渲染（而非纯色 tint 圆球）；不存在则回退纯色。
     */
    val ballIcon: String = "",
    /**
     * 面板布局。取值："default"（头部→实时→工具→镜像→按钮，默认）/
     * "compact"（折叠实时输出区，更紧凑）。
     */
    val panelLayout: String = "default",
    /** ⭐ 聊天气泡样式（插件可改聊天界面气泡圆角/配色/字号/留白） */
    val chatBubble: ChatBubbleStyle = ChatBubbleStyle(),
    /**
     * ⭐ 功能开关集：插件声明要启用的能力清单（如 "MEMORY" / "SCHEDULER" / "ACCESSIBILITY" / "FLOATING"）。
     * 空 = 全部默认启用；非空 = 仅启用清单内 + 宿主按此裁剪工具注册。
     */
    val features: List<String> = emptyList(),
) {
    companion object {
        /** ⭐ 默认 AndLua 白卡风格（xfc.aly 配色） */
        val DEFAULT = FloatingStyle()
        /** 深色风格（兜底 / 插件可选） */
        val DARK = FloatingStyle(
            panelBg = 0xF01F1F1F.toInt(),
            panelText = 0xFFFFFFFF.toInt(),
            panelTextSecondary = 0xFFB0B0B0.toInt(),
            panelActionText = 0xFFAEC6FF.toInt(),
            accent = 0xFF7FD4A8.toInt(),
            divider = 0x33FFFFFF,
            realtimeText = 0xE6E0E0E0.toInt(),
            ballBg = 0xE61F1F1F.toInt(),
            ballText = 0xFFFFFFFF.toInt(),
        )
    }

    /** 判断某功能是否启用（空清单 = 全启用；非空 = 仅清单内） */
    fun isFeatureEnabled(feature: String): Boolean =
        features.isEmpty() || feature in features
}

/**
 * ⭐ v1.1.0 #3：聊天气泡样式（插件可定制聊天界面气泡外观）。
 * 宿主在 MessageBubble 读取时，用 [PluginRegistry.activeChatBubbleStyle] 覆盖默认。
 */
data class ChatBubbleStyle(
    /** 气泡圆角 dp（默认：用户 16 / AI 16，跟随现有 RoundedCornerShape） */
    val cornerRadius: Int = 16,
    /** 用户气泡主色（ARGB Int；null = 用 Compose 内置 primaryContainer） */
    val userBg: Int? = null,
    /** AI 气泡主色（null = 用内置 surface） */
    val assistantBg: Int? = null,
    /** 气泡正文颜色（null = 跟随主题 onPrimaryContainer / onSurface） */
    val textColor: Int? = null,
    /** 正文字号 sp（0 = 跟随默认 15sp） */
    val fontSize: Int = 0,
    /** 左右留白 dp（0 = 跟随默认 12dp） */
    val horizontalPadding: Int = 0,
    /** 上下留白 dp（0 = 跟随默认 9dp） */
    val verticalPadding: Int = 0,
) {
    companion object {
        val DEFAULT = ChatBubbleStyle()
    }
}

/**
 * 插件提供的悬浮窗样式来源。
 *
 * 宿主在 [PluginRegistry.refresh] 时，把含 "THEME" 能力的启用插件登记为 [ThemeStyleProvider]；
 * [PluginRegistry.activeFloatingStyle] 取第一个的 [style]（无则返回 [FloatingStyle.DEFAULT]）。
 */
interface ThemeStyleProvider {
    val manifest: PluginManifest
    fun style(): FloatingStyle
}

/** 静态样式提供器：把插件 manifest 里可选的 theme.json 解析成 FloatingStyle。 */
class StaticThemeStyleProvider(
    override val manifest: PluginManifest,
    private val style: FloatingStyle,
) : ThemeStyleProvider {
    override fun style(): FloatingStyle = style
}
