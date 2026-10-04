package com.ai.android.provider

import android.hardware.Camera
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 音视频通话引擎（v1.0.0-beta4 新增）。
 *
 * 参考智谱 GLM-4-Voice（OpenAI 兼容 input_audio 块，单轮语音对话）
 * 与 GLM-Realtime（WebSocket 双向流式，ServerVAD + 可选视频帧）。
 * 仅用于通话界面，不影响既有聊天协议。
 */

/* ===================== 音频：录音 ===================== */

/** 16kHz 单声道 16bit PCM 录音器 */
class PcmRecorder(private val sampleRate: Int = 16000) {
    private var rec: AudioRecord? = null
    private val buf = ByteArray(3200)
    private val buffer = java.io.ByteArrayOutputStream()

    fun start() {
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)
        rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, min
        )
        rec?.startRecording()
    }

    /** 把当前可用的 PCM 累积进内部 buffer（语音模式用） */
    fun drain() {
        rec?.let { r ->
            var n: Int
            while (true) {
                n = r.read(buf, 0, buf.size)
                if (n <= 0) break
                buffer.write(buf, 0, n)
            }
        }
    }

    /** 读一段（实时模式每 ~100ms 调用一次） */
    fun readChunk(): ByteArray {
        val tmp = java.io.ByteArrayOutputStream()
        rec?.let { r ->
            var got = 0
            while (got < 3200) {
                val want = minOf(buf.size, 3200 - got)
                val n = r.read(buf, 0, want)
                if (n <= 0) break
                tmp.write(buf, 0, n); got += n
            }
        }
        return tmp.toByteArray()
    }

    fun stopAndReset(): ByteArray {
        runCatching { rec?.stop(); rec?.release() }
        rec = null
        val out = buffer.toByteArray()
        buffer.reset()
        return out
    }
}

/* ===================== 音频：WAV / 播放 ===================== */

/** 16bit 单声道 PCM 包成 WAV（默认 16kHz） */
fun pcm16ToWav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
    val dataLen = pcm.size
    val out = java.io.ByteArrayOutputStream()
    out.write("RIFF".toByteArray())
    out.writeIntLe(36 + dataLen)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray())
    out.writeIntLe(16)
    out.writeShortLe(1)
    out.writeShortLe(1)
    out.writeIntLe(sampleRate)
    out.writeIntLe(sampleRate * 2)
    out.writeShortLe(2)
    out.writeShortLe(16)
    out.write("data".toByteArray())
    out.writeIntLe(dataLen)
    out.write(pcm)
    return out.toByteArray()
}

private fun java.io.ByteArrayOutputStream.writeIntLe(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
}

private fun java.io.ByteArrayOutputStream.writeShortLe(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF)
}

/** 一次性播放 16bit 单声道 PCM（应在 IO 线程调用，会阻塞到播完） */
fun playPcm(pcm: ByteArray, sampleRate: Int) {
    if (pcm.isEmpty()) return
    val min = AudioTrack.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(1024)
    val track = AudioTrack(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        min, AudioTrack.MODE_STREAM, 0
    )
    track.play()
    var off = 0
    while (off < pcm.size) off += track.write(pcm, off, pcm.size - off)
    runCatching { track.stop(); track.release() }
}

/** 流式播放（Realtime 用） */
class PcmPlayer(private val sampleRate: Int) {
    private val track = AudioTrack(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024),
        AudioTrack.MODE_STREAM, 0
    )
    fun start() { track.play() }
    fun write(pcm: ByteArray) { var off = 0; while (off < pcm.size) off += track.write(pcm, off, pcm.size - off) }
    fun stop() { runCatching { track.stop() }; runCatching { track.release() } }
}

/* ===================== 摄像头 JPEG 帧（视频通话用） ===================== */

/** 用 Camera1 周期性 takePicture 采集 JPEG 帧（~0.6fps），不显示预览 */
class CameraFrames(private val onFrame: (ByteArray) -> Unit) {
    private var cam: Camera? = null
    private var busy = false
    private val handler = Handler(Looper.getMainLooper())
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        runCatching {
            val c = Camera.open()
            cam = c
            c.parameters.run {
                val sizes = supportedPreviewSizes
                if (sizes != null && sizes.isNotEmpty()) {
                    val target = sizes.minByOrNull { abs(it.width - 480) + abs(it.height - 360) } ?: sizes[0]
                    setPreviewSize(target.width, target.height)
                    c.parameters = this
                }
            }
            c.startPreview()
            running.set(true)
            handler.post { loop() }
        }
    }

    private fun loop() {
        if (!running.get()) return
        val c = cam ?: return
        if (!busy) {
            busy = true
            runCatching {
                c.takePicture(null, null, Camera.PictureCallback { data, _ ->
                    busy = false
                    runCatching { c.startPreview() }
                    data?.let { onFrame(it) }
                    if (running.get()) handler.postDelayed({ loop() }, 1500)
                })
            }
        } else {
            handler.postDelayed({ loop() }, 500)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { cam?.stopPreview(); cam?.release() }
        cam = null
    }
}

