package com.ai.android.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具调用展示卡片：折叠显示摘要，点击展开参数与结果。
 */
@Composable
fun ToolCallCard(
    call: ToolCall,
    result: ToolResult? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val running = result == null
    val success = result?.success ?: true

    val accent = when {
        running -> MaterialTheme.colorScheme.primary
        success -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.error
    }
    val icon = when {
        running -> "🔧"
        success -> "✅"
        else -> "❌"
    }

    val argsPretty = remember(call.arguments) { prettyJson(call.arguments) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = call.name,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = accent,
                )
                Spacer(Modifier.weight(1f))
                if (result != null) {
                    Text(
                        "${result.durationMs}ms",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (expanded) "▲" else "▼",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 参数摘要 / 完整参数
            if (argsPretty.isNotBlank() && argsPretty != "{}") {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (expanded) argsPretty.take(2000) else argsPretty.replace("\n", " ").take(90),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 30 else 1,
                )
            }

            // 结果
            if (result != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (expanded) result.output.take(6000) else result.output.replace("\n", " ").take(110),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    maxLines = if (expanded) 100 else 2,
                )
            }
        }
    }
}

/** 尝试把 JSON 字符串美化输出 */
internal fun prettyJson(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val el: JsonElement = Json.parseToJsonElement(raw)
        if (el is JsonPrimitive) el.content else Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), el)
    }.getOrDefault(raw)
}
