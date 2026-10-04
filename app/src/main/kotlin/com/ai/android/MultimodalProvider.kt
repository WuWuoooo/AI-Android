package com.ai.android.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 多模态能力抽象：文生图 / 识图 / TTS / ASR。
 * 2026 年 9 月最新 API 格局。
 */
interface MultimodalProvider {
    val name: String

    /** 文生图 */
    suspend fun generateImage(prompt: String, size: String = "1024x1024"): ImageResult

    /** 识图（图片 + 问题 → 文字回答） */
    suspend fun visionChat(imageBase64: String, question: String): String

    /** TTS（文字 → 音频字节） */
    suspend fun textToSpeech(text: String, voice: String = "alloy"): ByteArray

    /** ASR（音频 → 文字） */
    suspend fun speechToText(audioBytes: ByteArray): String

    data class ImageResult(
        val success: Boolean,
        val url: String = "",
        val base64: String = "",
        val error: String = "",
    )
}

/**
 * OpenAI 多模态实现（2026 年 9 月）。
 *
 * - 文生图：GPT-Image-2.5（Flare / Sunburst），取代已弃用的 DALL-E 3。
 *   2026 年 9 月 9 日发布，生成延迟最多降低 50%，新增 Sketch 手绘输入。
 *   API 端拆分为 Flare（速度优先）和 Sunburst（精度优先）两档。
 * - 识图：GPT-5.6 Sol / Luna 系列视觉能力。
 * - TTS/ASR：tts-1 / whisper-1。
 * - 实时语音：GPT-Realtime-2（GPT-5 级推理）、GPT-Realtime-Translate、GPT-Realtime-Whisper。
 */
class OpenAIMultimodal(
    override val name: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val apiKey: String,
    private val model: String = "gpt-5.6-sol",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun generateImage(prompt: String, size: String): MultimodalProvider.ImageResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                // 2026 年 9 月：GPT-Image-2.5，Flare 速度优先 / Sunburst 精度优先
                put("model", "gpt-image-2.5-flare")
                put("prompt", prompt)
                put("n", 1)
                put("size", size)
                put("response_format", "b64_json")
            }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/images/generations")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use MultimodalProvider.ImageResult(
                        false, error = "HTTP ${resp.code}: ${text.take(300)}"
                    )
                    val root = json.parseToJsonElement(text).jsonObject
                    val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    val b64 = item?.get("b64_json")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val url = item?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                    MultimodalProvider.ImageResult(true, url = url, base64 = b64)
                }
            }.getOrElse { MultimodalProvider.ImageResult(false, error = it.message ?: "未知错误") }
        }

    override suspend fun visionChat(imageBase64: String, question: String): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model.ifBlank { "gpt-5.6-sol" })
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", question) })
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:image/jpeg;base64,$imageBase64")
                                })
                            })
                        })
                    })
                })
                put("max_tokens", 4096)
            }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
                json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }

    override suspend fun textToSpeech(text: String, voice: String): ByteArray =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", "tts-1")
                put("input", text.take(4096))
                put("voice", voice)
            }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/audio/speech")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("TTS HTTP ${resp.code}")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }

    override suspend fun speechToText(audioBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file", "audio.m4a",
                    audioBytes.toRequestBody("audio/m4a".toMediaType())
                )
                .build()
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(multipart)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("ASR HTTP ${resp.code}: ${text.take(200)}")
                json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Anthropic 多模态实现（2026 年 9 月）。
 * Claude Opus 5 / Sonnet 5 支持视觉理解，图片最长边可达 2576 像素。
 * 图片格式：base64 或 URL，放在 content 数组的 image block 中。
 */
class AnthropicVision(
    override val name: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val model: String = "claude-opus-5",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun generateImage(prompt: String, size: String): MultimodalProvider.ImageResult =
        MultimodalProvider.ImageResult(false, error = "Anthropic 不支持文生图")

    override suspend fun visionChat(imageBase64: String, question: String): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", 4096)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", imageBase64)
                                })
                            })
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", question)
                            })
                        })
                    })
                })
            }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
                val root = json.parseToJsonElement(text).jsonObject
                root["content"]?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }

    override suspend fun textToSpeech(text: String, voice: String): ByteArray =
        throw IllegalStateException("Anthropic 不支持 TTS")

    override suspend fun speechToText(audioBytes: ByteArray): String =
        throw IllegalStateException("Anthropic 不支持 ASR")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 通义千问多模态实现（2026 年 9 月）。
 *
 * - 文生图：Qwen-Image-3.0-Pro（2026 年 8 月 5 日上线）。
 * - 识图：Qwen3.8-Max 多模态理解。
 * - TTS：Qwen3-TTS-Flash，支持 11 种语言。
 * - ASR：Qwen-ASR。
 * - 全模态：Qwen3.8-Omni-Flash 支持图像/音频/视频输入，文本+语音输出。
 */
