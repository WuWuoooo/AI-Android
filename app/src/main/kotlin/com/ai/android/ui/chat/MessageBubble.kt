package com.ai.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.model.ChatMessage
import com.ai.android.model.ToolResult
import com.ai.android.util.MarkdownText
import java.io.File

@OptIn(ExperimentalFoundationApi::class)

@Composable
fun MessageBubble(
    message: ChatMessage,
    toolResults: Map<String, ToolResult> = emptyMap(),
    isLastAssistant: Boolean = false,
    /** ⭐ 只有当前正在显示的最后一条 assistant 且生成未完整结束时才为 true */
    showContinue: Boolean = false,
    onRetry: () -> Unit = {},
    onContinue: () -> Unit = {},
    onCopy: () -> Unit = {},
    onQuote: () -> Unit = {},
    onSpeak: () -> Unit = {},
    // ⭐ 用户消息长按菜单 + 多选（用户额外需求 2）
    /** 用户消息编辑草稿（切换对话/退出即清；非空则覆盖显示原文） */
    displayContentOverride: String? = null,
    /** 是否处于多选模式 */
    selectionMode: Boolean = false,
    /** 当前消息是否被选中 */
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onEditUser: (String) -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onEnterMultiSelect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        ChatMessage.Role.USER -> UserBubble(
            message, displayContentOverride, selectionMode, isSelected,
            onToggleSelect, onEditUser, onDeleteMessage, onEnterMultiSelect, modifier,
        )
        ChatMessage.Role.ASSISTANT -> AssistantBubble(
            message, toolResults, isLastAssistant, showContinue,
            onRetry, onContinue, onCopy, onQuote, onSpeak,
            selectionMode, isSelected, onToggleSelect,
            modifier,
        )
        ChatMessage.Role.TOOL, ChatMessage.Role.SYSTEM -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: ChatMessage,
    displayContentOverride: String?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onEditUser: (String) -> Unit,
    onDeleteMessage: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(displayContentOverride ?: extractUserDisplayContent(message.content)) }

    val displayText = displayContentOverride?.takeIf { it.isNotBlank() }
        ?: extractUserDisplayContent(message.content)

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        if (message.files.isNotEmpty() || message.images.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
            ) {
                message.files.forEach { path ->
                    val name = runCatching { File(path).name }.getOrDefault(path).take(14)
                    Chip(icon = Icons.Default.InsertDriveFile, text = name)
                }
                message.images.forEachIndexed { idx, _ ->
                    Chip(icon = Icons.Default.Image, text = "图片 ${idx + 1}")
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            color = if (selectionMode && isSelected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = { if (!selectionMode) showMenu = true },
                ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (selectionMode) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                        Text(
                            "选择此条", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                }

                if (message.quotedContent.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    ) {
                        Column(Modifier.padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text("引用", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                message.quotedContent.take(120) +
                                    if (message.quotedContent.length > 120) "…" else "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                            )
                        }
                    }
                }

                if (displayText.isNotBlank()) {
                    if (selectingText) {
                        SelectionContainer {
                            Text(
                                displayText, fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                selectingText = false
                                val sel = ""  // 由系统选择手柄处理复制；此处提供"完成"
                            }) { Text("完成选择", fontSize = 11.sp) }
                        }
                    } else {
                        Text(displayText, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        // ⭐ 长按操作菜单（与输入框左侧加号菜单风格一致）
        Box {
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("修改", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false; editOpen = true
                        editText = displayContentOverride ?: extractUserDisplayContent(message.content)
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)) },
                    onClick = { showMenu = false; confirmDelete = true },
                )
                                DropdownMenuItem(
                    text = { Text("多选", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp)) },
                    onClick = { showMenu = false; onEnterMultiSelect() },
                )
                DropdownMenuItem(
                    text = { Text("复制", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) },
                    onClick = {
                        showMenu = false
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ai", displayText))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    },
                )
                DropdownMenuItem(
                    text = { Text("选择文本", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.TextFields, null, Modifier.size(16.dp)) },
                    onClick = { showMenu = false; selectingText = true },
                )
            }
        }

        // ⭐ 修改对话框（草稿：退出软件或切换对话即清除，不落盘）
        if (editOpen) {
            AlertDialog(
                onDismissRequest = { editOpen = false },
                title = { Text("修改消息") },
                text = {
                    Column {
                        Text("编辑后只显示修改版，不重新生成；切换对话或退出 App 即清除。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            placeholder = { Text("输入要显示的内容…", fontSize = 13.sp) },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onEditUser(editText.trim())
                        editOpen = false
                    }) { Text("保存", fontSize = 13.sp) }
                },
                dismissButton = { TextButton(onClick = { editOpen = false }) { Text("取消", fontSize = 13.sp) } },
            )
        }

        // ⭐ 删除确认对话框
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("删除消息") },
                text = { Text("确定删除这条消息吗？（不可恢复）", fontSize = 14.sp) },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; onDeleteMessage() }) {
                        Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", fontSize = 13.sp) } },
            )
        }
    }
}

