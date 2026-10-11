package com.ai.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.ai.android.MainViewModel
import com.ai.android.model.ChatMessage
import com.ai.android.ui.components.AppMenuPanel
import com.ai.android.ui.components.AppMenuItem
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenCall: (videoMode: Boolean) -> Unit = { },
    onOpenTerminal: () -> Unit = { },
) {
    val context = LocalContext.current
    val current by vm.current.collectAsState()
    val version by vm.version.collectAsState()
    val agentState by vm.agentState.collectAsState()
    val pending by vm.pendingQuestion.collectAsState()
    val attachedFiles by vm.attachedFiles.collectAsState()
    val attachedImages by vm.attachedImages.collectAsState()
    val quoted by vm.quotedMessage.collectAsState()
    val lastIncomplete by vm.lastGenerationIncomplete.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

        // ⭐ 朗读 toggle：记录正在朗读的消息 id；再次点击同一条 → 停止
    var speakingId by remember { mutableStateOf<String?>(null) }

    // ⭐ 第五轮 Bug 2：MiMo TTS 主路播放（MediaPlayer 读临时 wav）；系统 TTS 仅作未配置时的兜底
    var mediaTtsPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // ⭐ 多选删除模式（用户长按菜单"多选"进入；选中后可批量删除 AI / 用户消息）
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun enterMultiSelect() {
        selectionMode = true
        selectedIds = emptySet()
    }
    fun exitMultiSelect() {
        selectionMode = false
        selectedIds = emptySet()
    }

    // ⭐ v1.2.0 #5：对话历史侧边抽屉
    var showConvDrawer by remember { mutableStateOf(false) }

    // ⭐ v1.2.0 #6：自定义文件选择器（底部抽屉，替代系统 SAF）
    var showFilePick by remember { mutableStateOf(false) }

    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) engine?.language = Locale.CHINA
        }
        engine
    }
    DisposableEffect(Unit) { onDispose { tts?.stop(); tts?.shutdown() } }

        // ⭐ 切换对话时退出多选（草稿由 vm.switchConversation 清除）
    LaunchedEffect(current?.id) {
        selectionMode = false
        selectedIds = emptySet()
        speakingId = null
        // ⭐ #6：崩溃后恢复——切换/进入对话时从持久化草稿回填输入框
        input = current?.id?.let { vm.settings.inputDraft(it) } ?: ""
    }

    // ⭐ #6：输入框草稿持久化（防抖 400ms，扛进程崩溃）；当前对话 id 变化时不串
    LaunchedEffect(input) {
        val convId = current?.id ?: return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        runCatching { vm.settings.setInputDraft(convId, input) }
    }

    val messages = current?.messages?.toList() ?: emptyList()
    val toolResults = remember(version, messages.size) {
        messages.flatMap { it.toolResults }.associateBy { it.toolCallId }
    }
    val lastAssistantId = remember(messages) {
        messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id
    }

    LaunchedEffect(version, messages.size) {
        if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
    }

    // ⭐ v1.2.0 #3：语音输入控制器（录音 → ASR → 立即发送；没配 ASR 回退系统识别）
    val asrManager = remember { vm.providerManager.multimodal }
        val voiceController = remember(asrManager) {
        VoiceInputController(context, asrManager)
    }

        // ⭐ v1.2.0-next：当前模型名（显示在输入框下方，点击可切换，参考图二）
    val activeProvider = vm.providerManager.active
    val modelLabel = activeProvider?.let { "${it.name} ${it.model}" } ?: ""
    var showModelPicker by remember { mutableStateOf(false) }

            // ⭐ v1.2.0-next #14：自绘图片选择器（替代系统 GetContent SAF）
    var showImagePick by remember { mutableStateOf(false) }

    // ⭐ v1.2.0-next #10：加号菜单（自绘 AppMenuPanel 顶层渲染，替代 InputBar 内原生 DropdownMenu）
        var showAddMenu by remember { mutableStateOf(false) }

    // ⭐ 第五轮 Bug 2：TTS 协程作用域（MiMo 网络请求）
    val ttsScope = rememberCoroutineScope()

    // ⭐ 顶层 Box：抽屉画在 Scaffold **外面**，铺满全高，盖住 topBar + bottomBar
    Box(Modifier.fillMaxSize()) {
        // ==================== 底层：主界面（Scaffold）====================
        Scaffold(
            topBar = {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                    TopAppBar(
                                                navigationIcon = {
                            // ⭐ 汉堡按钮 → 打开对话历史抽屉；#1：同时收起软键盘
                                                        IconButton(onClick = {
                                runCatching {
                                    (context as? android.app.Activity)
                                        ?.window
                                        ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                                }
                                showConvDrawer = true
                            }) {
                                Icon(Icons.Default.Menu, "对话历史")
                            }
                        },
                        title = {
                            Column {
                                Text(current?.title ?: "AI Android",
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    vm.providerManager.active?.let { "${it.name} · ${it.model}" } ?: "未配置模型",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = onOpenTerminal) { Icon(Icons.Default.Terminal, "终端") }
                            IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Default.Add, "新会话") }
                            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置") }
                        },
                    )
                }
            },
                                                bottomBar = {
                // ⭐ #9 + #4：导航栏（bottomBar）背景统一到 InputBar 的 Surface 色；
                // background 放最外层（在 windowInsetsPadding 之后）才能盖住系统导航栏 insets 区
                Column(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    if (selectionMode) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraLarge)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已选 ${selectedIds.size} 条", fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    vm.deleteMessages(selectedIds.toSet())
                                    Toast.makeText(context, "已删除 ${selectedIds.size} 条", Toast.LENGTH_SHORT).show()
                                }
                                exitMultiSelect()
                            }) { Text("删除", fontSize = 14.sp) }
                            TextButton(onClick = { exitMultiSelect() }) { Text("取消", fontSize = 14.sp) }
                        }
                    } else {
                        InputBar(
                            value = input,
                            onValueChange = { input = it },
                            onSend = { t -> input = ""; vm.send(t) },
                            onStop = { vm.stop() },
                            isRunning = agentState.isActive,
                            stateText = agentState.progressText,
                                                        // ⭐ v1.2.0-next #12：文件 / 图片抽屉已弹出时再点加号 → 幂等收起（toggle，不叠加）
                            onPickFile = { showFilePick = !showFilePick },
                            // ⭐ v1.2.0-next #14：图片选择改自绘抽屉（替代系统 GetContent SAF）
                            onPickImage = { showImagePick = !showImagePick },
                            attachedFiles = attachedFiles,
                            attachedImages = attachedImages,
                            onRemoveAttachment = { vm.removeAttachment(it) },
                            onRemoveImage = { vm.removeImage(it) },
                            quotedContent = quoted?.content.orEmpty(),
                            onClearQuote = { vm.clearQuote() },
                            onStartVoiceCall = { onOpenCall(false) },
                            onStartVideoCall = { onOpenCall(true) },
                                                        voiceController = voiceController,
                            onVoiceAutoSend = { text -> vm.send(text) },
                                                                                    modelLabel = modelLabel,
                            onSwitchModel = { showModelPicker = true },
                            onToggleAddMenu = { showAddMenu = !showAddMenu },
                            modelMenuOpen = showModelPicker,
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (messages.isEmpty()) {
                    EmptyHint(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { _, m ->
                            MessageBubble(
                                message = m,
                                toolResults = toolResults,
                                isLastAssistant = m.id == lastAssistantId,
                                showContinue = m.id == lastAssistantId && lastIncomplete,
                                onRetry = { vm.retryLast() },
                                onContinue = { vm.continueGeneration() },
                                onCopy = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("ai", m.content))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                                onQuote = {
                                    vm.quoteMessage(m)
                                    Toast.makeText(context, "已引用", Toast.LENGTH_SHORT).show()
                                },
                                                                onSpeak = {
                                    // ⭐ 第五轮 Bug 2：优先走 MiMo TTS（配了 TTS Provider 时）；未配置才回退系统 TextToSpeech
                                    val text = m.content.take(2000)
                                    if (speakingId == m.id) {
                                        // 正在朗读这条 → 停止
                                                                                mediaTtsPlayer?.let { runCatching { it.stop(); it.release() } }
                                        mediaTtsPlayer = null
                                        runCatching { tts?.stop() }
                                        speakingId = null
                                    } else {
                                        ttsScope.launch {
                                            if (vm.providerManager.multimodal.hasTts) {
                                                // 主路：MiMo TTS
                                                runCatching {
                                                    val audio = vm.providerManager.multimodal.tts(text)
                                                    if (audio.isEmpty()) {
                                                        Toast.makeText(context, "TTS 未返回音频", Toast.LENGTH_SHORT).show()
                                                        return@runCatching
                                                    }
                                                    val wavFile = File(context.cacheDir, "tts_${m.id}.wav")
                                                    wavFile.writeBytes(audio)
                                                    // 释放旧播放器
                                                    mediaTtsPlayer?.let { runCatching { it.stop(); it.release() } }
                                                    mediaTtsPlayer = MediaPlayer().apply {
                                                        setDataSource(wavFile.absolutePath)
                                                        prepare()
                                                        setOnCompletionListener {
                                                            release()
                                                            mediaTtsPlayer = null
                                                            speakingId = null
                                                        }
                                                        start()
                                                    }
                                                    speakingId = m.id
                                                    Toast.makeText(context, "MiMo TTS 朗读中…（再点一次停止）", Toast.LENGTH_SHORT).show()
                                                }.onFailure {
                                                    Toast.makeText(context, "MiMo TTS 失败，回退系统 TTS", Toast.LENGTH_SHORT).show()
                                                    // 回退系统 TTS
                                                    runCatching {
                                                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_speak")
                                                        speakingId = m.id
                                                    }
                                                }
                                            } else {
                                                // 未配置 TTS Provider → 系统 TTS 兜底
                                                runCatching {
                                                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_speak")
                                                    speakingId = m.id
                                                }.onFailure {
                                                    Toast.makeText(context, "TTS 未就绪", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                displayContentOverride = vm.draftEdit(m.id),
                                selectionMode = selectionMode,
                                isSelected = selectedIds.contains(m.id),
                                onToggleSelect = {
                                    if (selectedIds.contains(m.id)) selectedIds = selectedIds - m.id
                                    else selectedIds = selectedIds + m.id
                                },
                                onEditUser = { newText -> vm.editUserMessage(m.id, newText) },
                                onResendUser = { newText -> vm.resendUserMessage(m.id, newText) },
                                onDeleteMessage = {
                                    vm.deleteMessage(m.id)
                                    selectedIds = selectedIds - m.id
                                },
                                onEnterMultiSelect = { enterMultiSelect() },
                            )
                        }
                    }
                }

                                // ⭐ 文件选择器底部抽屉（从底部向上滑入；v1.2.0-hotfix #1 过渡动画）
                                                AnimatedVisibility(
                    visible = showFilePick,
                    enter = slideInVertically(animationSpec = tween(300), initialOffsetY = { it }) +
                        fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(animationSpec = tween(300), targetOffsetY = { it }) +
                        fadeOut(animationSpec = tween(200)),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable { showFilePick = false },
                        )
                        FilePickDrawer(
                            onDismiss = { showFilePick = false },
                            onConfirm = { paths -> paths.forEach { vm.attachFile(it) } },
                        )
                    }
                                }

                // ⭐ v1.2.0-next #14：自绘图片选择器底部抽屉（与文件抽屉同样式，从底部滑入）
                AnimatedVisibility(
                    visible = showImagePick,
                    enter = slideInVertically(animationSpec = tween(300), initialOffsetY = { it }) +
                        fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(animationSpec = tween(300), targetOffsetY = { it }) +
                        fadeOut(animationSpec = tween(200)),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable { showImagePick = false },
                        )
                        ImagePickDrawer(
                            onDismiss = { showImagePick = false },
                            onConfirm = { b64List -> b64List.forEach { vm.attachImage(it) } },
                        )
                    }
                }
            }
        }

                // ==================== 顶层：对话历史抽屉（铺满全高，盖住 topBar + bottomBar）====================
        // ⭐ v1.2.0-hotfix #1：左侧滑入/滑出过渡
                        AnimatedVisibility(
            visible = showConvDrawer,
            enter = slideInHorizontally(animationSpec = tween(300), initialOffsetX = { -it }) +
                fadeIn(animationSpec = tween(200)),
            exit = slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { -it }) +
                fadeOut(animationSpec = tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))   // 遮罩盖住整个主界面
                    .clickable { showConvDrawer = false },   // 点遮罩关闭
            ) {
                // 左侧面板（宽度 280dp ≈ 屏宽 70%，铺满全高含状态栏，盖住 topBar + bottomBar）
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(280.dp)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    ConversationDrawer(
                        vm = vm,
                        currentId = current?.id,
                        onClose = { showConvDrawer = false },
                    )
                }
            }
        }
    }

                            // ⭐ v1.2.0-next #2 + #10：模型切换就近弹菜单（锚定到右下角，靠近模型名行，不再居中 AlertDialog）
    // ⭐ #2：外层 imePadding —— 键盘弹出顶起输入框时，菜单随 bottomBar 一起上移，不再留在原位
    // ⭐ #3 + 第五轮 #2：外层 AnimatedVisibility(visible=showModelPicker) → 遮罩+菜单整体淡入淡出（有 exit 过渡，不再生硬消失）
    //    内层 AnimatedVisibility(visible=true, enter=slideInVertically) → 菜单从下方滑入
    AnimatedVisibility(
        visible = showModelPicker,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        val allConfigs = vm.settings.loadConfigs()
        val curActive = vm.settings.activeId()
        Box(Modifier.fillMaxSize().imePadding().zIndex(10f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable { showModelPicker = false },
            )
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(animationSpec = tween(200), initialOffsetY = { it / 8 }) +
                    fadeIn(animationSpec = tween(150)),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                AppMenuPanel(
                    items = allConfigs.map { cfg ->
                        AppMenuItem(
                            label = cfg.label,
                            sub = cfg.model,
                            active = cfg.id == curActive,
                        )
                    },
                    onPick = { idx ->
                        val cfg = allConfigs[idx]
                        vm.settings.setActive(cfg.id)
                        showModelPicker = false
                        Toast.makeText(context, "已切换到 ${cfg.label}", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(bottom = 80.dp, end = 12.dp, start = 12.dp),
                    title = "切换模型",
                    emptyText = "还没有配置模型，请到 设置 → 模型与能力 添加",
                )
            }
        }
    }

    // ⭐ 加号菜单（自绘 AppMenuPanel，锚到左下角加号附近）
    // ⭐ 第五轮 #2：外层 AnimatedVisibility → 有进出过渡，不再生硬
    AnimatedVisibility(
        visible = showAddMenu,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        Box(Modifier.fillMaxSize().imePadding().zIndex(11f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable { showAddMenu = false },
            )
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(animationSpec = tween(200), initialOffsetY = { it / 8 }) +
                    fadeIn(animationSpec = tween(150)),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                AppMenuPanel(
                    items = listOf(
                        AppMenuItem(label = "选择文件"),
                        AppMenuItem(label = "选择图片"),
                        AppMenuItem(label = "语音通话（GLM-4-Voice）"),
                        AppMenuItem(label = "视频通话（GLM-Realtime）"),
                    ),
                    onPick = { idx ->
                        showAddMenu = false
                        when (idx) {
                            0 -> { showFilePick = !showFilePick }
                            1 -> { showImagePick = !showImagePick }
                            2 -> onOpenCall(false)
                            3 -> onOpenCall(true)
                        }
                    },
                    modifier = Modifier.padding(bottom = 80.dp, start = 12.dp),
                    title = "添加附件",
                )
            }
        }
    }

    pending?.let { q -> AskUserDialog(q = q, onAnswer = { vm.answerQuestion(it) }) }
}

