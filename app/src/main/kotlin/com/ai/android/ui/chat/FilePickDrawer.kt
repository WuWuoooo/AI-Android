package com.ai.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ⭐ v1.2.0 #6：自定义文件选择器（底部抽屉，替代系统 SAF）。
 *
 * 规格：
 *  - 顶部：面包屑路径导航（/sdcard/Download > …）+ 关闭按钮。
 *  - 主体：文件列表（图标 + 名称 + 大小/日期），支持**多选**（Checkbox）。
 *  - 底部：「取消 / 确认」操作栏。
 *  - 背景：半透明遮罩（Scrim，由父级 ChatScreen 提供）。
 *
 * 底层用 `File("/sdcard")` 遍历；确认时把选中文件路径回传给 onConfirm。
 * 普通 /sdcard 直接读；Android/data 需 Shizuku（未授权时这些目录会列空）。
 */
@Composable
fun FilePickDrawer(
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var path by remember { mutableStateOf("/sdcard") }
    val root = remember { File("/sdcard") }

        // 面包屑：从 /sdcard 到当前 path 的各级
    // #13 修复：旧逻辑把根 "sdcard" 也当成子级追加，导致 /sdcard/sdcard 重复显示两层。
    // 现以 "/sdcard" 为起点，只追加其**之后**的路径段（split 出来的第一段 "sdcard" 跳过）。
    val crumbs = remember(path, root) {
        val segments = path.split("/").filter { it.isNotBlank() }
        val sub = segments.drop(1)   // 去掉开头的 "sdcard" 根段
        val out = mutableListOf("/sdcard")
        sub.forEach { seg -> out.add(out.last() + "/" + seg) }
        out
    }

    val entries = remember(path) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return@remember emptyList<File>()
        dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.toList() ?: emptyList()
    }

    var selected by remember(path) { mutableStateOf(mutableSetOf<String>()) }

    fun toggle(f: File) {
        if (f.isDirectory) {
            path = if (f.absolutePath == "/sdcard") "/sdcard" else f.absolutePath
            selected = mutableSetOf()
        } else {
            val s = selected.toMutableSet()
            if (s.contains(f.absolutePath)) s.remove(f.absolutePath) else s.add(f.absolutePath)
            selected = s
        }
    }

    // 弹层（父级已有遮罩；本组件只画面板，高度自撑）
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.heightIn(max = 380.dp)) {
            // 面包屑 + 关闭
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).weight(1f),
                ) {
                    crumbs.forEachIndexed { i, c ->
                        if (i > 0) Text(" › ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            c,
                            fontSize = 13.sp,
                            color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                path = c; selected = mutableSetOf()
                            },
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭")
                }
            }
            HorizontalDivider()

            // 文件列表
            LazyColumn(Modifier.weight(1f)) {
                if (entries.isEmpty()) {
                    item { Text("（空目录）", fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                }
                items(entries, key = { it.absolutePath }) { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { toggle(f) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (f.isDirectory) {
                            Icon(Icons.Default.Folder, null, Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.InsertDriveFile, null, Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 14.sp, maxLines = 1)
                            Text(
                                if (f.isDirectory) "文件夹" else fmtSize(f.length()),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!f.isDirectory) {
                            Checkbox(
                                checked = selected.contains(f.absolutePath),
                                onCheckedChange = { v ->
                                    val s = selected.toMutableSet()
                                    if (v) s.add(f.absolutePath) else s.remove(f.absolutePath)
                                    selected = s
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            // 底部操作栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消", fontSize = 14.sp)
                }
                TextButton(
                    onClick = { onConfirm(selected.toList()); onDismiss() },
                    modifier = Modifier.weight(1f),
                    enabled = selected.isNotEmpty(),
                ) {
                    Text("确认（已选 ${selected.size}）", fontSize = 14.sp)
                }
            }
        }
    }
}

private val fmtTime = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

private fun fmtSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0 / 1024.0)} GB"
}
