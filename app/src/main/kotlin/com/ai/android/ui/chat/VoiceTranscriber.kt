package com.ai.android.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import com.ai.android.provider.MultimodalManager
import com.ai.android.provider.PcmRecorder
import com.ai.android.provider.pcm16ToWav
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * ⭐ v1.2.0 #3：单段「按住说话 → 语音转文字自动发送」控制器。
 *
 * 交互（见 InputBar）：**按住说话** —— 按下麦克风按钮开始录音/识别，
 * 手指抬起即停止并转写，结果立即自动发送。
 *
 * 转写引擎（主人要求 A+C 都实现）：
 *  1. **优先**：已配置的 ASR Provider（[MultimodalManager.asr]，OpenAI whisper / Qwen-ASR 等）
 *     —— 16kHz PCM 录音 → WAV → provider.speechToText（需要 RECORD_AUDIO 权限）
 *  2. **回退**：Android 自带 [SpeechRecognizer]（不依赖 Provider；部分机型不支持）
 *
 * ⭐ v1.2.0-hotfix #1：状态机加固（修「长按无反应 / 闪一下就消失」）
 *  - [begin] 返回是否启动成功；失败时经 [onError] + Toast 提示（缺录音权限 / 系统识别器不可用 / 启动失败）
 *  - 录音短于 500ms 视为误触 → Toast「说话时间太短」，不转写（修「闪一下就消失」的静默路径）
 *  - 系统识别器 startListening 失败 / 没听到语音 → Toast 引导（重试或配置 ASR Provider）
 *  - LIVE 轮次结束后销毁旧 SpeechRecognizer，避免每轮新建泄漏
 *
 * 区别于 CallScreen 的实时通话：这里只做单段转写，结果自动发送，不发起对话。
 */
