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
 * Anthropic Provider（/v1/messages）。
 * SSE 事件：message_start / content_block_* / message_delta / message_stop。
 * 支持 text_delta / thinking_delta / input_json_delta(tool_use)。
 *
 * v1.0.0-beta2-fix2：
 *  - 新增 extraBodyJson / extraHeaders，适配第三方代理
 */
class AnthropicProvider(
    override val name: String,
    override var baseUrl: String,
    override var apiKey: String,
    override var model: String,
    private val extraBodyJson: String = "",
    private val extraHeaders: Map<String, String> = emptyMap(),
    override val protocol: AIProvider.Protocol = AIProvider.Protocol.ANTHROPIC,
) : AIProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    private class PartialTool(val id: String, val name: String) {
        val args = StringBuilder()
    }

    private class UsageAcc {
        var prompt = 0
        var completion = 0
        var cache = 0
    }

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<JsonObject>,
        reasoningEffort: String,
        temperature: Float,
    ): Flow<StreamEvent> = channelFlow {
        val (system, msgs) = buildPayload(messages)
        val body = mergeExtra(buildJsonObject {
            put("model", model)
            put("max_tokens", 8192)
            if (system.isNotEmpty()) put("system", system)
            put("messages", msgs)
            put("stream", true)
            if (tools.isNotEmpty()) put("tools", convertTools(tools))
            if (reasoningEffort.isNotBlank()) {
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget(reasoningEffort))
                })
            } else {
                put("temperature", temperature.toDouble())
            }
        })

        val builder = Request.Builder()
            .url(endpoint())
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val blocks = LinkedHashMap<Int, PartialTool>()
        val completed = mutableListOf<PartialTool>()
        val usage = UsageAcc()
        val scope = this

        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                handleEvent(scope, data, blocks, completed, usage)
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                val msg = t?.message ?: "HTTP ${response?.code ?: 0}"
                scope.trySend(StreamEvent.Error("流式连接失败: $msg"))
                close()
            }

            override fun onClosed(es: EventSource) {
                emitFinal(scope, blocks, completed)
                close()
            }
        }

        val source = sseFactory.newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private fun handleEvent(
        scope: SendChannel<StreamEvent>,
        data: String,
        blocks: MutableMap<Int, PartialTool>,
        completed: MutableList<PartialTool>,
        usage: UsageAcc,
    ) {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "message_start" -> {
                val u = root["message"]?.let {
                    runCatching { it.jsonObject["usage"]?.jsonObject }.getOrNull()
                }
                usage.prompt = u?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                usage.cache = u?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            }

            "content_block_start" -> {
                val idx = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                val cb = root["content_block"]?.let { runCatching { it.jsonObject }.getOrNull() }
                if (cb?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use") {
                    blocks[idx] = PartialTool(
                        id = cb["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        name = cb["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }

            "content_block_delta" -> {
                val idx = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                val d = root["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }
                when (d?.get("type")?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> d["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { scope.trySend(StreamEvent.Content(it)) }

                    "thinking_delta" -> d["thinking"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { scope.trySend(StreamEvent.Reasoning(it)) }

                    "input_json_delta" -> d["partial_json"]?.jsonPrimitive?.contentOrNull?.let {
                        blocks[idx]?.args?.append(it)
                    }
                }
            }

            "content_block_stop" -> {
                val idx = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                blocks.remove(idx)?.takeIf { it.name.isNotEmpty() }?.let { completed.add(it) }
            }

            "message_delta" -> {
                usage.completion = root["usage"]?.let { u ->
                    runCatching { u.jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull }.getOrNull()
                } ?: usage.completion
                if (root["delta"]?.let { runCatching { it.jsonObject["stop_reason"] }.getOrNull() } != null) {
                    emitFinal(scope, blocks, completed)
                }
                scope.trySend(StreamEvent.Stats(
                    TokenStats(usage.prompt, usage.completion, usage.cache)
                ))
            }

            "message_stop" -> emitFinal(scope, blocks, completed)

            "error" -> {
                val m = root["error"]?.let {
                    runCatching { it.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                }
                scope.trySend(StreamEvent.Error(m ?: "Anthropic 返回错误"))
            }
        }
    }

    private fun emitFinal(
        scope: SendChannel<StreamEvent>,
        blocks: MutableMap<Int, PartialTool>,
        completed: MutableList<PartialTool>,
    ) {
        blocks.values.toList().forEach { if (it.name.isNotEmpty()) completed.add(it) }
        blocks.clear()
        if (completed.isEmpty()) return
        val calls = completed.map { p ->
            ToolCall(
                p.id.ifEmpty { "toolu_${System.currentTimeMillis()}" },
                p.name,
                p.args.toString().ifEmpty { "{}" },
            )
        }
        completed.clear()
        scope.trySend(StreamEvent.ToolCalls(calls))
    }

    override suspend fun chatOnce(messages: List<ChatMessage>, temperature: Float): String =
        withContext(Dispatchers.IO) {
            val (system, msgs) = buildPayload(messages)
            val body = mergeExtra(buildJsonObject {
                put("model", model)
                put("max_tokens", 4096)
                if (system.isNotEmpty()) put("system", system)
                put("messages", msgs)
                put("temperature", temperature.toDouble())
            })
            val builder = Request.Builder()
                .url(endpoint())
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            val request = builder
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
                val root = json.parseToJsonElement(text).jsonObject
                val blocks = root["content"]?.jsonArray
                blocks?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
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

    private fun buildPayload(messages: List<ChatMessage>): Pair<String, JsonArray> {
        val system = StringBuilder()
        val out = mutableListOf<JsonObject>()
        val pending = mutableListOf<JsonObject>()

        fun flushPending() {
            if (pending.isEmpty()) return
            out.add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray { pending.forEach { add(it) } })
            })
            pending.clear()
        }

        messages.forEach { m ->
            when (m.role) {
                ChatMessage.Role.SYSTEM -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(m.content)
                }

                ChatMessage.Role.TOOL -> pending.add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", m.toolResults.firstOrNull()?.toolCallId ?: m.id)
                    put("content", m.content)
                })

                ChatMessage.Role.ASSISTANT -> {
                    flushPending()
                    out.add(buildJsonObject {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            if (m.content.isNotBlank()) add(buildJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            })
                            m.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", runCatching {
                                        json.parseToJsonElement(tc.arguments)
                                    }.getOrElse { buildJsonObject { } })
                                })
                            }
                        })
                    })
                }

                ChatMessage.Role.USER -> {
                    flushPending()
                    out.add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            if (m.content.isNotBlank()) add(buildJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            })
                            m.images.forEach { img ->
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("source", buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", img)
                                    })
                                })
                            }
                        })
                    })
                }
            }
        }
        flushPending()
        return system.toString() to JsonArray(out)
    }

    private fun convertTools(tools: List<JsonObject>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            val fn = t["function"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return@forEach
            val nm = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            add(buildJsonObject {
                put("name", nm)
                fn["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", it) }
                put("input_schema", fn["parameters"] ?: buildJsonObject { put("type", "object") })
            })
        }
    }

    private fun thinkingBudget(effort: String): Int = when (effort.lowercase()) {
        "low" -> 1024
        "high" -> 16384
        else -> 4096
    }

    private fun endpoint(): String {
        val b = baseUrl.trim().trimEnd('/')
        return when {
            b.endsWith("/messages") -> b
            b.endsWith("/v1") -> "$b/messages"
            else -> "$b/v1/messages"
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}