/* ===================== GLM-4-Voice（单轮语音） ===================== */

/** 智谱 GLM-4-Voice，OpenAI 兼容 chat/completions，input_audio 块 */
class VoiceCallClient(
    private val apiKey: String,
    private val model: String = "glm-4-voice",
    private val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

        /** 一轮语音对话（阻塞调用，请在后台线程使用），返回 (文字, 音频PCM base64) */
    fun round(wavBase64: String, text: String, history: List<String> = emptyList()): Pair<String, String> {
            val msgs = buildJsonArray {
                history.takeLast(6).forEach { h ->
                    add(buildJsonObject { put("role", "user"); put("content", h) })
                    add(buildJsonObject { put("role", "assistant"); put("content", "（历史回答，略）") })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                        add(buildJsonObject {
                            put("type", "input_audio")
                            put("input_audio", buildJsonObject {
                                put("data", wavBase64)
                                put("format", "wav")
                            })
                        })
                    })
                })
            }
            val body = buildJsonObject {
                put("model", model)
                put("messages", msgs)
                put("stream", false)
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
                        return client.newCall(req).execute().use { resp ->
                val t = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${t.take(300)}")
                val root = json.parseToJsonElement(t).jsonObject
                val msg = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                val outText = msg?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                val audio = msg?.get("audio")?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull.orEmpty()
                outText to audio
            }
        }
}

/* ===================== GLM-Realtime（WebSocket 流式） ===================== */

/** 智谱 GLM-Realtime，OpenAI Realtime 兼容协议 */
class RealtimeSession(
    private val apiKey: String,
    private val model: String = "glm-realtime-flash",
    private val video: Boolean = false,
    private val onAudioDelta: (ByteArray) -> Unit = {},
    private val onTranscript: (String, Boolean) -> Unit = { _, _ -> },
    private val onState: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onOpen: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MINUTES).build()
    private var ws: WebSocket? = null
    private val base = "wss://open.bigmodel.cn/api/paas/v4/realtime"

    fun connect() {
        val req = Request.Builder().url(base).header("Authorization", "Bearer $apiKey").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendSessionUpdate(webSocket)
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError("实时连接失败: ${t.message}")
            }
        })
    }

    private fun sendSessionUpdate(ws: WebSocket) {
        val session = buildJsonObject {
            put("model", model)
            put("modalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("text")); add(kotlinx.serialization.json.JsonPrimitive("audio")) })
            put("voice", "tongtong")
            put("input_audio_format", "pcm16")
            put("output_audio_format", "pcm")
            put("chat_mode", if (video) "video_passive" else "audio")
            put("turn_detection", buildJsonObject {
                put("type", "server_vad")
                put("create_response", true)
                put("interrupt_response", true)
            })
            put("instructions", "你是一个运行在安卓手机上的中文语音助手，请用简洁自然的中文和我对话。")
        }
        ws.send(buildJsonObject {
            put("type", "session.update")
            put("session", session)
        }.toString())
    }

    private fun handle(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "response.audio.delta" -> {
                val b64 = obj["delta"]?.jsonPrimitive?.contentOrNull ?: return
                runCatching { onAudioDelta(Base64.decode(b64, Base64.DEFAULT)) }
            }
            "response.audio_transcript.delta" ->
                onTranscript(obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(), false)
            "response.audio_transcript.done" ->
                onTranscript(obj["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty(), true)
            "response.done" -> onState("已响应")
            "input_audio_buffer.speech_started" -> onState("聆听中…")
            "input_audio_buffer.speech_stopped" -> onState("思考中…")
            "error" -> {
                val m = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: obj["error"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
                onError(m)
            }
        }
    }

    fun appendAudio(pcmB64: String) {
        ws?.send(buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", pcmB64)
        }.toString())
    }

    fun appendVideoFrame(jpegB64: String) {
        if (!video) return
        ws?.send(buildJsonObject {
            put("type", "input_audio_buffer.append_video_frame")
            put("video_frame", jpegB64)
        }.toString())
    }

    fun close() { runCatching { ws?.close(1000, "bye") }; ws = null }
}