class VoiceInputController(
    private val context: Context,
    private val asrManager: MultimodalManager,
) {
    companion object {
        private const val TAG = "VoiceInputController"
        /** 最低录音时长（ms）：短于此时长视为误触，不转写只提示 */
        private const val MIN_RECORD_MS = 500L
    }

    enum class Phase { IDLE, RECORDING, LIVE, TRANSCRIBING }

    /** 当前阶段（供 InputBar 显示「录音中… / 转写中…」） */
    @Volatile var phase: Phase = Phase.IDLE
        private set

    /** 转写结果回调（自动发送）；无结果时回空串。主线程调用。 */
    @Volatile var onResult: (String) -> Unit = {}

    /** ⭐ hotfix：错误/提示回调（缺权限 / 识别器不可用 / 太短等）；控制器同时已弹 Toast。主线程调用。 */
    @Volatile var onError: (String) -> Unit = {}

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorder: PcmRecorder? = null
    private var recognizer: SpeechRecognizer? = null
    private var micGranted = false
    @Volatile private var recordStartMs = 0L

    init {
        micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 是否已有录音权限（没有则 [begin] 会提示并请求授权） */
    fun hasMicPermission(): Boolean = micGranted

    fun requestMicPermission(requestCode: Int = 1201) {
        (context as? Activity)?.requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO), requestCode
        )
    }

    /** ⭐ 动态检查录音权限（init 时的 [micGranted] 在请求授权后不会自动刷新，begin 前再查一次） */
    private fun refreshMicPermission() {
        micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 开始录音/识别（主线程调用，手指按下时）。
     * 返回 true = 已启动（phase → RECORDING/LIVE）；false = 失败（已经 [postError] 提示过，调用方无需再处理）。
     *
     * ⭐ hotfix：
     *  - 有 ASR Provider 但缺 RECORD_AUDIO → Toast 引导 + 弹系统授权框，不静默失败
     *  - 无 Provider 且系统识别器不可用 → Toast 引导去设置页配置 ASR Provider，不静默失败
     */
    fun begin(): Boolean {
        if (phase != Phase.IDLE) return false
        refreshMicPermission()
        if (asrManager.hasAsr) {
            // ⭐ 走 Provider 录音（16kHz PCM）才需要 App 的 RECORD_AUDIO 权限
            if (!micGranted) {
                postError("语音输入需要麦克风权限，请在弹出框中授权")
                requestMicPermission()
                return false
            }
                        val rec = PcmRecorder(16000)
            recorder = rec
            runCatching { rec.start() }
            if (recorder?.ready == true) {
                recordStartMs = System.currentTimeMillis()
                phase = Phase.RECORDING
                return true
            }
            recorder = null
            Log.e(TAG, "录音启动失败（AudioRecord 不可用或无权限）")
            postError("录音启动失败，请检查麦克风或重试")
            return false
        }
        // ⭐ 无 ASR Provider：回退系统 SpeechRecognizer（走系统语音服务，不依赖 App 录音权限）
        return startSystemRecognizer()
    }

    /**
     * 结束并转写（主线程调用，手指抬起时）。
     *  - RECORDING：短于 [MIN_RECORD_MS] → Toast「太短」不转写；否则停录音 → WAV → ASR Provider（Phase.TRANSCRIBING）
     *  - LIVE：系统识别器自带 VAD 会自动结束；这里仅强制 stop
     */
    fun finish() {
        when (phase) {
            Phase.RECORDING -> {
                val durationMs = System.currentTimeMillis() - recordStartMs
                if (durationMs < MIN_RECORD_MS) {
                    // ⭐ hotfix：按住不足 0.5s 视为误触，明确提示而不是静默消失
                    phase = Phase.IDLE
                    stopRecording()
                    postError("说话时间太短，请按住按钮稍久一点再说")
                    return
                }
                phase = Phase.TRANSCRIBING
                val wav = stopRecording()
                thread(isDaemon = true) {
                    val text = runBlocking {
                        runCatching { asrManager.asr(wav) }.getOrNull()?.trim().orEmpty()
                    }
                    if (text.isEmpty()) Log.w(TAG, "转写为空")
                    mainHandler.post {
                        phase = Phase.IDLE
                        onResult(text)
                    }
                }
            }
            Phase.LIVE -> {
                runCatching { recognizer?.stopListening() }
                phase = Phase.IDLE
            }
            else -> Unit
        }
    }

    /** 取消（不产生结果） */
    fun cancel() {
        when (phase) {
            Phase.RECORDING -> { stopRecording(); phase = Phase.IDLE }
            Phase.LIVE -> {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
                recognizer = null
                phase = Phase.IDLE
            }
            Phase.TRANSCRIBING -> { phase = Phase.IDLE }
            else -> Unit
        }
    }

    private fun stopRecording(): ByteArray {
        val pcm = runCatching { recorder?.stopAndReset() ?: ByteArray(0) }.getOrDefault(ByteArray(0))
        recorder = null
        // 包成 WAV（16kHz）交给 ASR Provider（whisper 等可直接吃 pcm，WAV 更稳）
        return pcm16ToWav(pcm, 16000)
    }

    /** ⭐ hotfix：统一错误提示（主线程）：回调 + Toast，绝不静默 */
    private fun postError(msg: String) {
        mainHandler.post {
            onError(msg)
            runCatching { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                .onFailure { Log.w(TAG, "Toast 失败", it) }
        }
    }

    /**
     * 启动系统 SpeechRecognizer 实时识别（无 ASR Provider 时回退路径）。
     * 返回 true = startListening 成功（phase → LIVE）；false = 不可用/失败（已经 [postError] 提示过）。
     *
     * ⭐ hotfix：入口先查 isRecognitionAvailable；startListening 失败时销毁识别器并提示。
     */
    private fun startSystemRecognizer(): Boolean {
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
            .getOrDefault(false)
        if (!available) {
            postError("本机不支持系统语音识别，请在设置中配置 ASR Provider 后使用语音输入")
            return false
        }
        // 清理上一轮可能残留的识别器（LIVE 结束不销毁的话，每轮新建会累积）
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null

        val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrElse {
            Log.e(TAG, "createSpeechRecognizer 失败", it)
            postError("系统语音识别器创建失败，请重试或配置 ASR Provider")
            return false
        }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (phase == Phase.LIVE) phase = Phase.IDLE
            }

            override fun onError(error: Int) {
                Log.w(TAG, "system recognizer error $error")
                if (phase == Phase.LIVE) {
                    phase = Phase.IDLE
                    mainHandler.post {
                        onResult("")
                        postError("没有识别到语音或识别失败，请重试或配置 ASR Provider")
                    }
                }
            }

            override fun onResults(bundle: Bundle?) {
                val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty().trim()
                phase = Phase.IDLE
                mainHandler.post { onResult(text) }
            }

            override fun onPartialResults(bundle: Bundle?) {
                runCatching {
                    val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = list?.firstOrNull().orEmpty()
                    if (partial.isNotEmpty()) mainHandler.post { onResult(partial) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        val started = runCatching {
            sr.startListening(intent)
            true
        }.getOrDefault(false)
        if (!started) {
            Log.e(TAG, "startListening 失败")
            runCatching { sr.destroy() }
            recognizer = null
            postError("系统语音识别启动失败，请重试或配置 ASR Provider")
            return false
        }
        phase = Phase.LIVE
        return true
    }
}
