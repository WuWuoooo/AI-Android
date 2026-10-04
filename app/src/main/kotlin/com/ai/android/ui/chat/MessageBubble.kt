package com.ai.android.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.model.ChatMessage
import com.ai.android.model.ToolResult
import com.ai.android.util.MarkdownText
import java.io.File

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
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        ChatMessage.Role.USER -> UserBubble(message, modifier)
        ChatMessage.Role.ASSISTANT -> AssistantBubble(
            message, toolResults, isLastAssistant, showContinue,
            onRetry, onContinue, onCopy, onQuote, onSpeak, modifier,
        )
        ChatMessage.Role.TOOL, ChatMessage.Role.SYSTEM -> Unit
    }
}

@Composable
private fun UserBubble(message: ChatMessage, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
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
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
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

                val displayText = extractUserDisplayContent(message.content)
                if (displayText.isNotBlank()) {
                    Text(displayText, fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
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
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        if (message.reasoning.isNotBlank()) ReasoningPanel(message.reasoning)

        if (message.content.isNotBlank() ||
            (message.isStreaming && message.toolCalls.isEmpty() && message.reasoning.isBlank())) {
            Surface(
                shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    MarkdownText(
                        text = if (message.isStreaming) message.content + " ▍" else message.content,
                        fontSize = 15.sp,
                    )
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

        if (isLastAssistant && !message.isStreaming && message.content.isNotBlank()) {
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
                // ⭐ 只在生成未完整时显示"继续生成"
                if (showContinue) {
                    DropdownMenuItem(
                        text = { Text("▶ 继续生成", fontSize = 13.sp) },
                        onClick = { showMore = false; onContinue() },
                    )
                }
                // 未来可以在这里加其他菜单项（导出、删除等）
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
private fun ReasoningPanel(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💭 思考过程", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (expanded) text else text.take(80) + if (text.length > 80) "…" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 500 else 2,
            )
        }
    }
}