private fun extractUserDisplayContent(fullContent: String): String {
    var s = fullContent
    if (s.startsWith("【引用消息】")) {
        val end = s.indexOf("\n\n", startIndex = 6)
        if (end >= 0) s = s.substring(end + 2)
    }
    val attachIdx = s.indexOf("--- 以下是用户附加的文件内容 ---")
    if (attachIdx >= 0) s = s.substring(0, attachIdx).trimEnd()
    return s.trim()
}

@Composable
private fun Chip(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, fontSize = 10.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    message: ChatMessage,
    toolResults: Map<String, ToolResult>,
    isLastAssistant: Boolean,
    showContinue: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onSpeak: () -> Unit,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        if (message.reasoning.isNotBlank()) ReasoningPanel(message.reasoning, streaming = message.isStreaming)

        if (message.content.isNotBlank() ||
            (message.isStreaming && message.toolCalls.isEmpty() && message.reasoning.isBlank())) {
            Surface(
                shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                color = if (selectionMode && isSelected)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = { if (selectionMode) onToggleSelect() },
                ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    if (selectionMode) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                            Text("选择此条", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    Box {
                        MarkdownText(
                            text = if (message.isStreaming) message.content + " ▍" else message.content,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }

        if (message.toolCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.toolCalls.forEach { call -> ToolCallCard(call = call, result = toolResults[call.id]) }
            }
        }

        message.error?.let { err ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text("❌ $err", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        message.tokenStats?.let { s ->
            Text("↑${s.promptTokens} ↓${s.completionTokens} · 缓存命中 ${s.cacheRate}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }

        if (isLastAssistant && !message.isStreaming && message.content.isNotBlank() && !selectionMode) {
            AssistantToolbar(
                showContinue = showContinue,
                onQuote = onQuote, onSpeak = onSpeak, onCopy = onCopy,
                onRetry = onRetry, onContinue = onContinue,
            )
        }
    }
}

@Composable
private fun AssistantToolbar(
    showContinue: Boolean,
    onQuote: () -> Unit,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        ToolbarBtn("引用追问", Icons.AutoMirrored.Filled.Reply, onQuote)
        ToolbarBtn("朗读", Icons.Default.VolumeUp, onSpeak)
        ToolbarBtn("复制", Icons.Default.ContentCopy, onCopy)
        ToolbarBtn("重新生成", Icons.Default.Refresh, onRetry)
        Box {
            ToolbarBtn("更多", Icons.Default.MoreHoriz) { showMore = true }
            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                if (showContinue) {
                    DropdownMenuItem(
                        text = { Text("▶ 继续生成", fontSize = 13.sp) },
                        onClick = { showMore = false; onContinue() },
                    )
                }
                if (!showContinue) {
                    DropdownMenuItem(
                        text = { Text("（无更多操作）", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { showMore = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarBtn(text: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
    ) {
        Icon(icon, contentDescription = text, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.sp)
    }
}

@Composable
private fun ReasoningPanel(text: String, streaming: Boolean = false) {
    var expanded by remember(streaming) { mutableStateOf(streaming) }
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        if (expanded && text.isNotEmpty()) scrollState.scrollTo(scrollState.maxValue)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💭 思考过程", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary)
                if (streaming) {
                    Spacer(Modifier.width(6.dp))
                    Text("生成中…", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(3.dp))
            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(scrollState),
                ) {
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = text.take(80) + if (text.length > 80) "…" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}
