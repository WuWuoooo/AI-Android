package com.ai.android.provider

import com.ai.android.model.ChatMessage
import com.ai.android.model.ToolCall
import com.ai.android.model.TokenStats
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** 流式事件 */
sealed class StreamEvent {
    data class Reasoning(val text: String) : StreamEvent()
    data class Content(val text: String) : StreamEvent()
    data class ToolCalls(val calls: List<ToolCall>) : StreamEvent()
    data class Stats(val stats: TokenStats) : StreamEvent()
    data class Error(val msg: String) : StreamEvent()
    object Done : StreamEvent()
}

/** AI Provider 接口 */
interface AIProvider {
    val name: String
    val baseUrl: String
    val apiKey: String
    val model: String
    val protocol: Protocol

    @Serializable
    enum class Protocol { OPENAI, ANTHROPIC }

    fun chatStream(
        messages: List<ChatMessage>,
        tools: List<JsonObject> = emptyList(),
        reasoningEffort: String = "",
        temperature: Float = 0.3f,
    ): Flow<StreamEvent>

    suspend fun chatOnce(
        messages: List<ChatMessage>,
        temperature: Float = 0.2f,
    ): String
}
