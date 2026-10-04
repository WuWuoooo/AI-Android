package com.ai.android.provider

import com.ai.android.model.ChatMessage
import com.ai.android.model.ToolCall
import com.ai.android.model.TokenStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * OpenAI / 兼容协议 Provider（DeepSeek、Moonshot、通义、Ollama 等）。
 * 使用 okhttp-sse 做流式解析，支持 tool_calls 与 reasoning_content。
 *
 * v1.0.0-beta2-fix2：
 *  - 新增 extraBodyJson：用户自定义请求体，会 merge 进每次请求（冲突时以用户填的为准）
 *  - 新增 extraHeaders：自定义请求头，适配第三方代理 / 转发服务
 */
class OpenAIProvider(
    override val name: String,
    override var baseUrl: String,
    override var apiKey: String,
    override var model: String,
    private val extraBodyJson: String = "",
    private val extraHeaders: Map<String, String> = emptyMap(),
    override val protocol: AIProvider.Protocol = AIProvider.Protocol.OPENAI,
) : AIProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    /** 流式 tool_calls 增量累积器 */
    private class PartialTool {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<JsonObject>,
        reasoningEffort: String,
        temperature: Float,
    ): Flow<StreamEvent> = channelFlow {
        val body = mergeExtra(buildJsonObject {
            put("model", model)
            put("messages", buildMessages(messages))
            put("stream", true)
            put("temperature", temperature.toDouble())
            if (tools.isNotEmpty()) put("tools", JsonArray(tools))
            if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
        })

        val builder = Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val partials = LinkedHashMap<Int, PartialTool>()
        val scope = this

        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    flushToolCalls(scope, partials)
                    close()
                    return
                }
                handleChunk(scope, data, partials)
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code ?: 0
                val msg = t?.message ?: "HTTP $code"
                scope.trySend(StreamEvent.Error("流式连接失败: $msg"))
                close()
            }

            override fun onClosed(es: EventSource) {
                flushToolCalls(scope, partials)
                close()
            }
        }

        val source = sseFactory.newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private fun handleChunk(
        scope: SendChannel<StreamEvent>,
        data: String,
        partials: MutableMap<Int, PartialTool>,
    ) {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return

        root["error"]?.let {
            val m = it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it.toString()
            scope.trySend(StreamEvent.Error(m))
            return
        }

        val choices = root["choices"]?.let { runCatching { it.jsonArray }.getOrNull() }
        if (choices.isNullOrEmpty()) {
            root["usage"]?.let { u ->
                runCatching { scope.trySend(StreamEvent.Stats(parseUsage(u.jsonObject))) }
            }
            return
        }

        val choice = choices.first().jsonObject
        val delta = choice["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }

        delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            scope.trySend(StreamEvent.Reasoning(it))
        }
        delta?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            scope.trySend(StreamEvent.Content(it))
        }

        delta?.get("tool_calls")?.let { runCatching { it.jsonArray }.getOrNull() }?.forEach { el ->
            val tc = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            val idx = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
            val p = partials.getOrPut(idx) { PartialTool() }
            tc["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { p.id = it }
            val fn = tc["function"]?.let { runCatching { it.jsonObject }.getOrNull() }
            fn?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { p.name = it }
            fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { p.args.append(it) }
        }

        val fin = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        if (fin == "tool_calls") flushToolCalls(scope, partials)

        root["usage"]?.let { u ->
            if (u !is JsonNull) runCatching { scope.trySend(StreamEvent.Stats(parseUsage(u.jsonObject))) }
        }
    }

    private fun flushToolCalls(
        scope: SendChannel<StreamEvent>,
        partials: MutableMap<Int, PartialTool>,
    ) {
        if (partials.isEmpty()) return
        val calls = partials.toSortedMap().entries.map { (i, p) ->
            ToolCall(
                id = p.id.ifEmpty { "call_${System.currentTimeMillis()}_$i" },
                name = p.name,
                arguments = p.args.toString().ifEmpty { "{}" },
            )
        }.filter { it.name.isNotEmpty() }
        if (calls.isNotEmpty()) scope.trySend(StreamEvent.ToolCalls(calls))
        partials.clear()
    }

    private fun parseUsage(u: JsonObject) = TokenStats(
        promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
        cacheHitTokens = u["prompt_tokens_details"]?.let { d ->
            runCatching { d.jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull }.getOrNull()
        } ?: 0,
    )

    override suspend fun chatOnce(messages: List<ChatMessage>, temperature: Float): String =
        withContext(Dispatchers.IO) {
            val body = mergeExtra(buildJsonObject {
                put("model", model)
                put("messages", buildMessages(messages))
                put("stream", false)
                put("temperature", temperature.toDouble())
            })
            val builder = Request.Builder()
                .url(endpoint())
                .header("Authorization", "Bearer $apiKey")
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            val request = builder
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
                val root = json.parseToJsonElement(text).jsonObject
                root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }

    /** 把用户自定义 extraBodyJson merge 进 base（extra 优先） */
    private fun mergeExtra(base: JsonObject): JsonObject {
        if (extraBodyJson.isBlank()) return base
        val extra = runCatching { json.parseToJsonElement(extraBodyJson).jsonObject }.getOrNull()
            ?: return base
        return buildJsonObject {
            base.forEach { (k, v) -> if (k !in extra) put(k, v) }
            extra.forEach { (k, v) -> put(k, v) }
        }
    }

    private fun buildMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        messages.forEach { m ->
            when (m.role) {
                ChatMessage.Role.TOOL -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.toolResults.firstOrNull()?.toolCallId ?: m.id)
                    put("content", m.content)
                })

                ChatMessage.Role.ASSISTANT -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", if (m.content.isBlank()) JsonNull else JsonPrimitive(m.content))
                    if (m.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                        m.toolCalls.forEach { tc -> add(buildJsonObject {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                            })
                        }) }
                    })
                })

                ChatMessage.Role.USER -> {
                    if (m.images.isEmpty()) {
                        add(buildJsonObject { put("role", "user"); put("content", m.content) })
                    } else {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", m.content) })
                                m.images.forEach { img ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", "data:image/jpeg;base64,$img")
                                        })
                                    })
                                }
                            })
                        })
                    }
                }

                else -> add(buildJsonObject {
                    put("role", m.role.name.lowercase())
                    put("content", m.content)
                })
            }
        }
    }

    private fun endpoint(): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}