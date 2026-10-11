package com.ai.android.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * ⭐ v1.2.0 输入框（问题 2 重做）：
 *
 * 规格（主人要求）：
 *  - **加号 / 语音 / 发送 都放进输入框里面**（不再是框外一排按钮）。
 *  - **只保留「加号」和「右侧按钮」两个控件**（去掉原来独立的语音 + 发送双按钮）。
 *  - 右侧按钮三态：
 *      · 输入框**为空** → 显示「语音」（Mic）：点击开始录音 → 转写完成**立即发送**
 *      · 输入框**有内容** → 显示「发送」（Send）
 *      · **正在生成** → 显示「暂停」（Stop）
 *  - 语音输入完成后**立即自动发送**（不再只是回填输入框）。
 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
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
    /** 音视频通话（v1.0.0-beta4） */
    onStartVoiceCall: () -> Unit = {},
    onStartVideoCall: () -> Unit = {},
        /** ⭐ v1.2.0 #3：语音输入控制器（录音 → ASR → 立即发送） */
    voiceController: VoiceInputController? = null,
    /** 语音转写结果 → 立即发送 */
    onVoiceAutoSend: (String) -> Unit = {},
                // ⭐ v1.2.0-next：输入框下方显示当前模型名（参考图二 DeepSeek V4.1flash），点击可切换模型
        modelLabel: String = "",
    onSwitchModel: () -> Unit = {},
                /** ⭐ v1.2.0-next #10：加号菜单 toggle（自绘 AppMenuPanel，由 ChatScreen 顶层渲染） */
    onToggleAddMenu: () -> Unit = {},
    /** ⭐ 第五轮 #2：模型菜单是否展开（箭头随之旋转 90°，提示当前可切模型） */
    modelMenuOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
        var voiceActive by remember { mutableStateOf(false) }   // 是否正在录音/转写
    // ⭐ 第五轮 #2：模型名行箭头随菜单展开旋转（展开 90°，收起 0°），用 animateFloatAsState 平滑过渡
    val modelArrowAngle by animateFloatAsState(
        targetValue = if (modelMenuOpen) 90f else 0f,
        animationSpec = tween(200),
        label = "model_arrow",
    )

        // 语音控制器回调：转写结果 → 立即发送（主人要求「输入完立即发送」）
    LaunchedEffect(voiceController) {
        voiceController?.apply {
            onResult = { text ->
                voiceActive = false
                if (text.isNotBlank()) {
                    onVoiceAutoSend(text)   // ⭐ 立即自动发送
                }
            }
            // ⭐ hotfix #1：录音太短 / 缺权限 / 识别失败等错误复位 UI，避免卡在「正在录音」
            onError = { voiceActive = false }
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { voiceController?.cancel() } }
    }

    val hasText = value.isNotBlank()
    val micEnabled = !isRunning && voiceController != null

    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
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

            // ⭐ 录音 / 转写状态提示行
            if (voiceActive && !isRunning) {
                val p = voiceController?.phase
                Row(
                    Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = when {
                        p == VoiceInputController.Phase.RECORDING -> "正在录音…（说完会自动转写并发送）"
                        p == VoiceInputController.Phase.LIVE -> "正在识别…（说一句话）"
                        p == VoiceInputController.Phase.TRANSCRIBING -> "转写中…"
                        else -> ""
                    }
                    if (label.isNotEmpty()) {
                        CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ==================== ⭐ 输入框：加号 + 文本框 + 右侧按钮（全在框内）====================
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                                // 左侧：加号（放进输入框胶囊内）
                // ⭐ v1.2.0-next #10：加号菜单改自绘 AppMenuPanel（ChatScreen 顶层渲染，避免 bottomBar 裁剪）
                IconButton(onClick = onToggleAddMenu) {
                    Icon(Icons.Default.Add, "添加附件", Modifier.size(22.dp))
                }

                                // 中间：多行文本框（圆角胶囊）
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息或按住说话", fontSize = 14.sp) },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                )

                Spacer(Modifier.width(6.dp))

                                // 右侧：三态按钮（语音 / 发送 / 暂停）
                if (isRunning) {
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(46.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error),
                    ) { Icon(Icons.Default.Stop, "暂停", tint = MaterialTheme.colorScheme.onError) }
                } else if (hasText) {
                    // 有内容 → 发送
                    FilledIconButton(
                        onClick = { onSend(value) },
                        modifier = Modifier.size(46.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                                                } else {
                                        // 空框 → 语音（⭐ hotfix #1：点一下开始录音、再点一下结束并自动转写发送。
                    // 缺权限 / 系统识别器不可用 / 录音太短，控制器都会 Toast，绝不静默消失）
                    FilledIconButton(
                        onClick = {
                            val c = voiceController ?: return@FilledIconButton
                            if (voiceActive) {
                                // 结束 → 转写 → 立即发送（onVoiceAutoSend / onError 回调复位 voiceActive）
                                c.finish()
                            } else {
                                // 开始；失败（缺权限等）控制器已 Toast，此时不置 active
                                val started = c.begin()
                                if (started) voiceActive = true
                            }
                        },
                        modifier = Modifier.size(46.dp)
                            .then(if (voiceActive) Modifier.border(2.dp, MaterialTheme.colorScheme.error, CircleShape) else Modifier),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (voiceActive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            contentColor = if (voiceActive) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.Mic,
                            if (voiceActive) "停止录音" else "语音输入",
                            tint = if (voiceActive) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

                        // ⭐ v1.2.0-next：输入框下方显示当前模型名（参考图二 DeepSeek V4.1flash），点击切换模型
            // #8：vertical 6dp→3dp，让模型名行贴近输入框，减少上方留白
            if (modelLabel.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSwitchModel)
                        .padding(horizontal = 20.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modelLabel,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                                        Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "切换模型",
                        // ⭐ 第五轮 #2：箭头随模型菜单展开旋转 90°（modelMenuOpen=true 时）
                        modifier = Modifier
                            .graphicsLayer { rotationZ = modelArrowAngle }
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
