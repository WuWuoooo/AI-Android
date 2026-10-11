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
                put("model", "gpt-image-2.5-flare")
                put("prompt", prompt)
                put("n", 1)
                put("size", size)
                put("response_format", "b64_json")
            }
            // ⭐ 端点智能判断：baseUrl 已填完整 images 端点就直接用；
            // 含 /chat/completions 则剥掉再拼 /images/generations；其余情况拼 /images/generations
            val base = baseUrl.trimEnd('/')
            val imgUrl = when {
                base.endsWith("/images/generations") -> base
                base.endsWith("/chat/completions")   -> base.removeSuffix("/chat/completions") + "/images/generations"
                else                                 -> base + "/images/generations"
            }
            val req = Request.Builder()
                .url(imgUrl)
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
                // ⭐ 第五轮：voice 为空（默认）时兜底 OpenAI 的 alloy
                put("voice", voice.ifBlank { "alloy" })
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
 * MiMo TTS（小米 MiMo 语音合成）。
 * 走 OpenAI 兼容 chat/completions，body 带 messages（assistant 放正文）+ audio:{format,voice}，
 * 取 choices[0].message.audio.data（base64）解码返回 wav 字节（24kHz PCM16LE mono）。
 *
 * 预置音色：mimo_default / 冰糖 / 茉莉 / 苏打 / 白桦 / Mia / Chloe / Milo / Dean
 *
 * ⚠️ V2.5 系列将于 2026.10.21 迁移到 V2.6，届时 model id 会失效；
 * model 通过构造函数外部传入（由 SettingsRepository 用 cfg.model 或默认 mimo-v2.5-tts），无需改代码。
 */
