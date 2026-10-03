package com.ai.android.model

import java.util.UUID

/**
 * 聊天消息模型
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
    val images: List<String> = emptyList(),
    val tokenStats: TokenStats? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val error: String? = null
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

    companion object {
        fun user(content: String) = ChatMessage(role = Role.USER, content = content)
        fun system(content: String) = ChatMessage(role = Role.SYSTEM, content = content)
        fun assistant(content: String) = ChatMessage(role = Role.ASSISTANT, content = content)
        fun tool(toolCallId: String, content: String) =
            ChatMessage(
                role = Role.TOOL,
                content = content,
                toolResults = listOf(ToolResult(toolCallId = toolCallId, name = "", output = content)),
            )
    }
}

/**
 * 工具调用（模型发起）
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String, // JSON string
)

/**
 * 工具执行结果
 */
data class ToolResult(
    val toolCallId: String,
    val name: String,
    val output: String,
    val success: Boolean = true,
    val durationMs: Long = 0,
)

/**
 * Token 统计
 */
data class TokenStats(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitTokens: Int = 0,
) {
    val cacheRate: String
        get() = if (promptTokens > 0)
            String.format("%.1f%%", cacheHitTokens * 100.0 / promptTokens)
        else "0%"
}
