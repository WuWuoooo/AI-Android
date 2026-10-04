package com.ai.android.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    stateText: String = "",
    onPickFile: () -> Unit = {},
    onPickImage: () -> Unit = {},
    attachedFiles: List<String> = emptyList(),
    attachedImages: List<String> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
    /** 引用的消息内容（空串表示无引用） */
    quotedContent: String = "",
    onClearQuote: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showAddMenu by remember { mutableStateOf(false) }

    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column {
            if (isRunning) {
                Row(
                    Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stateText.ifBlank { "Agent 工作中…" }, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            // 引用框
            if (quotedContent.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(
                        Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply, null,
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text("引用", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            Text(
                                quotedContent.take(80).replace("\n", " ") +
                                    if (quotedContent.length > 80) "…" else "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = onClearQuote, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "取消引用", Modifier.size(14.dp))
                        }
                    }
                }
            }

            // 附件 chips
            if (attachedFiles.isNotEmpty() || attachedImages.isNotEmpty()) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachedFiles.forEach { path ->
                        val name = runCatching { File(path).name }.getOrDefault(path).take(16)
                        AssistChip(
                            onClick = { onRemoveAttachment(path) },
                            label = { Text(name, fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.InsertDriveFile, null, Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                        )
                    }
                    attachedImages.forEachIndexed { idx, img ->
                        AssistChip(
                            onClick = { onRemoveImage(img) },
                            label = { Text("图片 ${idx + 1}", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                        )
                    }
                }
            }

            Row(
                Modifier.padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(onClick = { showAddMenu = true }) {
                        Icon(Icons.Default.Add, "添加附件", Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("选择文件", fontSize = 13.sp) },
                            onClick = { showAddMenu = false; onPickFile() },
                        )
                        DropdownMenuItem(
                            text = { Text("选择图片", fontSize = 13.sp) },
                            onClick = { showAddMenu = false; onPickImage() },
                        )
                    }
                }

                OutlinedTextField(
                    value = value, onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入指令…", fontSize = 14.sp) },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                )

                Spacer(Modifier.width(8.dp))

                FilledIconButton(
                    onClick = { if (isRunning) onStop() else onSend() },
                    enabled = isRunning || value.isNotBlank()
                        || attachedFiles.isNotEmpty() || attachedImages.isNotEmpty(),
                    modifier = Modifier.size(46.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    ),
                ) {
                    if (isRunning) Icon(Icons.Default.Stop, "停止", tint = MaterialTheme.colorScheme.onError)
                    else Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }
    }
}