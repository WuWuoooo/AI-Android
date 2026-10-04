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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.agent.tools.TerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Termux 风格终端屏：
 * - 与 AI 共享同一常驻会话（按当前对话 convId 绑定），历史保留（除非 clear）。
 * - 上方实时滚动显示会话输出（自动滚底）；下方输入框回车执行（clear 清屏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val convId = vm.current.value?.id.orEmpty()

    // ⭐ 按当前对话取/建共享会话（与 AI 终端工具同一进程）
    val session = remember(convId) {
        TerminalSessionManager.get(convId, context, vm.settings)
    }
    val scope = rememberCoroutineScope()

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

    fun runCommand(raw: String) {
        val cmd = raw
        if (cmd.isBlank()) return
        input = ""
        // clear 走 UI 清屏（不清 shell）
        if (cmd.trim() == "clear") {
            runCatching { session.clearScreen() }
            output = ""
            return
        }
        running = true
        // 写入常驻 shell（与 AI 共享）；输出由轮询异步刷新
        scope.launch(Dispatchers.Default + Job()) {
            runCatching { session.writeInput(cmd) }
            running = false
        }
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF101418)),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                // 输出区
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

                // 输入区
                                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { runCommand(input) }),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    maxLines = 1,
                    placeholder = { Text("$ ", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                )
            }
        }
    }
}