private fun copyUriToCache(context: Context, uri: Uri): String? = runCatching {
    val f = File(context.cacheDir, "attach_${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(f).use { output -> input.copyTo(output) }
    }
    f.absolutePath
}.getOrNull()

private fun uriToBase64(context: Context, uri: Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}.getOrNull()

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(modifier.padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("AI Android", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("运行在手机上的 AI Agent", fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text("可以帮你：整理文件 · 执行终端命令 · 联网搜索 · 定时任务 · 操控其他 App",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, lineHeight = 18.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskUserDialog(q: MainViewModel.PendingQuestion, onAnswer: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var optionsExpanded by remember { mutableStateOf(false) }
    val visibleOptions = if (q.options.size > 6 && !optionsExpanded) q.options.take(6) else q.options
    val hasMore = q.options.size > 6

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Agent 提问") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(q.question, fontSize = 14.sp)
                if (q.options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    visibleOptions.forEach { opt ->
                        TextButton(onClick = { onAnswer(opt) }, modifier = Modifier.fillMaxWidth()) {
                            Text(opt, fontSize = 13.sp)
                        }
                    }
                    if (hasMore && !optionsExpanded) {
                        TextButton(
                            onClick = { optionsExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("展开更多选项（${q.options.size}）…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("或输入回答…", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(text) }, enabled = text.isNotBlank()) { Text("回复") } },
        dismissButton = { TextButton(onClick = { onAnswer("") }) { Text("跳过") } },
    )
}
