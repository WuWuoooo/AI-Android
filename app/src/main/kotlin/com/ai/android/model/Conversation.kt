package com.ai.android.model

import kotlinx.serialization.Serializable

/**
 * 会话（一次完整对话）
 */
data class Conversation(
    val id: String,
    val title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val providerName: String = "",
    val model: String = "",
) {
    val lastMessageTime: Long
        get() = messages.lastOrNull()?.timestamp ?: createdAt

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        updatedAt = msg.timestamp
    }
}

/**
 * Agent 运行状态（用于悬浮窗展示）
 */
data class AgentState(
    val status: Status = Status.IDLE,
    val currentTool: String = "",
    val progressText: String = "",
    val startedAt: Long = 0,
) {
    enum class Status { IDLE, THINKING, TOOL_CALLING, WAITING_USER, COMPLETED, ERROR }

    val isActive: Boolean get() = status != Status.IDLE && status != Status.COMPLETED && status != Status.ERROR
}

/**
 * 定时任务定义
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val type: TaskType,
    val intervalMinutes: Int = 0,
    val timeOfDay: String = "", // "HH:mm"
    val daysOfWeek: String = "", // "1,2,3,4,5"
    val conversationId: String = "",
    val enabled: Boolean = true,
    val lastRunAt: Long = 0,
    val nextRunAt: Long = 0,
) {
    enum class TaskType { ONCE, INTERVAL, DAILY, WEEKLY }
}
