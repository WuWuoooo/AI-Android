package com.ai.android.plugin

import android.content.Context
import android.os.Bundle
import android.view.View

/**
 * 挂件插槽：宿主提供此接口，具体造型由导入的插件实现并注册。
 *
 * beta5 阶段宿主只内置"静态挂件"实现（按 manifest.iconPath 渲染图标 View），
 * beta6 将由 JS 插件在 WebView/JsBridge 内实现本接口。
 */
interface WidgetSlot {

    /** 挂件关联的插件清单（用于宿主显示名称、判断能力） */
    val manifest: PluginManifest

    /**
     * 渲染自定义 View。
     * @param context 宿主上下文
     * @param onExpand 用户点击挂件时回调（宿主展开面板）
     * @return 渲染出的 View
     */
    fun render(context: Context, onExpand: () -> Unit): View

    /** 宿主向挂件推送数据（Token 统计、Agent 状态等），key 自定 */
    fun onBind(data: Bundle) {}

    /** 解绑清理（插件被禁用 / 悬浮窗销毁） */
    fun onUnbind() {}
}
