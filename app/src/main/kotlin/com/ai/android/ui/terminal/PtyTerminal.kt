package com.ai.android.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.android.MainViewModel
import com.ai.android.agent.tools.PtySessionManager
import com.ai.android.termux.BootstrapInstaller
import com.ai.android.termux.ProotRunner
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * ⭐ v1.0.0-Stable：完整 PTY 终端屏（com.termux:terminal-view，含 native libtermux.so）。
 *
 * 终端 UI 与 AI 的 terminal_exec / terminal_read 共用**同一按对话隔离的 PTY 会话**
 * （PtySessionManager），因此 AI 跑的命令能在终端屏实时看到，反之亦然（"与 AI 共享"）。
 * 底层是真 pty：Ctrl-C/D/Z 是 kernel 真信号，支持 jobs / fg / bg、vim / top、ANSI 彩色、宽字符。
 *
 * ⭐ 本轮新增（v1.0.0-Stable 增补）：
 *  - 第6项：终端支持双指捏合缩放字号（TerminalView 的 GestureAndScaleRecognizer + client.onScale 调 setTextSize）。
 *  - 第7项：PTY 启动注入 Termux 风格 PS1 提示符（`user@host:path$`，~ 表 HOME），仿真 ~/.bash。
 *  - 第8项：终端多窗口（标签页），每个标签独立 PTY 会话（见 [PtyMultiTerminalScreen]）。
 *  - 第9项：底部快捷键改为纯文字排列（非卡片按钮），两行：
 *      行1：ESC  /  -  HOME  ↑  END  PGUP
 *      行2：Tab  CTRL  ALT  ←  ↓  →  PGDN
 *    CTRL / ALT 为可 toggle 修饰键；点击方向键/功能键时按当前修饰状态组合发送 xterm 序列。
 *    AI 侧也可经 PtyTerminalSession.sendKeySeq / buildKeySeq 使用同样快捷键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PtyTerminalScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    /** ⭐ 第8项：独立会话 key（多窗口标签用）；为空则绑定当前对话 convId */
    sessionKey: String = "",
    /** 多窗口嵌入时隐藏自带的返回 TopAppBar（由外层标签栏提供返回） */
    showChrome: Boolean = true,
) {
    val context = LocalContext.current as Context
    val convId = vm.current.value?.id.orEmpty()
    // ⭐ 第8项：多窗口时每个标签用不同 sessionKey → PtySessionManager 各自独立 PTY 会话
    val key = if (sessionKey.isNotBlank()) sessionKey else convId
        val pty = remember(key) {
        runCatching { PtySessionManager.get(key, context) }.getOrNull()
    }
    val scope = rememberCoroutineScope()
    // ⭐ key 到 pty：切标签（sessionKey 变 → pty 变）时重新评估 proot 是否就绪
    val ready = remember(pty) {
        pty != null && runCatching {
            BootstrapInstaller.isInstalled(context) && ProotRunner(context).isReady()
        }.getOrDefault(false)
    }

    var attached by remember { mutableStateOf<TerminalSession?>(null) }
    var canRestart by remember { mutableStateOf(false) }

    LaunchedEffect(pty, ready) {
        if (pty != null && ready) {
            pty.ensureStarted()
            attached = pty.uiSession()
        }
    }
    LaunchedEffect(pty) {
        pty?.runningFlow?.collect { running -> canRestart = !running }
    }
    LaunchedEffect(pty) {
        pty?.sessionFlow?.collect { s -> if (s != null) attached = s }
    }

        // ⭐ 第9项：底部快捷键的 CTRL / ALT 修饰键 toggle 状态
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    // ⭐ v1.1.0 #1：客户端提升到 Screen 层，Toggle 变化时同步 ctrlDown/altDown
    // 给 AndroidView 里的 TermViewClient（软键盘输入路径用）
    val termClient = remember(key) { TermViewClient(null, baseTextSize = 14) }

    // ⭐ v1.1.0 #1：toggle 变化时把状态同步到 TermViewClient（软键盘读 ctrl/alt）
    LaunchedEffect(ctrlActive, altActive) {
        termClient.ctrlDown = ctrlActive
        termClient.altDown = altActive
    }


    if (showChrome) {
        Scaffold(
            topBar = {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Icon(Icons.Default.Terminal, null, tint = Color(0xFF7FD4A8))
                                Spacer(Modifier.size(6.dp))
                                Text("终端 (PTY)", fontSize = 16.sp)
                                Text(
                                    " · ${if (convId.isNotEmpty()) "绑定当前对话" else "默认"} · 双指缩放",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                            }
                        },
                    )
                }
            },
        ) { padding ->
                        PtyTerminalBody(
                pty = pty,
                attached = attached,
                canRestart = canRestart,
                termClient = termClient,
                onRestart = {
                    scope.launch {
                        pty?.restart()
                        pty?.ensureStarted()
                        attached = pty?.uiSession()
                        canRestart = false
                    }
                },
                onClearScreen = {
                    scope.launch {
                        pty?.sendKeySeq("ctrl-l", ctrl = true, alt = false, shift = false)
                    }
                },
                ctrlActive = ctrlActive,
                altActive = altActive,
                onToggleCtrl = { ctrlActive = !ctrlActive },
                onToggleAlt = { altActive = !altActive },
                onKey = { k ->
                    scope.launch { pty?.sendKeySeq(k, ctrl = ctrlActive, alt = altActive, shift = false) }
                },
                modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF101418)),
            )
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
            PtyTerminalBody(
                pty = pty,
                attached = attached,
                canRestart = canRestart,
                termClient = termClient,
                onRestart = {
                    scope.launch {
                        pty?.restart()
                        pty?.ensureStarted()
                        attached = pty?.uiSession()
                        canRestart = false
                    }
                },
                onClearScreen = {
                    scope.launch {
                        pty?.sendKeySeq("ctrl-l", ctrl = true, alt = false, shift = false)
                    }
                },
                ctrlActive = ctrlActive,
                altActive = altActive,
                onToggleCtrl = { ctrlActive = !ctrlActive },
                onToggleAlt = { altActive = !altActive },
                onKey = { k ->
                    scope.launch { pty?.sendKeySeq(k, ctrl = ctrlActive, alt = altActive, shift = false) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 终端主体（TerminalView + 底部快捷键条），供单窗口 / 多窗口标签复用 */
@Composable
private fun PtyTerminalBody(
    pty: com.ai.android.agent.tools.PtyTerminalSession?,
    attached: TerminalSession?,
    canRestart: Boolean,
    /** ⭐ v1.1.0 #1：提升到 Screen 层的 TermViewClient（toggle 时同步 ctrlDown/altDown） */
    termClient: TermViewClient,
    onRestart: () -> Unit,
    onClearScreen: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pty == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PTY 终端不可用", color = Color(0xFFE08080), fontSize = 13.sp)
                Text("（proot 未安装或会话未建）", color = Color(0xFFB0B0B0), fontSize = 11.sp)
            }
        }
        return
    }
                        Column(modifier.fillMaxSize().imePadding()) {
        // ⭐ 第6项：TerminalView 支持双指缩放（client.onScale 调 setTextSize，8..40sp）
        // ⭐ v1.1.0 #1：用 Screen 层共享的 termClient（软键盘输入读 ctrl/alt 状态）
        AndroidView(
                        factory = { ctx ->
                val tv = TerminalView(ctx, null)
                // ⭐ 必须设为 true，否则 Touch 模式下 requestFocus() 失败，软键盘弹不出
                //    （Termux 原版在 XML 里设了 android:focusableInTouchMode="true"）
                tv.isFocusable = true
                tv.isFocusableInTouchMode = true
                // ⭐ v1.1.0 #1：绑定共享 client，并把它后设的 view 绑回（client 在 Screen 层创建）
                termClient.setView(tv)
                tv.setTerminalViewClient(termClient)
                tv.setBackgroundColor(0xFF101418.toInt())
                tv.setTextSize(14)
                tv.setTypeface(Typeface.MONOSPACE)
                                                attached?.let { tv.attachSession(it) }
                tv.requestFocus()
                tv
            },
                        update = { tv ->
                val s = attached
                if (s != null && tv.mTermSession != s) tv.attachSession(s)
                // ⭐ 重绘绑定放 update（每次重组都跑）：factory 只跑一次，首次 attached 还是 null 会绑不上；
                //    这里 attached 有值后每次重组都重新绑，保证 onTextChanged → 实时刷新
                pty.onScreenUpdate = { tv.onScreenUpdated() }
            },
                                                modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        // ⭐ 第9项：底部纯文字快捷键条（两行；CTRL/ALT 可 toggle）
        TermKeyBar(
            onKey = onKey,
            onToggleCtrl = onToggleCtrl,
            onToggleAlt = onToggleAlt,
            ctrlActive = ctrlActive,
            altActive = altActive,
            onClearScreen = onClearScreen,
            canRestart = canRestart,
            onRestart = onRestart,
        )
    }
}

/** ⭐ 第9项：底部纯文字快捷键条（非卡片按钮）。
 * 行1：ESC  /  -  HOME  ↑  END  PGUP
 * 行2：Tab  CTRL  ALT  ←  ↓  →  PGDN
 * CTRL / ALT 为可 toggle 修饰键（激活高亮）；点击其它键按当前 ctrl/alt 组合发 xterm 序列。
 */
@Composable
private fun TermKeyBar(
    onKey: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    onClearScreen: () -> Unit,
    canRestart: Boolean,
    onRestart: () -> Unit,
) {
    @Composable
    fun keyRow(keys: List<Pair<String, String>>) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            keys.forEach { (label, key) ->
                KeyText(
                    label = label,
                    active = (key == "ctrl" && ctrlActive) || (key == "alt" && altActive),
                ) {
                    when (key) {
                        "ctrl" -> onToggleCtrl()
                        "alt" -> onToggleAlt()
                        "clear" -> onClearScreen()
                        "restart" -> onRestart()
                        else -> onKey(key)
                    }
                }
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF0C1014)),
    ) {
        keyRow(listOf(
            "ESC" to "esc", "/" to "/", "-" to "-",
            "HOME" to "home", "↑" to "up", "END" to "end", "PGUP" to "pgup",
        ))
        keyRow(listOf(
            "Tab" to "tab", "CTRL" to "ctrl", "ALT" to "alt",
            "←" to "left", "↓" to "down", "→" to "right", "PGDN" to "pgdn",
        ))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            KeyText(label = "清屏", active = false, onClick = { onClearScreen() })
            KeyText(label = "重启", active = canRestart, onClick = { onRestart() })
            if (ctrlActive) KeyText(label = "Ctrl", active = true, onClick = { onToggleCtrl() })
            if (altActive) KeyText(label = "Alt", active = true, onClick = { onToggleAlt() })
        }
    }
}

