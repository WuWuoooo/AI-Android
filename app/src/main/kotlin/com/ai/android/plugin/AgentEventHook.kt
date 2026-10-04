package com.ai.android.plugin

import android.graphics.Bitmap
import com.ai.android.model.AgentState

/**
 * Agent 事件钩子（sealed interface）。
 *
 * 宿主在关键节点产生这些事件，广播给已注册的事件监听者（AgentEventListener）。
 * beta6 时 JS 插件可订阅这些事件；beta5 只定义数据模型。
 */
sealed interface AgentEventHook {

    /** Token 统计更新（每条 assistant 消息完成后） */
    data class OnTokenUpdate(
        val prompt: Int,
        val completion: Int,
        val cache: Int,
    ) : AgentEventHook

    /** Agent 状态变化 */
    data class OnAgentState(val state: AgentState) : AgentEventHook

    /** 工具调用开始 */
    data class OnToolCall(val toolName: String, val args: String) : AgentEventHook

    /** 镜像帧到达（AI 操控手机时，2fps 推送屏幕画面） */
    data class OnMirrorFrame(val bitmap: Bitmap) : AgentEventHook
}

/**
 * 事件监听者：插件（beta6）或宿主内部组件实现此接口订阅事件。
 * 纯接口，不实现任何分发逻辑。
 */
interface AgentEventListener {
    fun onEvent(event: AgentEventHook)
}
