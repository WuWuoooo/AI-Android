package com.ai.android.ui.call

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.provider.CameraFrames
import com.ai.android.provider.PcmPlayer
import com.ai.android.provider.PcmRecorder
import com.ai.android.provider.ProviderConfig
import com.ai.android.provider.RealtimeSession
import com.ai.android.provider.VoiceCallClient
import com.ai.android.provider.pcm16ToWav
import com.ai.android.provider.playPcm
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 通话用的 Provider 选择结果 */
private data class CallCfg(val apiKey: String, val base: String, val voiceModel: String, val realtimeModel: String)

/** 从设置里挑一个可用作通话的 Provider（优先智谱/大模型，其次有 key 的） */
private fun pickCallCfg(vm: MainViewModel): CallCfg? {
    val settings = vm.settings
    val configs = settings.loadConfigs()
    val bound = settings.multimodalBinding("CALL")
    val isZhipu = { c: ProviderConfig ->
        c.baseUrl.contains("bigmodel", true) || c.id == "zhipu" || c.label.contains("智谱") || c.model.contains("glm", true)
    }
        val chosen =
        (if (bound.isNotBlank()) configs.firstOrNull { it.id == bound } else null)
            ?: configs.firstOrNull { isZhipu(it) && it.apiKey.isNotBlank() }
            ?: configs.firstOrNull { it.apiKey.isNotBlank() }
            ?: return null
    val c = chosen
    val isGlm = isZhipu(c)
    val base = if (isGlm) "https://open.bigmodel.cn/api/paas/v4" else c.baseUrl.trimEnd('/')
    val voiceModel = if (isGlm) "glm-4-voice" else c.model
    val realtimeModel = if (isGlm) "glm-realtime-flash" else c.model
    return CallCfg(c.apiKey, base, voiceModel, realtimeModel)
}

/** 通话控制器：封装录音/播放/实时 WS/摄像头帧 */
private class CallController(
    private val video: Boolean,
    private val cfg: CallCfg,
) {
    private val recorder = PcmRecorder(16000)
    private var player: PcmPlayer? = null
    private var rt: RealtimeSession? = null
    private var cam: CameraFrames? = null
    private var recThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val voiceRec = AtomicBoolean(false)

    var onStatus: (String) -> Unit = {}
    var onTranscript: (String) -> Unit = {}
    var onErr: (String) -> Unit = {}

    fun startVideo() {
        running.set(true)
        player = PcmPlayer(24000).also { it.start() }
        rt = RealtimeSession(
            apiKey = cfg.apiKey,
            model = cfg.realtimeModel,
            video = true,
            onAudioDelta = { player?.write(it) },
            onTranscript = { s, done -> if (done) onStatus("AI：") else onTranscript(s) },
            onState = { st -> onStatus(st) },
            onError = { onErr(it) },
            onOpen = { onStatus("已接通，请说话") },
        )
        rt?.connect()
        recorder.start()
        cam = CameraFrames { frame -> rt?.appendVideoFrame(Base64.encodeToString(frame, Base64.NO_WRAP)) }
        cam?.start()
        recThread = thread(isDaemon = true) {
            while (running.get()) {
                val chunk = recorder.readChunk()
                if (chunk.isNotEmpty()) rt?.appendAudio(Base64.encodeToString(chunk, Base64.NO_WRAP))
                Thread.sleep(100)
            }
        }
    }

    /** 语音模式：开始录音 */
    fun startVoiceRecord() {
        if (voiceRec.get()) return
        voiceRec.set(true)
        recorder.start()
        recThread = thread(isDaemon = true) {
            while (voiceRec.get()) {
                recorder.drain()
                Thread.sleep(120)
            }
        }
    }

    /** 语音模式：停止并发送 */
    fun sendVoice(text: String, history: MutableList<String>) {
        voiceRec.set(false)
        val pcm = recorder.stopAndReset()
        recThread = null
        thread(isDaemon = true) {
            runCatching {
                val b64 = Base64.encodeToString(pcm16ToWav(pcm, 16000), Base64.NO_WRAP)
                val (t, audio) = VoiceCallClient(cfg.apiKey, cfg.voiceModel, cfg.base).round(b64, text, history)
                onStatus("AI：$t")
                if (audio.isNotBlank()) playPcm(Base64.decode(audio, Base64.NO_WRAP), 44100)
                if (t.isNotBlank()) history.add(t)
                recorder.start()
            }.onFailure {
                onErr("通话失败：${it.message}")
                recorder.start()
            }
            voiceRec.set(false)
        }
    }

    fun release() {
        running.set(false)
        voiceRec.set(false)
        recThread?.interrupt()
        rt?.close(); cam?.stop(); player?.stop()
        runCatching { recorder.stopAndReset() }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(vm: MainViewModel, videoMode: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val cfg = remember { pickCallCfg(vm) }
    val controller = remember { CallController(videoMode, cfg ?: CallCfg("", "", "", "")) }

    var status by remember { mutableStateOf(if (cfg == null) "未找到可用的 API Key，请在设置里配置智谱/OpenAI Provider" else "准备中") }
    var transcript by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val history = remember { mutableListOf<String>() }

    // 绑定控制器回调
    remember {
        controller.onStatus = { status = it }
        controller.onTranscript = { s -> transcript += s }
        controller.onErr = { e -> err = e }
        controller
    }

    // 运行时权限
    LaunchedEffect(Unit) {
        if (cfg != null) {
            val need = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (videoMode) add(Manifest.permission.CAMERA)
            }
            val missing = need.filter {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) activity?.requestPermissions(missing.toTypedArray(), 1001)
        }
    }

    DisposableEffect(Unit) { onDispose { controller.release() } }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                TopAppBar(
                    title = { Text(if (videoMode) "视频通话" else "语音通话", fontSize = 17.sp) },
                    navigationIcon = {
                                                IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                )
            }
        },
    ) { padding ->
                Column(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (cfg == null) {
                Spacer(Modifier.height(40.dp))
                Text(status, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp))
                Text(
                    "请回到设置 → 模型服务，添加智谱（baseUrl 含 bigmodel）并填 API Key，再回来。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                Spacer(Modifier.height(24.dp))

                if (videoMode) {
                    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                        Text("📹", fontSize = 48.sp)
                    }
                    Text("视频通话中（约 0.6 帧/秒发送画面）", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                                        Box(Modifier.size(96.dp).clip(CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text(if (recording) "🎙" else "💬", fontSize = 40.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(status, fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                if (transcript.isNotBlank()) {
                    Text(transcript.takeLast(400), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp))
                }
                if (err.isNotBlank()) {
                    Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp))
                }

                                Spacer(Modifier.height(16.dp))

                // 底部大按钮
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    FilledIconButton(
                        onClick = {
                            if (videoMode) {
                                if (!active) {
                                    transcript = ""; err = ""
                                    controller.startVideo(); active = true
                                } else {
                                    controller.release(); active = false; status = "已挂断"
                                }
                            } else {
                                if (!recording) {
                                    controller.startVoiceRecord(); recording = true; status = "录音中，再点一次发送"
                                } else {
                                    controller.sendVoice("", history)
                                    recording = false; status = "发送中…"
                                }
                            }
                        },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Text(
                            if (videoMode) (if (active) "挂断" else "接通") else (if (recording) "发送" else "开始"),
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