/** 单个文字快捷键（纯文字，点击有反馈；active 态高亮） */
@Composable
private fun KeyText(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = if (active) Color(0xFF7FD4A8) else Color(0xFF8AA89C),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * ⭐ 第6项：带双指缩放的 TerminalViewClient。
 * TerminalView 的 GestureAndScaleRecognizer 双指捏合 → onScale(累积 scale) →
 * 这里调 TerminalView.setTextSize(base*scale) 真正改变字号（clamp 8..40sp）。
 *
 * ⭐ v1.1.0 #1：软键盘输入路径（onCreateInputConnection → commitText → sendTextToTerminal
 * → inputCodePoint → mClient.readControlKey / readAltKey）。旧实现硬编码返回 false，
 * 导致「底部 CTRL toggle 高亮后按软键盘字母不带 ctrl」。这里加可变 ctrlDown / altDown，
 * 由 PtyTerminalScreen 在 toggle 状态变化时同步；readControlKey / readAltKey 返回当前值。
 */
private class TermViewClient(
    view: TerminalView?,
    private val baseTextSize: Int,
) : TerminalViewClient {
    private var viewRef: WeakReference<TerminalView> = WeakReference(view)

    /** ⭐ v1.1.0 #1：底部快捷键条 CTRL / ALT toggle 状态（由 UI 同步过来） */
    @Volatile var ctrlDown: Boolean = false
    @Volatile var altDown: Boolean = false

    /** 客户端可后绑定 TerminalView（在 AndroidView factory 里 setView） */
    fun setView(view: TerminalView) { viewRef = WeakReference(view) }

    override fun onScale(scale: Float): Float {
        val tv = viewRef.get()
        if (tv != null && scale > 0f) {
            val newSize = (baseTextSize * scale).toInt().coerceIn(8, 40)
            runCatching { tv.setTextSize(newSize) }
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        // ⭐ 修复：点终端区域时拉起软键盘。
        // 纯代码创建的 TerminalView 默认不可聚焦，先补 requestFocus()，
        // 再延迟 showSoftInput（模仿 Termux 原版 postDelayed，等 focus 就位）
        val tv = viewRef.get() ?: return
        tv.requestFocus()
        tv.postDelayed({
            val imm = tv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }
    override fun shouldBackButtonBeMappedToEscape() = true
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, s: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey() = ctrlDown
    override fun readAltKey() = altDown
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession) = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) { Log.e("PtyTV", "$tag: $message") }
    override fun logWarn(tag: String, message: String) { Log.w("PtyTV", "$tag: $message") }
    override fun logInfo(tag: String, message: String) { Log.i("PtyTV", "$tag: $message") }
    override fun logDebug(tag: String, message: String) { Log.d("PtyTV", "$tag: $message") }
    override fun logVerbose(tag: String, message: String) { Log.v("PtyTV", "$tag: $message") }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e("PtyTV", "$tag: $message", e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e("PtyTV", "${e.message}", e) }
}

/**
 * ⭐ 第8项：终端多窗口（标签页）屏。
 * 顶部标签栏：[终端1] [终端2] ... [+]，每个标签绑定独立 PTY 会话
 * （sessionKey = "term_${convId}_${index}"），底部统一用 [PtyTerminalScreen] 渲染当前标签。
 * 点 [+] 新增标签（上限 8）；点标签左上 ✕ 关闭（至少保留 1 个）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PtyMultiTerminalScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
            val convId = vm.current.value?.id.orEmpty()

    // ⭐ 标签列表用不可变 List 重赋值（mutableStateOf 的 MutableList 原地改不触发重组）
    var tabs by remember { mutableStateOf(listOf("" to "共享(AI)")) } // (sessionKey, label)
    var activeIndex by remember { mutableStateOf(0) }

    fun addTab() {
        if (tabs.size >= 8) return
        val idx = tabs.size
        val key = "term_${convId}_${idx}"
        tabs = tabs + (key to "终端${idx + 1}")
        activeIndex = idx
    }

    fun closeTab(i: Int) {
        if (tabs.size <= 1) return
        tabs = tabs.toMutableList().also { it.removeAt(i) }
        activeIndex = activeIndex.coerceIn(0, tabs.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        // 标签栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C1014))
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color(0xFF7FD4A8))
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEachIndexed { i, (key, label) ->
                    val active = i == activeIndex
                    Row(
                        Modifier
                            .background(
                                if (active) Color(0xFF1E2A22) else Color(0xFF14181C),
                                androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (active) Color(0xFF7FD4A8) else Color(0xFF8AA89C),
                        )
                        if (tabs.size > 1) {
                            Text(
                                "✕",
                                fontSize = 11.sp,
                                color = Color(0xFFE08080),
                                modifier = Modifier.clickable { closeTab(i) },
                            )
                        }
                    }
                    if (active) {
                        // 高亮指示
                    }
                }
            }
            IconButton(onClick = { addTab() }, enabled = tabs.size < 8) {
                Text(
                    "+",
                    fontSize = 20.sp,
                    color = Color(0xFF7FD4A8),
                )
            }
        }

        // 当前标签的终端主体（独立 PTY 会话）
        val activeTab = tabs.getOrNull(activeIndex)
        if (activeTab != null) {
            PtyTerminalScreen(
                vm = vm,
                onBack = onBack,
                sessionKey = activeTab.first,
                showChrome = false,
            )
        }
    }
}
