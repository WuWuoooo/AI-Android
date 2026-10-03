package com.ai.android.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.model.ChatMessage
import com.ai.android.model.ToolResult
import com.ai.android.util.MarkdownText

/**
 * 消息气泡：
 * - 用户消息右对齐
 * - AI 消息左侧渲染（思考折叠面板 + Markdown + 工具卡片）
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    toolResults: Map<String, ToolResult> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        ChatMessage.Role.USER -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        ChatMessage.Role.ASSISTANT -> AssistantBubble(message, toolResults, modifier)

        ChatMessage.Role.TOOL -> Unit // 工具结果已并入 assistant 工具卡片，不单独展示

        ChatMessage.Role.SYSTEM -> Unit // 系统消息不展示
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    toolResults: Map<String, ToolResult>,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // 💭 思考过程折叠面板
        if (message.reasoning.isNotBlank()) {
            ReasoningPanel(message.reasoning)
        }

        // 正文
        if (message.content.isNotBlank() || (message.isStreaming && message.toolCalls.isEmpty() && message.reasoning.isBlank())) {
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

        // 工具调用卡片
        if (message.toolCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.toolCalls.forEach { call ->
                    ToolCallCard(call = call, result = toolResults[call.id])
                }
            }
        }

        // 错误
        message.error?.let { err ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = "❌ $err",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // Token 统计
        message.tokenStats?.let { s ->
            Text(
                text = "↑${s.promptTokens} ↓${s.completionTokens} · 缓存命中 ${s.cacheRate}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** 思考过程折叠面板 */
@Composable
private fun ReasoningPanel(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💭 思考过程", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
