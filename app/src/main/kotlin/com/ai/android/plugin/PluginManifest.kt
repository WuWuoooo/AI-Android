package com.ai.android.plugin

import kotlinx.serialization.Serializable

/**
 * 插件清单：由导入的 .zip 包内 manifest.json 解析而来。
 *
 * beta5：挂件为静态资源（iconPath 指向 zip 内图标）；
 * beta6：entryPoint 将作为 JS 模块名加载执行。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    /** 取值："WIDGET" / "THEME" / "TOOL" / "EVENT_HOOK" / "MIRROR" */
    val capabilities: List<String> = emptyList(),
    /** beta5 = 静态资源路径；beta6 = JS 模块名 */
    val entryPoint: String = "",
    /** 挂件图标相对路径（相对插件目录） */
    val iconPath: String = "",
)
