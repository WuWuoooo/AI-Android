package com.ai.android.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
    val images: List<String> = emptyList(),
    /** 附件文件路径（用户气泡上方显示为 chip） */
    val files: List<String> = emptyList(),
    /** 引用的历史消息内容（发送时附加，渲染时显示为引用框） */
    val quotedContent: String = "",
    val tokenStats: TokenStats? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val error: String? = null,
    val parentId: String? = null,
) {
    @Serializable
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

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolResult(
    val toolCallId: String,
    val name: String,
    val output: String,
    val success: Boolean = true,
    val durationMs: Long = 0,
)

@Serializable
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