class QwenMultimodal(
    override val name: String,
    private val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    private val apiKey: String,
    private val model: String = "qwen3.8-max",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun generateImage(prompt: String, size: String): MultimodalProvider.ImageResult =
        withContext(Dispatchers.IO) {
            // Qwen-Image-3.0-Pro
            val body = buildJsonObject {
                put("model", "qwen-image-3.0-pro")
                put("input", buildJsonObject { put("prompt", prompt) })
                put("parameters", buildJsonObject {
                    put("size", size.replace("x", "*"))
                    put("n", 1)
                })
            }
            val req = Request.Builder()
                .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis")
                .header("Authorization", "Bearer $apiKey")
                .header("X-DashScope-Async", "enable")
                .post(body.toString().toRequestBody(JSON))
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use MultimodalProvider.ImageResult(
                        false, error = "HTTP ${resp.code}: ${text.take(300)}"
                    )
                    val taskId = json.parseToJsonElement(text).jsonObject["output"]
                        ?.jsonObject?.get("task_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                    MultimodalProvider.ImageResult(true, url = "task:$taskId")
                }
            }.getOrElse { MultimodalProvider.ImageResult(false, error = it.message ?: "未知错误") }
        }

    override suspend fun visionChat(imageBase64: String, question: String): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", question) })
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:image/jpeg;base64,$imageBase64")
                                })
                            })
                        })
                    })
                })
            }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
                json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }

    override suspend fun textToSpeech(text: String, voice: String): ByteArray =
        withContext(Dispatchers.IO) {
            // Qwen3-TTS-Flash
            val body = buildJsonObject {
                put("model", "qwen3-tts-flash")
                put("input", buildJsonObject { put("text", text.take(2000)) })
                put("parameters", buildJsonObject {
                    put("voice", voice.ifBlank { "Cherry" })
                    put("format", "wav")
                })
            }
            val req = Request.Builder()
                .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("TTS HTTP ${resp.code}")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }

    override suspend fun speechToText(audioBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", "qwen-asr")
                put("input", buildJsonObject {
                    put("audio", "data:audio/m4a;base64," +
                        android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP))
                })
            }
            val req = Request.Builder()
                .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("ASR HTTP ${resp.code}")
                text.take(5000)
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 多模态管理器。
 */
class MultimodalManager {

    private var imageProvider: MultimodalProvider? = null
    private var visionProvider: MultimodalProvider? = null
    private var ttsProvider: MultimodalProvider? = null
    private var asrProvider: MultimodalProvider? = null

    fun setImage(p: MultimodalProvider?) { imageProvider = p }
    fun setVision(p: MultimodalProvider?) { visionProvider = p }
    fun setTts(p: MultimodalProvider?) { ttsProvider = p }
    fun setAsr(p: MultimodalProvider?) { asrProvider = p }

    val hasImage get() = imageProvider != null
    val hasVision get() = visionProvider != null
    val hasTts get() = ttsProvider != null
    val hasAsr get() = asrProvider != null

    suspend fun image(prompt: String, size: String = "1024x1024"): MultimodalProvider.ImageResult =
        imageProvider?.generateImage(prompt, size)
            ?: MultimodalProvider.ImageResult(false, error = "未配置文生图 Provider")

    suspend fun vision(b64: String, q: String): String =
        visionProvider?.visionChat(b64, q) ?: throw IllegalStateException("未配置识图 Provider")

    suspend fun tts(text: String, voice: String = "alloy"): ByteArray =
        ttsProvider?.textToSpeech(text, voice) ?: throw IllegalStateException("未配置 TTS Provider")

    suspend fun asr(bytes: ByteArray): String =
        asrProvider?.speechToText(bytes) ?: throw IllegalStateException("未配置 ASR Provider")
}