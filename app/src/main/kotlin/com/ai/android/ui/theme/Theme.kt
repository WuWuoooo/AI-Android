package com.ai.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ⭐ 第5项：可配置的软件主题风格。
 * 来源优先级：插件 THEME 能力（theme.json 的 app 节点）> 设置页选择。
 * 默认值与原内置配色一致，不填插件时行为不变。
 */
data class AppThemeStyle(
    /** "follow"（跟随系统）/ "light" / "dark" */
    val mode: String = "follow",
    /** 可选 16 进制 0xRRGGBB 主色；null = 用内置默认 */
    val primary: Int? = null,
    val background: Int? = null,
    val surface: Int? = null,
) {
    companion object {
        val DEFAULT = AppThemeStyle()
    }
}

private fun c(v: Int?, def: Int) = if (v != null) Color(v) else Color(def)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F6FDE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF575E71),
    secondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFF725572),
    background = Color(0xFFF8F9FE),
    surface = Color(0xFFF8F9FE),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002D6D),
    primaryContainer = Color(0xFF1B449C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFBFC6DC),
    secondaryContainer = Color(0xFF3F4759),
    tertiary = Color(0xFFDEB8DC),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
)

/** ⭐ 把十进制 ARGB Int 转成 Compose Color（负数/越界安全：转无符号 Long） */
private fun toColor(v: Int): Color = Color(v.toLong() and 0xFFFFFFFFL)

/** ⭐ 第5项：按 AppThemeStyle 覆盖主色 / 背景 / 表面色（插件或设置提供），其余保持内置默认 */
private fun AppThemeStyle.overrideColors(dark: Boolean): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    return base.copy(
        primary = if (primary != null) toColor(primary) else base.primary,
        background = if (background != null) toColor(background) else base.background,
        surface = if (surface != null) toColor(surface) else base.surface,
    )
}

@Composable
fun AiAndroidTheme(
    appTheme: AppThemeStyle = AppThemeStyle.DEFAULT,
    content: @Composable () -> Unit,
) {
    val isDark = when (appTheme.mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    // ⭐ 无自定义颜色（=DEFAULT 或全 null）时用内置默认色，避免无谓重建 ColorScheme
    val colorScheme = if (appTheme == AppThemeStyle.DEFAULT)
        (if (isDark) DarkColors else LightColors)
    else appTheme.overrideColors(dark = isDark)
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
