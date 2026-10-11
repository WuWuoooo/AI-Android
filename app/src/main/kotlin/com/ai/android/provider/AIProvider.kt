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
    /**
     * ⭐ v1.2.0 #2：流式结束原因（模型停止类型）。
     * - OpenAI: "stop" / "length"(max_tokens) / "tool_calls" / "content_filter"
     * - Anthropic: "end_turn" / "max_tokens" / "stop_sequence" / "tool_use"
     * AgentCore 据此判断是否截断。
     */
    data class FinishInfo(val reason: String) : StreamEvent()
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

/**
 * ⭐ 工具调用配对清理（发请求前务必调用，根治 HTTP 400）：
 * OpenAI / Anthropic 都要求——assistant 消息里带 tool_calls 时，其后必须紧跟对应
 * tool_call_id 的「工具结果」消息，否则服务端直接返回 400。流式中断、自动重试、
 * 手动停止等场景常会留下「孤儿调用 / 孤儿结果」，导致之后每次请求都复现 400。
 *
 * 本函数做 4 件事：
 *  1. 剔除没有对应工具结果的 assistant.tool_calls（孤儿调用）；
 *  2. 剔除找不到对应 assistant.tool_calls 的 tool 结果（孤儿结果）；
 *  3. 校验每条 tool_call 的 arguments 是合法 JSON 对象，非法则替换为 "{}"
 *     （流式中断时 arguments 可能被截断成非法 JSON，直接发出去会 400）；
 *  4. 丢弃「既无正文也无有效工具调用」的空 assistant 消息（部分服务端会 400）。
 */
fun sanitizeToolMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val declared = messages.asSequence()
        .filter { it.role == ChatMessage.Role.ASSISTANT }
        .flatMap { it.toolCalls.map { tc -> tc.id } }
        .filter { it.isNotBlank() }
        .toSet()
    val hasResult = messages.asSequence()
        .filter { it.role == ChatMessage.Role.TOOL }
        .flatMap { it.toolResults.map { r -> r.toolCallId } }
        .filter { it.isNotBlank() }
        .toSet()

    return messages.mapNotNull { m ->
        when (m.role) {
            ChatMessage.Role.TOOL -> {
                val cid = m.toolResults.firstOrNull()?.toolCallId.orEmpty()
                // 仅当该结果确实对应某个已声明的 tool_call 才保留，否则视为孤儿丢弃
                if (cid.isNotEmpty() && cid in declared) m else null
            }
            ChatMessage.Role.ASSISTANT -> {
                // 剔除没有对应工具结果的调用；同时校验 arguments 是合法 JSON
                val kept = m.toolCalls
                    .filter { tc -> tc.name.isNotBlank() && tc.id in hasResult }
                    .map { tc -> tc.copy(arguments = ensureValidJsonObject(tc.arguments)) }
                val hasContent = m.content.isNotBlank()
                // 既无正文、也无有效工具调用 → 丢弃（避免发出 {"role":"assistant","content":null} 触发 400）
                if (!hasContent && kept.isEmpty()) null
                else m.copy(toolCalls = kept)
            }
            else -> m
        }
    }
}

/**
 * 确保 tool_call.arguments 是合法 JSON 对象字符串，否则退回 "{}"。
 * 流式中断时 arguments 常被截断成非法 JSON，直接发给服务端会 400。
 */
internal fun ensureValidJsonObject(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return "{}"
    return runCatching {
        val el = Json.parseToJsonElement(s)
        if (el is JsonObject) s else "{}"
    }.getOrDefault("{}")
}