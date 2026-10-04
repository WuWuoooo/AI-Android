package com.ai.android.plugin

import androidx.compose.material3.ColorScheme

/**
 * 主题插槽：插件可提供 Compose 主题方案与挂件造型标识。
 *
 * beta5 只定义接口；beta6 允许插件在运行时切换主题。
 */
interface ThemeProvider {

    val manifest: PluginManifest

    /** 主题名（显示在设置页） */
    fun name(): String

    /** Compose 主题配色 */
    fun colorScheme(): ColorScheme

    /** 造型标识（例：whale / robot / 默认球） */
    fun widgetShape(): String
}
