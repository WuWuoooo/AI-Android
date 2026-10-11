package com.ai.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import com.ai.android.ui.components.AppMenuPanel
import com.ai.android.ui.components.AppMenuItem
import com.ai.android.plugin.ChatBubbleStyle
import com.ai.android.util.MarkdownText
import java.io.File

/**
 * ⭐ v1.1.0 #3：取当前生效的聊天气泡样式（插件 THEME 可覆盖；无插件返回默认）。
 * 宿主在 [MessageBubble] 渲染时读取，覆盖默认圆角 / 配色 / 字号 / 留白。
 */
internal fun activeChatBubbleStyle(): ChatBubbleStyle = runCatching {
    com.ai.android.MainApp.instance.pluginRegistry.activeChatBubbleStyle
}.getOrDefault(ChatBubbleStyle.DEFAULT)

/** 把 Int?（ARGB）转 Compose Color；null 返回 null */
internal fun pluginColor(v: Int?): androidx.compose.ui.graphics.Color? =
    if (v != null) androidx.compose.ui.graphics.Color(v.toLong() and 0xFFFFFFFFL) else null

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
    /** ⭐ v1.1.0：修改后重新发送（重新生成 AI 回复） */
    onResendUser: (String) -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onEnterMultiSelect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        ChatMessage.Role.USER -> UserBubble(
            message, displayContentOverride, selectionMode, isSelected,
            onToggleSelect, onEditUser, onResendUser, onDeleteMessage, onEnterMultiSelect, modifier,
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
    /** ⭐ v1.1.0：修改后重新发送（重新生成 AI 回复） */
    onResendUser: (String) -> Unit,
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

    // ⭐ v1.1.0 #3：插件可覆盖气泡圆角 / 配色 / 字号 / 留白
    val bubble = activeChatBubbleStyle()
    val userBubbleShape = androidx.compose.foundation.shape.RoundedCornerShape(
        bubble.cornerRadius.dp, bubble.cornerRadius.dp,
        if (bubble.cornerRadius.dp < 20.dp) 4.dp else bubble.cornerRadius.dp,
        bubble.cornerRadius.dp,
    )
    val userBubbleColor = pluginColor(bubble.userBg)
        ?: if (selectionMode && isSelected)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        else MaterialTheme.colorScheme.primaryContainer
    val bubbleFontSize = if (bubble.fontSize > 0) bubble.fontSize.sp else 15.sp
    val bubbleHPad = if (bubble.horizontalPadding > 0) bubble.horizontalPadding.dp else 12.dp
    val bubbleVPad = if (bubble.verticalPadding > 0) bubble.verticalPadding.dp else 9.dp
    val bubbleTextColor = pluginColor(bubble.textColor) ?: MaterialTheme.colorScheme.onPrimaryContainer

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
            shape = userBubbleShape,
            color = userBubbleColor,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = { if (!selectionMode) showMenu = true },
                ),
        ) {
            Column(Modifier.padding(horizontal = bubbleHPad, vertical = bubbleVPad)) {
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
                                displayText, fontSize = bubbleFontSize,
                                color = bubbleTextColor,
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
                        Text(displayText, fontSize = bubbleFontSize, color = bubbleTextColor)
                    }
                }
            }
        }

                                                // ⭐ 第五轮 #1/#8：长按操作菜单改 material3 DropdownMenu
        // （overlay 浮层不挤占布局 + 内建展开/收起过渡动画 + 锚定气泡附近，替代旧 Box+matchParentSize 撑高 item）
                // ⭐ material3 1.3.0 DropdownMenu 没有 containerColor 直传参数（须用 DropdownMenuDefaults.colors），
        //    用默认色（已是 surface）最稳，避免编译报错
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.widthIn(min = 150.dp),
            content = {
                AppMenuPanel(
                    items = listOf(
                        AppMenuItem(label = "修改", icon = Icons.Default.Edit),
                        AppMenuItem(label = "删除", icon = Icons.Default.Delete),
                        AppMenuItem(label = "多选", icon = Icons.Default.CheckCircle),
                        AppMenuItem(label = "复制", icon = Icons.Default.ContentCopy),
                        AppMenuItem(label = "选择文本", icon = Icons.Default.TextFields),
                    ),
                    onPick = { idx ->
                        when (idx) {
                            0 -> {
                                showMenu = false; editOpen = true
                                editText = displayContentOverride ?: extractUserDisplayContent(message.content)
                            }
                            1 -> { showMenu = false; confirmDelete = true }
                            2 -> { showMenu = false; onEnterMultiSelect() }
                            3 -> {
                                showMenu = false
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("ai", displayText))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            }
                            4 -> { showMenu = false; selectingText = true }
                        }
                    },
                    chrome = false,
                    title = "操作",
                )
            },
        )

        // ⭐ 修改对话框（草稿：退出软件或切换对话即清除，不落盘）
        if (editOpen) {
            AlertDialog(
                onDismissRequest = { editOpen = false },
                                title = { Text("修改消息") },
                text = {
                    Column {
                        Text("「保存」仅改显示、不重新生成；「保存并重新生成」会用新内容重发此条并让 AI 重新回答。",
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
                    // ⭐ v1.1.0：两个保存选项——「保存」仅改显示；「保存并重新生成」用新内容重发此条并让 AI 重新回答
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            onEditUser(editText.trim())
                            editOpen = false
                        }) { Text("保存", fontSize = 13.sp) }
                        TextButton(onClick = {
                            onResendUser(editText.trim())
                            editOpen = false
                        }) {
                            Text("保存并重新生成", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
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
        // ⭐ v1.1.0 #3：插件可覆盖 AI 气泡圆角 / 配色 / 字号 / 留白
    val bubble = activeChatBubbleStyle()
    val aiBubbleShape = androidx.compose.foundation.shape.RoundedCornerShape(
        if (bubble.cornerRadius.dp < 20.dp) 4.dp else bubble.cornerRadius.dp,
        bubble.cornerRadius.dp, bubble.cornerRadius.dp, bubble.cornerRadius.dp,
    )
    val aiBubbleColor = pluginColor(bubble.assistantBg)
        ?: if (selectionMode && isSelected)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surface
    val aiFontSize = if (bubble.fontSize > 0) bubble.fontSize.sp else 15.sp
    val aiHPad = if (bubble.horizontalPadding > 0) bubble.horizontalPadding.dp else 12.dp
    val aiVPad = if (bubble.verticalPadding > 0) bubble.verticalPadding.dp else 9.dp

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        if (message.reasoning.isNotBlank()) ReasoningPanel(message.reasoning, streaming = message.isStreaming)

        if (message.content.isNotBlank() ||
            (message.isStreaming && message.toolCalls.isEmpty() && message.reasoning.isBlank())) {
            Surface(
                shape = aiBubbleShape,
                color = aiBubbleColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() },
                    onLongClick = { if (selectionMode) onToggleSelect() },
                ),
            ) {
                Column(Modifier.padding(horizontal = aiHPad, vertical = aiVPad)) {
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
                            fontSize = aiFontSize,
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
        // ⭐ 第五轮：「更多」改 material3 DropdownMenu（锚定本按钮右下角 + 浮层不挤占 + 内建展开/收起动画 + wrap 不过长）
        Box {
            ToolbarBtn("更多", Icons.Default.MoreHoriz) { showMore = !showMore }
                                                DropdownMenu(
                expanded = showMore,
                onDismissRequest = { showMore = false },
                modifier = Modifier.widthIn(min = 120.dp),
                shape = RoundedCornerShape(10.dp),
                content = {
                    AppMenuPanel(
                        items = if (showContinue) {
                            listOf(AppMenuItem(label = "▶ 继续生成"))
                        } else {
                            listOf(AppMenuItem(label = "（无更多操作）", enabled = false))
                        },
                        onPick = { _ ->
                            showMore = false
                            if (showContinue) onContinue()
                        },
                        chrome = false,
                        title = "更多操作",
                    )
                },
            )
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
