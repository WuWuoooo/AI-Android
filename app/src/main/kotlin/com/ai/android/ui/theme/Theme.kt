package com.ai.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 可配置的软件主题风格。
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

/**
 * 三套内置风格预设。
 * - "native"   原生（Material 默认配色）
 * - "minimal"  现代极简（浅灰底、纯白面、大留白、细字）
 * - "brutal"  新粗野主义（高饱和撞色、粗字重）
 */
data class ThemePreset(
    val key: String,
    val label: String,
    val light: ColorScheme,
    val dark: ColorScheme,
) {
    companion object {
        val NATIVE = ThemePreset(
            key = "native", label = "原生",
            light = lightColorScheme(
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
            ),
            dark = darkColorScheme(
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
            ),
        )

        val MINIMAL = ThemePreset(
            key = "minimal", label = "现代极简",
            light = lightColorScheme(
                primary = Color(0xFF333333),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFE8E8E8),
                onPrimaryContainer = Color(0xFF1A1A1A),
                secondary = Color(0xFF666666),
                secondaryContainer = Color(0xFFEEEEEE),
                tertiary = Color(0xFF888888),
                background = Color(0xFFFAFAFA),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFF0F0F0),
                onSurfaceVariant = Color(0xFF666666),
            ),
            dark = darkColorScheme(
                primary = Color(0xFFDDDDDD),
                onPrimary = Color(0xFF111111),
                primaryContainer = Color(0xFF2A2A2A),
                onPrimaryContainer = Color(0xFFEEEEEE),
                secondary = Color(0xFF999999),
                secondaryContainer = Color(0xFF222222),
                tertiary = Color(0xFF777777),
                background = Color(0xFF0D0D0D),
                surface = Color(0xFF1A1A1A),
                surfaceVariant = Color(0xFF2A2A2A),
                onSurfaceVariant = Color(0xFFAAAAAA),
            ),
        )

        val BRUTAL = ThemePreset(
            key = "brutal", label = "新粗野主义",
            light = lightColorScheme(
                primary = Color(0xFFFF5722),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFFFCCBC),
                onPrimaryContainer = Color(0xFF5D0B00),
                secondary = Color(0xFFFFEB3B),
                secondaryContainer = Color(0xFFFFEE58),
                tertiary = Color(0xFF00E5FF),
                background = Color(0xFFFFF8F0),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFFFE0CC),
                onSurfaceVariant = Color(0xFF3E2B22),
            ),
            dark = darkColorScheme(
                primary = Color(0xFFFFAB40),
                onPrimary = Color(0xFF330000),
                primaryContainer = Color(0xFF8C2F00),
                onPrimaryContainer = Color(0xFFFFCCBC),
                secondary = Color(0xFFFFEB3B),
                secondaryContainer = Color(0xFF534700),
                tertiary = Color(0xFF00E5FF),
                background = Color(0xFF1C1200),
                surface = Color(0xFF1C1200),
                surfaceVariant = Color(0xFF4A3222),
                onSurfaceVariant = Color(0xFFFFCCBC),
            ),
        )

                fun all(): List<ThemePreset> = listOf(MINIMAL, NATIVE, BRUTAL)   // #11：极简默认排第1，原生第2
        fun find(key: String): ThemePreset = all().firstOrNull { it.key == key } ?: MINIMAL
    }
}

/** 把十进制 ARGB Int 转成 Compose Color（负数/越界安全：转无符号 Long） */
private fun toColor(v: Int): Color = Color(v.toLong() and 0xFFFFFFFFL)

@Composable
fun AiAndroidTheme(
    appTheme: AppThemeStyle = AppThemeStyle.DEFAULT,
    stylePreset: String = "minimal",
    /** ⭐ #1：变化时强制本 Composable 重新计算（监听 settings.revision，切主题即时生效） */
    revisionKey: Int = 0,
    content: @Composable () -> Unit,
) {
    // revisionKey 变化 → 下面所有计算重跑（stylePreset 已是最新值）
    val isDark = when (appTheme.mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val preset = ThemePreset.find(stylePreset)
    val baseScheme = if (isDark) preset.dark else preset.light

    // 插件 THEME 覆盖优先于预设
    val colorScheme = if (appTheme != AppThemeStyle.DEFAULT) {
        baseScheme.copy(
            primary = if (appTheme.primary != null) toColor(appTheme.primary!!) else baseScheme.primary,
            background = if (appTheme.background != null) toColor(appTheme.background!!) else baseScheme.background,
            surface = if (appTheme.surface != null) toColor(appTheme.surface!!) else baseScheme.surface,
        )
    } else baseScheme

    // typography 覆盖（在 composable 上下文里用 MaterialTheme.typography.copy()，安全）
    val typography = when (preset.key) {
        "minimal" -> MaterialTheme.typography.copy(
            titleLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 20.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
            bodyLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 15.sp),
            bodyMedium = TextStyle(fontWeight = FontWeight.Light, fontSize = 14.sp),
            bodySmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 12.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 11.sp),
        )
        "brutal" -> MaterialTheme.typography.copy(
            titleLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 24.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp),
            bodyLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
            bodyMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp),
            bodySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp),
        )
        else -> MaterialTheme.typography // native: 无覆盖
    }

                // ⭐ 第五轮 #5/#6：系统状态栏 + 底部导航栏配色跟主题 background 同色（minimal 浅色 = FAFAFA，跟聊天背景一体）。
    // SideEffect 跟随主题切换自动重刷；不依赖 androidx.core 的 ContextCompat（避免 Unresolved），
    //    手动沿 ContextWrapper.getBaseContext() 链解包到 Activity。
    val ctx = LocalContext.current
    SideEffect {
        val bg = colorScheme.background
        val statusBar = if (isDark) Color.Black else bg
        // 手动解包：LocalContext 在 Activity 里是 ContextThemeWrapper（继承 ContextWrapper），
        //    沿 baseContext 链走到最外层就是 Activity（或 Application Context）
        var unwrapped: android.content.Context = ctx
        while (unwrapped is android.content.ContextWrapper && unwrapped.baseContext != null) {
            unwrapped = unwrapped.baseContext
        }
        (unwrapped as? android.app.Activity)?.window?.let { w ->
            runCatching {
                // statusBarColor / navigationBarColor 是 Int（ARGB），Compose Color 须 .toArgb()
                w.statusBarColor = statusBar.toArgb()
                w.navigationBarColor = bg.toArgb()
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    // 关系统自动对比色（否则浅色主题系统会强制把导航栏涂黑）+ 状态栏图标随深浅色切
                    w.isStatusBarContrastEnforced = false
                    w.isNavigationBarContrastEnforced = false
                    val flags = w.decorView.systemUiVisibility
                    w.decorView.systemUiVisibility = if (isDark)
                        flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    else
                        flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}
