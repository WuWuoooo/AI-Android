package com.ai.android.plugin

/**
 * ⭐ 悬浮窗外观样式（可被 THEME 类插件覆盖）。
 *
 * 默认值遵循 AndLua MyApp1 示例（xfc.aly）的白卡风格：
 * - 白色圆角卡片、深色正文、灰色次要文字、蓝色 Token 强调色。
 *
 * 插件 manifest.capabilities 含 "THEME" 时，可经 [ThemeStyleProvider] 提供自定义样式，
 * 覆盖悬浮窗球 / 面板的颜色、字号、圆角等。
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
