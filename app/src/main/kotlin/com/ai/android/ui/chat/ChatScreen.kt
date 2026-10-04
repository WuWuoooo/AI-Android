package com.ai.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.model.ChatMessage
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

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { val p = copyUriToCache(context, it); if (p != null) vm.attachFile(p) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { val b = uriToBase64(context, it); if (b != null) vm.attachImage(b) }
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                TopAppBar(
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
                        // ⭐ 终端按钮（加号左边，最左侧）：点击进入 Termux 风格终端，与 AI 共享、绑定当前对话
                        IconButton(onClick = onOpenTerminal) { Icon(Icons.Default.Terminal, "终端") }
                        IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Default.Add, "新会话") }
                        IconButton(onClick = onOpenConversations) { Icon(Icons.AutoMirrored.Filled.List, "对话列表") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置") }
                    },
                )
            }
        },
                bottomBar = {
            Column(Modifier.fillMaxWidth().imePadding().windowInsetsPadding(WindowInsets.navigationBars)) {
                // ⭐ 多选模式：显示批量删除操作栏（替代输入框）
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
                    onSend = { val t = input; input = ""; vm.send(t) },
                    onStop = { vm.stop() },
                    isRunning = agentState.isActive,
                    stateText = agentState.progressText,
                    onPickFile = { filePicker.launch("*/*") },
                    onPickImage = { imagePicker.launch("image/*") },
                    attachedFiles = attachedFiles,
                    attachedImages = attachedImages,
                    onRemoveAttachment = { vm.removeAttachment(it) },
                    onRemoveImage = { vm.removeImage(it) },
                                        quotedContent = quoted?.content.orEmpty(),
                    onClearQuote = { vm.clearQuote() },
                                        onStartVoiceCall = { onOpenCall(false) },
                    onStartVideoCall = { onOpenCall(true) },
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
                            // ⭐ 只有最后一条 assistant 且生成未完整结束时，才允许"继续生成"
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
                                // ⭐ 朗读 toggle：正在朗读同一消息 → 停止；否则重新朗读
                                runCatching {
                                    if (speakingId == m.id) {
                                        tts?.stop()
                                        speakingId = null
                                    } else {
                                        tts?.speak(m.content.take(2000), TextToSpeech.QUEUE_FLUSH, null, "ai_speak")
                                        speakingId = m.id
                                    }
                                }.onFailure { Toast.makeText(context, "TTS 未就绪", Toast.LENGTH_SHORT).show() }
                            },
                            // ⭐ 用户消息长按菜单 + 多选删除
                            displayContentOverride = vm.draftEdit(m.id),
                            selectionMode = selectionMode,
                            isSelected = selectedIds.contains(m.id),
                            onToggleSelect = {
                                if (selectedIds.contains(m.id)) selectedIds = selectedIds - m.id
                                else selectedIds = selectedIds + m.id
                            },
                            onEditUser = { newText -> vm.editUserMessage(m.id, newText) },
                            onDeleteMessage = {
                                vm.deleteMessage(m.id)
                                selectedIds = selectedIds - m.id
                            },
                            onEnterMultiSelect = { enterMultiSelect() },
                        )
                    }
                }
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

@Composable
private fun AskUserDialog(q: MainViewModel.PendingQuestion, onAnswer: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Agent 提问") },
        text = {
            Column {
                Text(q.question, fontSize = 14.sp)
                if (q.options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    q.options.forEach { opt ->
                        TextButton(onClick = { onAnswer(opt) }, modifier = Modifier.fillMaxWidth()) {
                            Text(opt, fontSize = 13.sp)
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