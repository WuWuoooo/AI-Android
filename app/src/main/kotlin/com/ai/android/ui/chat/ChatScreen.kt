package com.ai.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel

/**
 * 主聊天界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    val current by vm.current.collectAsState()
    val version by vm.version.collectAsState()
    val agentState by vm.agentState.collectAsState()
    val pending by vm.pendingQuestion.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var showConvMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val messages = current?.messages?.toList() ?: emptyList()
    val toolResults = remember(version, messages.size) {
        messages.flatMap { it.toolResults }.associateBy { it.toolCallId }
    }

    // 新消息自动滚动到底部
    LaunchedEffect(version, messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = current?.title ?: "AI Android",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = vm.providerManager.active?.let { "${it.name} · ${it.model}" } ?: "未配置模型",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newConversation() }) {
                        Icon(Icons.Default.Add, contentDescription = "新会话")
                    }
                    Box {
                        IconButton(onClick = { showConvMenu = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "会话列表")
                        }
                        DropdownMenu(expanded = showConvMenu, onDismissRequest = { showConvMenu = false }) {
                            if (conversations.isEmpty()) {
                                DropdownMenuItem(text = { Text("暂无会话") }, onClick = { showConvMenu = false })
                            }
                            conversations.forEach { c ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${c.title}（${c.messages.size} 条）",
                                            maxLines = 1,
                                            fontSize = 13.sp,
                                        )
                                    },
                                    onClick = {
                                        vm.switchConversation(c.id)
                                        showConvMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            InputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    val t = input
                    input = ""
                    vm.send(t)
                },
                onStop = { vm.stop() },
                isRunning = agentState.isActive,
                stateText = agentState.progressText,
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                EmptyHint(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageBubble(message = m, toolResults = toolResults)
                    }
                }
            }
        }
    }

    // ask_user 对话框
    pending?.let { q ->
        AskUserDialog(q = q, onAnswer = { vm.answerQuestion(it) })
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🐱", fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text("AI Android", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "运行在手机上的 AI Agent",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "可以帮主人：整理文件 · 执行终端命令 · 联网搜索 · 定时任务 · 操控其他 App",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "试试：「整理我的下载目录」",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AskUserDialog(q: MainViewModel.PendingQuestion, onAnswer: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("🙋 Agent 提问") },
        text = {
            Column {
                Text(q.question, fontSize = 14.sp)
                if (q.options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    q.options.forEach { opt ->
                        TextButton(
                            onClick = { onAnswer(opt) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(opt, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("或输入回答…", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(text) }, enabled = text.isNotBlank()) {
                Text("回复")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer("") }) {
                Text("跳过")
            }
        },
    )
}