class MimoMultimodal(
    override val name: String,
    private val baseUrl: String = "https://api.xiaomimimo.com/v1",
    private val apiKey: String,
    /** 模型 ID：默认 mimo-v2.5-tts；V2.6 迁移时外部传 mimo-v2.6-tts 即可 */
    private val model: String = "mimo-v2.5-tts",
        /** 默认音色（留空 / 传非法音色时兜底）。默认「冰糖」：MiMo 中国集群预置默认中文女声，读中文最自然 */
    private val defaultVoice: String = "冰糖",
) : MultimodalProvider {

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** MiMo V2.5 预置音色白名单（v2.5 文档）；传入非法音色（如 OpenAI 的 alloy）会 400，须兜底 */
    private val VALID_VOICES = setOf(
        "mimo_default", "冰糖", "茉莉", "苏打", "白桦",
        "Mia", "Chloe", "Milo", "Dean",
    )

    override suspend fun textToSpeech(text: String, voice: String): ByteArray =
        withContext(Dispatchers.IO) {
            // ⭐ 第五轮 Bug 4（根因）：MiMo 只认预置音色。外部传入的 voice 若不是 MiMo 合法音色
            // （比如 OpenAI 默认 "alloy"），直接发会 HTTP 400 → 朗读无声。这里校验白名单，非法/空 → 兜底 defaultVoice。
            val requested = voice.ifBlank { defaultVoice }
            val v = if (requested in VALID_VOICES) requested else defaultVoice
            val body = buildJsonObject {
                put("model", model)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", text.take(4096))
                    })
                })
                put("audio", buildJsonObject {
                    put("format", "wav")
                    put("voice", v)
                })
            }
                        // ⭐ 端点归一化：baseUrl 可能带/不带 /v1、甚至填了完整 /chat/completions，统一收敛
            val b = baseUrl.trimEnd('/')
            val url = when {
                b.endsWith("/chat/completions") -> b
                b.endsWith("/v1") -> "$b/chat/completions"
                else -> "$b/v1/chat/completions"
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("MiMo TTS HTTP ${resp.code}（voice=$v, url=$url）: ${respText.take(300)}")
                val root = json.parseToJsonElement(respText).jsonObject
                val b64 = root["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject
                    ?.get("audio")?.jsonObject
                    ?.get("data")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (b64.isEmpty()) throw IllegalStateException("MiMo TTS 响应缺少 choices[0].message.audio.data")
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
        }

    // MiMo 目前只提供 TTS，不支持文生图 / 识图 / ASR
    override suspend fun generateImage(prompt: String, size: String): MultimodalProvider.ImageResult =
        MultimodalProvider.ImageResult(false, error = "MiMo 不支持文生图")

    override suspend fun visionChat(imageBase64: String, question: String): String =
        throw IllegalStateException("MiMo 不支持识图")

    override suspend fun speechToText(audioBytes: ByteArray): String =
        throw IllegalStateException("MiMo 不支持 ASR")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 智谱 GLM-Image 文生图（国产芯片训练的自回归+扩散混合架构）。
 * 端点：{base}/images/generations，model=glm-image，响应 data[0].url 是图片 URL（非 base64）。
 * 需下载该 URL 再转 base64 返回，供 generate_image 工具落盘。
 *
 * baseUrl 容忍：用户常填成 .../v4/web_search 或 .../v4/chat/completions（复用同一个 key），
 * 本实现自动剥离已知后缀段，归一化到版本根（.../v4）再拼 /images/generations，避免 404。
 */
class ZhipuImageMultimodal(
    override val name: String,
    private val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    private val apiKey: String,
    private val model: String = "glm-image",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /** 归一化智谱端点：剥掉 /web_search、/chat/completions、/images/generations 等末段，回到版本根 */
    private fun imageEndpoint(): String {
        val base = baseUrl.trimEnd('/')
        val root = base
            .substringBefore("/web_search")
            .substringBefore("/chat/completions")
            .substringBefore("/images/generations")
            .trimEnd('/')
        return root + "/images/generations"
    }

    override suspend fun generateImage(prompt: String, size: String): MultimodalProvider.ImageResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model.ifBlank { "glm-image" })
                put("prompt", prompt)
                // GLM-Image 支持 1:1/4:3/3:4 等；size 传推荐尺寸，非法则走默认
                if (size.isNotBlank()) put("size", size)
            }
            val req = Request.Builder()
                .url(imageEndpoint())
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use MultimodalProvider.ImageResult(
                        false, error = "GLM-Image HTTP ${resp.code}: ${text.take(300)}"
                    )
                    val root = json.parseToJsonElement(text).jsonObject
                    // 响应：{ "data": [ { "url": "https://..." } ] } 或 { "url": "..." }
                    val url = root["data"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: root["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (url.isBlank()) return@use MultimodalProvider.ImageResult(
                        false, url = url, error = "GLM-Image 响应缺少图片 URL"
                    )
                    // 下载图片 URL → base64（供工具落盘 / 展示）
                    val dlReq = Request.Builder().url(url).build()
                    client.newCall(dlReq).execute().use { dl ->
                        if (!dl.isSuccessful) return@use MultimodalProvider.ImageResult(
                            false, url = url, error = "下载图片 URL 失败 HTTP ${dl.code}"
                        )
                        val bytes = dl.body?.bytes() ?: ByteArray(0)
                        val b64 = android.util.Base64.encodeToString(
                            bytes, android.util.Base64.NO_WRAP
                        )
                        MultimodalProvider.ImageResult(true, url = url, base64 = b64)
                    }
                }
            }.getOrElse { MultimodalProvider.ImageResult(false, error = it.message ?: "GLM-Image 未知错误") }
        }

    override suspend fun visionChat(imageBase64: String, question: String): String =
        throw IllegalStateException("智谱 GLM-Image 不支持识图")

    override suspend fun textToSpeech(text: String, voice: String): ByteArray =
        throw IllegalStateException("智谱 GLM-Image 不支持 TTS")

    override suspend fun speechToText(audioBytes: ByteArray): String =
        throw IllegalStateException("智谱 GLM-Image 不支持 ASR")

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

        /** ⭐ 第五轮：默认 voice 传空串，由各 Provider 自行兜底（MiMo 会校验预置音色白名单，OpenAI 兜底 alloy） */
    suspend fun tts(text: String, voice: String = ""): ByteArray =
        ttsProvider?.textToSpeech(text, voice) ?: throw IllegalStateException("未配置 TTS Provider")

    suspend fun asr(bytes: ByteArray): String =
        asrProvider?.speechToText(bytes) ?: throw IllegalStateException("未配置 ASR Provider")
}