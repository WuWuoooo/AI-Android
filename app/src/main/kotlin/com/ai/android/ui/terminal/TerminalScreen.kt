package com.ai.android.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.agent.tools.TerminalSessionManager

/**
 * Termux 风格终端屏 v1.0.0-Stable：
 * - 与 AI 共享同一常驻会话（按当前对话 convId 绑定），历史保留（除非 clear）。
 * - ⭐ 新增 Ctrl-C / Ctrl-D / Ctrl-Z 快捷按钮（向常驻 shell 发对应控制字符）。
 * - ⭐ 清屏 + 重启会话按钮。
 * - ⭐ 键盘弹起时输入区随键盘上移（imePadding），输出区自适应压缩。
 * - 上方实时滚动显示会话输出（自动滚底）；下方输入框回车执行（clear 清屏）。
 *
 * ⭐ v1.0.0-Stable：若设置 terminalUsePty() 开启，则走完整 PTY 终端（[PtyTerminalScreen]），
 *    支持真 Ctrl-C/D/Z 信号、jobs/fg/bg、vim/top、ANSI 彩色；否则走稳定管道版。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: MainViewModel, onBack: () -> Unit) {
    // ⭐ PTY 终端开关（默认开，走完整 PTY；关闭则回退管道版稳定兜底）
    val usePty = remember { vm.settings.terminalUsePty() }
    if (usePty) {
        // ⭐ 第8项：终端多窗口（标签页），每个标签独立 PTY 会话；默认开一个"AI 共享"标签
        PtyMultiTerminalScreen(vm = vm, onBack = onBack)
        return
    }
    PipelineTerminalScreen(vm = vm, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipelineTerminalScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val convId = vm.current.value?.id.orEmpty()

    // ⭐ 按当前对话取/建共享会话（与 AI 终端工具同一进程）
    val session = remember(convId) {
        TerminalSessionManager.get(convId, context, vm.settings)
    }

    var output by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    // ⭐ 订阅会话输出流（免轮询，AI 跑终端时即时刷新；replay=1 订阅即得最新快照）
    LaunchedEffect(session) {
        runCatching { session.ensureStarted() }
        session.outputFlow.collect { latest ->
            output = latest
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(output) {
        if (output.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    // ⭐ 键盘弹起后自动聚焦输入框（让光标可见）
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    /** 执行命令（主线程，非阻塞写入） */
    fun runCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        input = ""
        // clear 走 UI 清屏（不清 shell）
        if (cmd == "clear") {
            runCatching { session.clearScreen() }
            output = ""
            return
        }
        running = true
        // ⭐ writeInput 是快速非阻塞写入（仅 writer.write+flush），主线程调用安全
        runCatching { session.writeInput(cmd) }
        running = false
    }

    /** 发送控制字符（Ctrl-C/D/Z）到常驻 shell（主线程，非阻塞写入） */
    fun sendCtrl(ctrlChar: String) {
        running = true
        runCatching { session.sendControlChar(ctrlChar) }
        running = false
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, null, tint = Color(0xFF7FD4A8))
                            Spacer(Modifier.width(6.dp))
                            Text("终端", fontSize = 16.sp)
                            Text(
                                " · ${if (convId.isNotEmpty()) "绑定当前对话" else "默认"}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("term", output))
                            Toast.makeText(context, "已复制终端输出", Toast.LENGTH_SHORT).show()
                        }) { Text("复制", fontSize = 13.sp) }
                    },
                )
            }
        },
    ) { padding ->
        // ⭐ imePadding：键盘弹起时整块内容自动上移，输入框始终可见
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .background(Color(0xFF101418)),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                // 输出区（自适应占满剩余空间）
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        output.ifEmpty { "（终端已就绪，下方输入命令，回车执行；输入 clear 清屏）" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFD6E0DA),
                    )
                    if (running) {
                        Text("▍执行中…", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = Color(0xFF7FD4A8))
                    }
                }

                // ⭐ 快捷按钮行（Ctrl-C/D/Z + 清屏 + 重启会话）
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtrlBtn("Ctrl-C", "⏹", onClick = { sendCtrl("\u0003") })
                    CtrlBtn("Ctrl-D", "⏏", onClick = { sendCtrl("\u0004") })
                    CtrlBtn("Ctrl-Z", "⏸", onClick = { sendCtrl("\u001A") })
                    CtrlBtn("清屏", "⌫", onClick = {
                        runCatching { session.clearScreen() }
                        output = ""
                    })
                    CtrlBtn("重启", "↻", onClick = {
                        runCatching {
                            session.restart()
                            session.ensureStarted()
                        }
                        output = ""
                    })
                    Spacer(Modifier.weight(1f))
                }

                // 输入区
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = { runCommand(input) },
                        onDone = { runCommand(input) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    maxLines = 1,
                    placeholder = { Text("$ ", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7FD4A8),
                        unfocusedBorderColor = Color(0xFF3A4A40),
                        focusedLabelColor = Color(0xFF7FD4A8),
                    ),
                )
            }
        }
    }
}

/** 快捷按钮（Termux 风格小圆角方块：Ctrl-C / Ctrl-D / Ctrl-Z / 清屏 / 重启） */
@Composable
private fun CtrlBtn(
    label: String,
    icon: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.width(56.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A22)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(icon, fontSize = 16.sp, color = Color(0xFF7FD4A8))
            Text(label, fontSize = 9.sp, color = Color(0xFF8AA89C))
        }
    }
}
