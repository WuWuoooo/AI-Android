package com.ai.android.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.model.Project
import com.ai.android.plugin.PluginManager
import com.ai.android.provider.AIProvider
import com.ai.android.provider.ProviderConfig
import com.ai.android.provider.SearchManager
import com.ai.android.service.FloatingService
import com.ai.android.ui.components.AppDialog
import com.ai.android.util.PermissionHelper
import kotlinx.coroutines.delay
import java.util.UUID
import com.ai.android.termux.BootstrapInstaller
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ai.android.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val revision by vm.settings.revision.collectAsState()

        var configs by remember { mutableStateOf(vm.settings.loadConfigs()) }
    var activeId by remember { mutableStateOf(vm.settings.activeId()) }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
        var permStatus by remember { mutableStateOf(PermissionHelper.status(context)) }
    val toastSwitched = stringResource(R.string.toast_switched)
        // ⭐ v1.2.0-next #3：多级设置——null=分类概览页，"model"/"agent"/"ui"/"system"/"terminal"/"conversation"=详情页
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    // 分类表提升到函数体：外层 topBar（动态标题/返回）与 content 概览页共用
        val categories = listOf(
        "model" to "模型与能力",
        "agent" to "Agent 行为",
        "ui" to "界面与主题",
        "system" to "系统权限",
        "terminal" to "终端",
        "conversation" to "对话管理",
        "about" to "关于",
    )

    LaunchedEffect(revision) {
        configs = vm.settings.loadConfigs()
        activeId = vm.settings.activeId()
    }

        LaunchedEffect(Unit) {
        while (true) {
            permStatus = PermissionHelper.status(context)
            delay(3000)
        }
    }

    // ⭐ v1.2.0-next #6：二级设置按系统返回键先回一级；一级时不拦截，走系统 onBack 回聊天
    if (selectedCategory != null) {
        BackHandler {
            selectedCategory = null
        }
    }

        Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                TopAppBar(
                    title = {
                        Text(
                            if (selectedCategory == null) stringResource(R.string.set_title)
                            else categories.firstOrNull { it.first == selectedCategory }?.second.orEmpty()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectedCategory == null) onBack()
                            else selectedCategory = null
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
        ) { padding ->
                // ⭐ #5：设置一级/二级切换加过渡动画（右侧滑入，与 Activity 级设置页一致）
        AnimatedContent(
            targetState = selectedCategory,
            label = "settings_level",
                                    // ⭐ v1.2.0-next #5 + 编译修复：animation 1.7.0 的 AnimatedContent.transitionSpec
            // 要求返回 ContentTransform，须用 togetherWith 组合 enter/exit（旧代码误用 to 返回
            // Pair<Enter,Exit> 导致 "inferred type is Pair but ContentTransform was expected"）。
            transitionSpec = {
                when {
                    // 一级 → 二级：向右滑入（旧内容向左滑出）
                    initialState == null ->
                        (slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn(animationSpec = tween(200)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(200)))
                    // 二级 → 一级：向左滑入（旧内容向右滑出）
                    targetState == null ->
                        (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(animationSpec = tween(200)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut(animationSpec = tween(200)))
                    // 一级 ↔ 一级（切换不同分类）：淡入淡出
                    else ->
                        fadeIn(animationSpec = tween(200)).togetherWith(fadeOut(animationSpec = tween(200)))
                }
            },
        ) { level ->
        if (level == null) {
            // ===== 一级：分类概览页 =====
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(padding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                                items(categories) { (key, title) ->
                    Card(Modifier.clickable { selectedCategory = key }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            // ⭐ 自绘线性图标（参考系统设置页风格）
                            Icon(
                                categoryIcon(key),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(
                                                                        when (key) {
                                        "model" -> "模型 / 多模态"
                                        "agent" -> "参数 / 行为 / 读取限制"
                                        "ui" -> "悬浮窗 / 语言 / 插件 / 风格"
                                        "system" -> "Shizuku / AI 操控 / 权限"
                                        "terminal" -> "Termux / PTY / 环境变量"
                                        "about" -> "版本 / 项目 / 许可"
                                        else -> "记忆 / Token / 项目"
                                    },
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                                                                                    // 右箭头（仿系统设置 ›）——用 filled 版 ArrowForward（与顶栏 ArrowBack 同源，稳）
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
                } else {
            // ===== 二级：各分类详情页（外层 Scaffold 顶栏已动态显示分类名 + 返回上级，避免双顶栏） =====
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(padding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                    when (selectedCategory) {
                        "model" -> {
                            item { SectionTitle(stringResource(R.string.set_section_provider)) }
                            items(configs, key = { it.id }) { cfg ->
                                ProviderCard(
                                    cfg = cfg,
                                    isActive = cfg.id == activeId,
                                    onActivate = {
                                        vm.settings.setActive(cfg.id)
                                        activeId = vm.settings.activeId()
                                        Toast.makeText(context, "$toastSwitched ${cfg.label}", Toast.LENGTH_SHORT).show()
                                    },
                                    onEdit = { editing = cfg },
                                    onDelete = {
                                        vm.settings.deleteConfig(cfg.id)
                                        configs = vm.settings.loadConfigs()
                                        activeId = vm.settings.activeId()
                                    },
                                )
                            }
                            item {
                                OutlinedButton(
                                    onClick = { creating = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.set_add_provider)) }
                            }
                            item { SectionTitle(stringResource(R.string.set_section_multimodal)) }
                            item { MultimodalBindingCard(vm = vm, configs = configs) }
                        }
                        "agent" -> {
                            item { SectionTitle(stringResource(R.string.set_section_agent)) }
                            item { AgentParamsCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_ai_behavior)) }
                            item { AiBehaviorCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_readlimit)) }
                            item { ReadLimitCard(vm) }
                        }
                        "ui" -> {
                            item { SectionTitle(stringResource(R.string.set_section_display)) }
                            item { FloatingToggleCard(vm) }
                            // ⭐ v1.2.0-next #2：主题风格切换
                            item { SectionTitle("主题风格") }
                            item { ThemeStyleCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_language)) }
                            item { LanguageCard(vm, context) }
                            item { SectionTitle(stringResource(R.string.set_section_plugin)) }
                            item { PluginManagerCard(vm, context) }
                        }
                        "system" -> {
                            item { SectionTitle(stringResource(R.string.set_section_shizuku)) }
                            item { ShizukuCard(vm, context) }
                            item { SectionTitle(stringResource(R.string.set_section_aicontrol)) }
                            item { AiControlCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_permission)) }
                            item {
                                PermissionCard(
                                    status = permStatus,
                                    onRequestStorage = { activity?.let { p -> PermissionHelper.requestStorage(p) } },
                                    onRequestNotification = { activity?.let { p -> PermissionHelper.requestNotification(p) } },
                                    onRequestOverlay = { activity?.let { p -> PermissionHelper.requestOverlay(p) } },
                                    onRequestAccessibility = { activity?.let { p -> PermissionHelper.openAccessibilitySettings(p) } },
                                )
                            }
                        }
                        "terminal" -> {
                            item { SectionTitle(stringResource(R.string.set_section_termux)) }
                                                        item { TermuxCard(vm, context) }
                            item { SectionTitle(stringResource(R.string.set_section_terminal)) }
                            item { TerminalEnvCard(vm) }
                        }
                                                "conversation" -> {
                            item { SectionTitle(stringResource(R.string.set_section_memory)) }
                            item { MemoryManagerCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_token)) }
                            item { TokenStatsCard(vm, context) }
                            item { SectionTitle(stringResource(R.string.set_section_project)) }
                            item { ProjectManagerCard(vm) }
                        }
                        "about" -> {
                            item { AboutCard() }
                        }
                    }
                                                                                item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (creating) {
        EditProviderDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { cfg ->
                vm.settings.upsertConfig(cfg)
                configs = vm.settings.loadConfigs()
                creating = false
            },
        )
    }
    editing?.let { cfg ->
        EditProviderDialog(
            initial = cfg,
            onDismiss = { editing = null },
            onSave = { updated ->
                vm.settings.upsertConfig(updated)
                configs = vm.settings.loadConfigs()
                editing = null
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/**
 * ⭐ 设置概览页分类图标（仿系统设置的线性图标，不用 emoji）。
 * model/agent/ui/system/terminal/conversation → 6 个 outlined 图标。
 */
private fun categoryIcon(key: String): androidx.compose.ui.graphics.vector.ImageVector = when (key) {
    "model" -> Icons.Outlined.Cloud
    "agent" -> Icons.Outlined.SmartToy
    "ui" -> Icons.Outlined.Palette
        "system" -> Icons.Outlined.Lock
    "terminal" -> Icons.Outlined.Terminal
        "about" -> Icons.Outlined.Info
    else -> Icons.Outlined.Chat
}

// ==================== ⭐ 关于页（#4）====================

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrDefault("unknown")
    }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("关于 AI Android", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary)
                        AboutRow("版本", "v$versionName")
            AboutRow("技术栈", "Kotlin + Compose")
            AboutRow("定位", "运行在手机上的智能助手 Agent")
            AboutRow("许可", "开源")
            // ⭐ #3 仓库链接（可点击，打开浏览器；去掉"仅限个人学习使用"）
            Text(
                "仓库  github.com/WuWuoooo/Ai-Android",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/WuWuoooo/Ai-Android"),
                                )
                            )
                        }
                    },
            )
            Text(
                "本 App 可调用 AI 模型、操控手机界面、执行终端命令等。" +
                    "Shizuku 提权后能突破应用沙箱读写 Android/data。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp))
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ReadLimitCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var maxChars by remember { mutableStateOf(vm.settings.maxReadChars()) }
    LaunchedEffect(revision) { maxChars = vm.settings.maxReadChars() }

        Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.readlimit_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.readlimit_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 50_000, 100_000, 200_000, 500_000).forEach { v ->
                    FilterChip(
                        selected = maxChars == v,
                        onClick = { vm.settings.setMaxReadChars(v) },
                        label = {
                            Text(
                                if (v == 0) stringResource(R.string.readlimit_none) else "${v / 1000}K",
                                fontSize = 11.sp,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TermuxCard(vm: MainViewModel, context: android.content.Context) {
    var installed by remember { mutableStateOf(BootstrapInstaller.isInstalled(context)) }
    var installing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    val termuxUninstall = stringResource(R.string.termux_uninstall)

    val scope = rememberCoroutineScope()

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.termux_title),
                fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.termux_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (statusText.isNotBlank()) {
                Text(statusText, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed) {
                    Button(
                        enabled = !installing,
                        onClick = {
                            installing = true
                            statusText = "准备安装…"
                            scope.launch {
                                val r = BootstrapInstaller.install(context) { statusText = it }
                                installing = false
                                installed = r.success
                                statusText = r.message
                            }
                        },
                                        ) { Text(if (installing) stringResource(R.string.termux_installing) else stringResource(R.string.termux_install)) }
                } else {
                                        OutlinedButton(
                        onClick = {
                            scope.launch {
                                val ok = BootstrapInstaller.uninstall(context)
                                installed = false
                                statusText = if (ok) termuxUninstall else "⚠️"
                            }
                        },
                    ) { Text(termuxUninstall) }
                }
            }
        }
    }
}

@Composable
private fun TerminalEnvCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var pathPrefix by remember { mutableStateOf(vm.settings.terminalPathPrefix()) }
    var home by remember { mutableStateOf(vm.settings.terminalHome()) }
    var initScript by remember { mutableStateOf(vm.settings.terminalInitScript()) }
    var usePty by remember { mutableStateOf(vm.settings.terminalUsePty()) }
    LaunchedEffect(revision) {
        pathPrefix = vm.settings.terminalPathPrefix()
        home = vm.settings.terminalHome()
        initScript = vm.settings.terminalInitScript()
        usePty = vm.settings.terminalUsePty()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.terminal_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.terminal_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ⭐ v1.0.0-Stable：完整 PTY 终端开关
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                                                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.terminal_pty), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.terminal_pty_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = usePty, onCheckedChange = { vm.settings.setTerminalUsePty(it); usePty = it })
            }

                        OutlinedTextField(
                value = home, onValueChange = { home = it; vm.settings.setTerminalHome(it) },
                label = { Text(stringResource(R.string.terminal_home), fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pathPrefix,
                onValueChange = { pathPrefix = it; vm.settings.setTerminalPathPrefix(it) },
                label = { Text(stringResource(R.string.terminal_path), fontSize = 11.sp) },
                placeholder = { Text("/data/data/com.termux/files/usr/bin", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = initScript,
                onValueChange = { initScript = it; vm.settings.setTerminalInitScript(it) },
                label = { Text(stringResource(R.string.terminal_init), fontSize = 11.sp) },
                placeholder = { Text("export FOO=bar\nalias ll='ls -la'", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            )
        }
    }
}

// ==================== Provider ====================

@Composable
private fun ProviderCard(
    cfg: ProviderConfig,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isActive, onClick = onActivate)
            Column(Modifier.weight(1f)) {
                Text(cfg.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${cfg.protocol.name} · ${cfg.model}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                if (cfg.capabilities.isNotEmpty()) {
                    Text(
                        cfg.capabilities.joinToString(" · "),
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary, maxLines = 1,
                    )
                }
                Text(
                    cfg.baseUrl,
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                                Text(
                    if (cfg.apiKey.isBlank()) stringResource(R.string.provider_no_key)
                    else stringResource(R.string.provider_key_prefix) + " ${cfg.apiKey.take(6)}••••",
                    fontSize = 10.sp,
                    color = if (cfg.apiKey.isBlank()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.rename)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
}

// ==================== 多模态绑定 ====================

@Composable
private fun MultimodalBindingCard(vm: MainViewModel, configs: List<ProviderConfig>) {
    val revision by vm.settings.revision.collectAsState()

                val caps = listOf(
        "IMAGE" to stringResource(R.string.mm_image),
        "VISION" to stringResource(R.string.mm_vision),
        "TTS" to stringResource(R.string.mm_tts),
        "ASR" to stringResource(R.string.mm_asr),
        "SEARCH" to stringResource(R.string.mm_search),
        "CALL" to stringResource(R.string.mm_call),
    )

    // ⭐ 第五轮 #7：用自绘 AppDialog 做选择器（替代原生 DropdownMenu）
    var pickerCap by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.multimodal_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.multimodal_search) + "：${SearchManager.currentName()}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary,
            )

            caps.forEach { (cap, label) ->
                val boundId = vm.settings.multimodalBinding(cap)
                val bound = configs.firstOrNull { it.id == boundId }
                val display = bound?.let { "${it.label} · ${it.model}" }
                    ?: if (configs.none { cap in it.capabilities }) stringResource(R.string.multimodal_none)
                    else stringResource(R.string.multimodal_auto)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { pickerCap = cap }, modifier = Modifier.fillMaxWidth()) {
                            Text(display, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // ⭐ 第五轮 #7：自绘选择器（自动 + 各候选 Provider）
    pickerCap?.let { cap ->
        val label = caps.firstOrNull { it.first == cap }?.second ?: cap
        AppDialog(
            onDismissRequest = { pickerCap = null },
            title = "绑定「$label」",
            dismissText = "取消",
            onDismiss = { pickerCap = null },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(
                    onClick = {
                        vm.settings.setMultimodalBinding(cap, "")
                        pickerCap = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.multimodal_auto), fontSize = 13.sp) }
                configs.filter { cap in it.capabilities }.forEach { cfg ->
                    TextButton(
                        onClick = {
                            vm.settings.setMultimodalBinding(cap, cfg.id)
                            pickerCap = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${cfg.label} · ${cfg.model}", fontSize = 13.sp) }
                }
            }
        }
    }
}

// ==================== Agent 参数 ====================

@Composable
private fun AgentParamsCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var cfg by remember { mutableStateOf(vm.settings.agentConfig()) }

    LaunchedEffect(revision) { cfg = vm.settings.agentConfig() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                            (if (cfg.maxRounds == 0) "不限制" else "${cfg.maxRounds}") + " " +
                                stringResource(R.string.agent_rounds_times) +
                                (if (cfg.maxRounds == 0) "" else "（0=不限制）"),
                            fontSize = 13.sp,
                        )
            Slider(
                value = cfg.maxRounds.toFloat(),
                onValueChange = { vm.settings.setMaxRounds(it.toInt()) },
                valueRange = 0f..100f,
            )
                        Text(stringResource(R.string.agent_temperature) + ": ${String.format("%.1f", cfg.temperature)}", fontSize = 13.sp)
            Slider(
                value = cfg.temperature,
                onValueChange = { vm.settings.setTemperature(it) },
                valueRange = 0f..2f,
            )
            Text(stringResource(R.string.agent_retries) + ": ${cfg.networkRetries} ${stringResource(R.string.agent_rounds_times)}（0=）", fontSize = 13.sp)
            Slider(
                value = cfg.networkRetries.toFloat(),
                onValueChange = { vm.settings.setStreamRetries(it.toInt()) },
                valueRange = 0f..10f,
            )
            Text(stringResource(R.string.agent_compress) + ": ${cfg.contextCompressRounds}", fontSize = 13.sp)
            Text(
                stringResource(R.string.agent_compress_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = cfg.contextCompressRounds.toFloat(),
                onValueChange = { vm.settings.setContextCompressRounds(it.toInt()) },
                valueRange = 0f..50f,
            )
            Text(stringResource(R.string.agent_reasoning), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chips = listOf(
                    "" to stringResource(R.string.agent_reasoning_default),
                    "low" to stringResource(R.string.agent_reasoning_low),
                    "medium" to stringResource(R.string.agent_reasoning_medium),
                    "high" to stringResource(R.string.agent_reasoning_high),
                )
                chips.forEach { (v, label) ->
                    FilterChip(
                        selected = cfg.reasoningEffort == v,
                        onClick = { vm.settings.setReasoningEffort(v) },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            PromptExtraEditor(vm)
        }
    }
}

@Composable
private fun PromptExtraEditor(vm: MainViewModel) {
    var editing by remember { mutableStateOf(false) }
    val current = vm.settings.agentConfig().systemPromptExtra
        Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (current.isBlank()) stringResource(R.string.agent_prompt_unset)
            else stringResource(R.string.agent_prompt_title) + "：${current.take(20)}…",
            fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.agent_prompt_edit), fontSize = 12.sp) }
    }
    if (editing) {
        var text by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.agent_prompt_title)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text(stringResource(R.string.agent_prompt_example), fontSize = 12.sp) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.settings.setSystemPromptExtra(text)
                    editing = false
                }) { Text(stringResource(R.string.set_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}

// ==================== ⭐ v1.2.0-next #2：主题风格切换 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeStyleCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var currentStyle by remember { mutableStateOf(vm.settings.themeStyle()) }
    LaunchedEffect(revision) { currentStyle = vm.settings.themeStyle() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("主题风格", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "切换 App 整体视觉风格（预设配色 + 圆角 + 字重）",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.ai.android.ui.theme.ThemePreset.all().forEach { preset ->
                    FilterChip(
                        selected = currentStyle == preset.key,
                        onClick = {
                            vm.settings.setThemeStyle(preset.key)
                            currentStyle = preset.key
                        },
                        label = { Text(preset.label, fontSize = 12.sp) },
                    )
                }
            }
        }
    }
}

// ==================== 悬浮窗开关 ====================

@Composable
private fun FloatingToggleCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var enabled by remember { mutableStateOf(vm.settings.floatingEnabled()) }

    LaunchedEffect(revision) { enabled = vm.settings.floatingEnabled() }

    Card {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                        Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.floating_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.floating_desc),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    vm.settings.setFloatingEnabled(it)
                    enabled = it
                },
            )
        }
    }
}

// ==================== 项目管理 ====================

@Composable
private fun ProjectManagerCard(vm: MainViewModel) {
    val projects by vm.projects.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    // ⭐ 第五轮 #4：管理某项目的独立记忆
    var managingMemory by remember { mutableStateOf<Project?>(null) }
    val projectUnnamed = stringResource(R.string.project_unnamed)

    Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.project_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (projects.isEmpty()) {
                Text(stringResource(R.string.project_empty), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                projects.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        // ⭐ 第五轮 #4：每个项目可设置独立记忆
                        TextButton(onClick = { managingMemory = p }) {
                            Text("记忆", fontSize = 12.sp)
                        }
                        IconButton(onClick = { renaming = p }) {
                            Icon(Icons.Default.Edit, stringResource(R.string.rename), Modifier.width(16.dp))
                        }
                        IconButton(onClick = {
                            vm.deleteProject(p.id)
                        }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.width(16.dp))
                        }
                    }
                }
            }

            TextButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, null, Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.project_new), fontSize = 12.sp)
            }
        }
    }

        if (creating) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.project_new)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.project_name), fontSize = 13.sp) },
                )
            },
            confirmButton = {
                TextButton(
                                        onClick = {
                        vm.createProject(text.trim().ifBlank { projectUnnamed })
                        creating = false
                    },
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.project_create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }

    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.project_rename)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameProject(p.id, text.trim().ifBlank { p.name })
                    renaming = null
                }) { Text(stringResource(R.string.set_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }

    // ⭐ 第五轮 #4：项目记忆管理对话框
    managingMemory?.let { p ->
        ProjectMemoryDialog(vm = vm, project = p, onDismiss = { managingMemory = null })
    }
}

/**
 * ⭐ 第五轮 #4：单个项目的独立记忆管理对话框（增 / 删 / 看全文）。
 * 记忆与全局长期记忆分开存（前缀 proj:<id>:），AI 在该项目对话里可自动读写。
 */
@Composable
private fun ProjectMemoryDialog(vm: MainViewModel, project: Project, onDismiss: () -> Unit) {
    val memories by vm.projectMemoryList.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(project.id) { vm.refreshProjectMemory(project.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("项目记忆 · ${project.name}", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "该项目下所有对话共享这套记忆，AI 会自动读取并可按需增删改。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (memories.isEmpty()) {
                    Text("还没有项目记忆", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    memories.forEach { (k, v) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth().clickable { viewing = (k to v) },
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(k, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(
                                    if (v.length > 80) v.take(80) + "…" else v,
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.deleteProjectMemory(project.id, k) }) {
                                Icon(Icons.Default.Delete, "删除", Modifier.width(16.dp))
                            }
                        }
                    }
                }
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, null, Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加项目记忆", fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) } },
    )

    if (adding) {
        var key by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加项目记忆", fontSize = 14.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("标识（英文短词，如 goal）", fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = content, onValueChange = { content = it },
                        label = { Text("内容", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveProjectMemory(project.id, key.trim(), content)
                        adding = false
                    },
                    enabled = key.isNotBlank() && content.isNotBlank(),
                ) { Text("保存", fontSize = 13.sp) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消", fontSize = 13.sp) } },
        )
    }

    viewing?.let { (vk, vv) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text("项目记忆：$vk", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(vv, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProjectMemory(project.id, vk)
                    viewing = null
                }) { Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text("关闭", fontSize = 13.sp) } },
        )
    }
}

// ==================== 长期记忆 ====================

@Composable
private fun MemoryManagerCard(vm: MainViewModel) {
    val memories by vm.memoryList.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) { vm.refreshMemoryList() }

        Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.memory_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (memories.isEmpty()) {
                Text(stringResource(R.string.memory_empty), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                memories.forEach { (k, v) ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().clickable { viewing = (k to v) },
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(k, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (v.length > 120) v.take(120) + "…" else v,
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.deleteMemory(k) }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.width(16.dp))
                        }
                    }
                }
            }

            TextButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, null, Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.memory_add), fontSize = 12.sp)
            }
        }
    }

        viewing?.let { (vk, vv) ->
        ViewMemoryDialog(
            key = vk, content = vv,
            onDismiss = { viewing = null },
            onDelete = {
                vm.deleteMemory(vk)
                viewing = null
            },
        )
    }

    if (adding) {
        var key by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.memory_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text(stringResource(R.string.memory_key), fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = content, onValueChange = { content = it },
                        label = { Text(stringResource(R.string.memory_content), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveMemory(key.trim(), content)
                        adding = false
                    },
                    enabled = key.isNotBlank() && content.isNotBlank(),
                ) { Text(stringResource(R.string.set_save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}

// ==================== ⭐ v1.2.0-next #6：记忆查看对话框 ====================

@Composable
private fun ViewMemoryDialog(
    key: String,
    content: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("记忆：$key", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(content, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("memory_$key", content))
                Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("复制", fontSize = 13.sp) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) { Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
            }
        },
    )
}

// ==================== 权限 ====================

@Composable
private fun PermissionCard(
    status: PermissionHelper.Status,
    onRequestStorage: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
) {
        Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionRow(stringResource(R.string.perm_storage), status.storage, onRequestStorage)
            PermissionRow(stringResource(R.string.perm_notification), status.notification, onRequestNotification)
            PermissionRow(stringResource(R.string.perm_overlay), status.overlay, onRequestOverlay)
            PermissionRow(stringResource(R.string.perm_accessibility), status.accessibility, onRequestAccessibility)
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = if (granted) stringResource(R.string.perm_granted) else stringResource(R.string.perm_ungranted),
            fontSize = 11.sp,
            color = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!granted) {
            TextButton(onClick = onRequest) { Text(stringResource(R.string.perm_go), fontSize = 12.sp) }
        }
    }
}

// ==================== Provider 编辑对话框 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProviderDialog(
    initial: ProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var protocol by remember { mutableStateOf(initial?.protocol ?: AIProvider.Protocol.OPENAI) }
    var caps by remember { mutableStateOf(initial?.capabilities?.toSet() ?: setOf("CHAT")) }
        var extraBodyJson by remember { mutableStateOf(initial?.extraBodyJson ?: "") }
            // ⭐ v1.1.0 #5：逐条请求参数（新手化）。优先用已存 extraParams；
    //    否则从已有 extraBodyJson 解析成行（不丢数据）
    // ⭐ 第五轮 #7：该 Provider 从没自定义过参数（extraParams 与 extraBodyJson 均空）时，
    //    预置默认 5 个参数（temperature/top_p/max_tokens/stream/stop）作为「默认参数」，
    //    用户可逐条删除/改值/加自定义；已有自定义的保持原样不破坏。
    //    ⚠️ 用 when 分层而非 `?:` 链：extraParams 为空列表时 .toList() 非 null，会短路掉后面的分支
                var extraParams by remember {
        // ⚠️ 类型须为 List<ParamRow>（不是 MutableList）：后面 = extraParams+ / filterIndexed / mapIndexed
        //    都返回 List，赋给 MutableList 变量会「inferred List but MutableList expected」编译错
        // ⭐ 第五轮 #5+#6：默认不再无条件塞 PRESET_PARAMS（治"没选却存在"）。
        //    有已存参数保留；有 JSON 解析；全新/没配过 → 填「当前 provider 专属默认参数」(defaultParamsFor)，
        //    主人可删可改；新建时 initial=null 用通用对话默认，填了 baseUrl/model 后可点「填入当前 Provider 默认」重套。
        mutableStateOf(
            when {
                initial != null && initial.extraParams.isNotEmpty() -> initial.extraParams.toList()
                initial != null && initial.extraBodyJson.isNotBlank() ->
                    ProviderConfig.jsonToRows(initial.extraBodyJson)
                else -> ProviderConfig.defaultParamsFor(
                    ProviderConfig(model = "default", capabilities = listOf("CHAT"))
                )
            }
        )
    }
                var extraHeaders by remember { mutableStateOf(initial?.extraHeaders ?: "") }
    var advOpen by remember { mutableStateOf(false) }
    // ⭐ 高级 JSON 是否被手动改过（改过则保存时以 JSON 为准）
    var jsonDirty by remember { mutableStateOf(false) }
    // 类型下拉菜单挂在第几行（-1 = 收起）
    var typeMenuIdx by remember { mutableStateOf(-1) }
    val paramTypes = listOf("string", "number", "bool", "json")

    fun updateRow(idx: Int, f: (com.ai.android.provider.ParamRow) -> com.ai.android.provider.ParamRow) {
        extraParams = extraParams.mapIndexed { i, r -> if (i == idx) f(r) else r }
    }

        AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.provider_new_title) else stringResource(R.string.provider_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text(stringResource(R.string.provider_name), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = protocol == AIProvider.Protocol.OPENAI,
                        onClick = { protocol = AIProvider.Protocol.OPENAI },
                        label = { Text(stringResource(R.string.provider_openai), fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = protocol == AIProvider.Protocol.ANTHROPIC,
                        onClick = { protocol = AIProvider.Protocol.ANTHROPIC },
                        label = { Text(stringResource(R.string.provider_anthropic), fontSize = 11.sp) },
                    )
                }
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.provider_baseurl), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.provider_key), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text(stringResource(R.string.provider_model), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                                                Text(stringResource(R.string.provider_caps), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    "能力互斥：语音类（TTS/ASR/REALTIME）与正常 AI 对话类（CHAT/VISION/IMAGE/SEARCH）不能同选，" +
                        "勾选一类会自动去掉另一类。",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CapabilityChips(
                    selected = caps,
                    onToggle = { c ->
                        val willAdd = c !in caps
                        if (!willAdd) {
                            // 取消勾选：直接去掉（不清另一组）
                            caps = caps - c
                            return@CapabilityChips
                        }
                        val withNew = caps + c
                        // ⭐ 第五轮 #6：能力互斥——勾语音组清对话组，勾对话组清语音组
                        val final = when {
                            c in ProviderConfig.VOICE_CAPS -> withNew - ProviderConfig.CHAT_CAPS
                            c in ProviderConfig.CHAT_CAPS -> withNew - ProviderConfig.VOICE_CAPS
                            else -> withNew
                        }
                        caps = final
                    },
                )

                // ==================== ⭐ v1.1.0 #5：请求参数（逐条添加，新手化）====================
                Text(stringResource(R.string.param_title), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.param_desc),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                                // 预设 chip（一键添加常见参数）
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProviderConfig.PRESET_PARAMS.forEach { preset ->
                        val exists = extraParams.any { it.key == preset.key }
                        FilterChip(
                            selected = exists,
                            onClick = {
                                if (exists) return@FilterChip
                                extraParams = extraParams + preset
                                extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                                jsonDirty = false
                            },
                            // ⭐ 第五轮 #6：chip 后加「＋」提示可点加
                            label = { Text(preset.key + " ＋", fontSize = 11.sp) },
                        )
                    }
                }

                                // ⭐ 第五轮 #6：填入「当前 Provider 专属默认参数」（按主人刚填的 baseUrl/model/能力 计算，可改可删）
                TextButton(
                    onClick = {
                        val live = ProviderConfig(
                            baseUrl = baseUrl, model = model, capabilities = caps.toList()
                        )
                        val defaults = ProviderConfig.defaultParamsFor(live)
                        if (defaults.isEmpty()) {
                            Toast.makeText(context, "当前 Provider 没有可识别的专属默认参数", Toast.LENGTH_SHORT).show()
                        } else {
                            // 已存在的 key 保留主人改过的值；defaults 里没填过的补到最前
                            val existingKeys = extraParams.map { it.key }.toSet()
                            extraParams = defaults.filter { d -> d.key !in existingKeys } + extraParams
                            extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                            jsonDirty = false
                            Toast.makeText(context, "已填入 ${defaults.size} 条默认参数", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("填入当前 Provider 默认参数", fontSize = 12.sp)
                }

                                // 逐条列表
                if (extraParams.isEmpty()) {
                    Text(stringResource(R.string.param_empty), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                extraParams.forEachIndexed { idx, r ->
                    ParamRowEditor(
                        row = r,
                        index = idx,
                        count = extraParams.size,
                        types = paramTypes,
                        typeMenuIdx = typeMenuIdx,
                        onTypeMenu = { typeMenuIdx = if (typeMenuIdx == idx) -1 else idx },
                        onChange = { new -> updateRow(idx) { new } },
                        onDelete = {
                            extraParams = extraParams.filterIndexed { i, _ -> i != idx }
                            extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                            jsonDirty = false
                            if (typeMenuIdx == idx) typeMenuIdx = -1
                        },
                        onMoveUp = {
                            if (idx == 0) return@ParamRowEditor
                            val list = extraParams.toMutableList()
                            val t = list.removeAt(idx); list.add(idx - 1, t)
                            extraParams = list
                            extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                            jsonDirty = false
                        },
                        onMoveDown = {
                            if (idx == extraParams.size - 1) return@ParamRowEditor
                            val list = extraParams.toMutableList()
                            val t = list.removeAt(idx); list.add(idx + 1, t)
                            extraParams = list
                            extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                            jsonDirty = false
                        },
                    )
                }

                                OutlinedButton(
                    onClick = {
                        extraParams = extraParams + com.ai.android.provider.ParamRow()
                        extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                        jsonDirty = false
                    },
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.param_add), fontSize = 12.sp)
                }

                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { advOpen = !advOpen },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Icon(
                        if (advOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.width(16.dp).height(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.param_adv), fontSize = 12.sp)
                }

                                                                if (advOpen) {
                    OutlinedTextField(
                        value = extraBodyJson,
                        onValueChange = { extraBodyJson = it; jsonDirty = true },
                        label = { Text(stringResource(R.string.param_json_label), fontSize = 11.sp) },
                        placeholder = { Text("{\"top_p\":0.9}", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    if (jsonDirty) {
                        OutlinedButton(
                            onClick = {
                                // ⭐ JSON → 逐条 回写（保持两者互通，不丢数据）
                                val parsed = ProviderConfig.jsonToRows(extraBodyJson)
                                if (parsed.isNotEmpty()) {
                                    extraParams = parsed
                                    jsonDirty = false
                                }
                            },
                        ) { Text(stringResource(R.string.param_sync), fontSize = 11.sp) }
                    }
                    OutlinedTextField(
                        value = extraHeaders, onValueChange = { extraHeaders = it },
                        label = { Text(stringResource(R.string.param_headers_label), fontSize = 11.sp) },
                        placeholder = { Text("X-Custom: abc", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    Text(
                        stringResource(R.string.param_merge_hint),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
                confirmButton = {
            TextButton(
                onClick = {
                    // ⭐ 双向同步落盘：
                    //  - 未手动改 JSON（jsonDirty=false）→ extraBodyJson 已由逐条实时生成，二者一致
                    //  - 手动改过 JSON（jsonDirty=true）→ 以 JSON 为准，回推逐条参数
                    val finalJson = extraBodyJson.trim()
                    val finalRows = if (jsonDirty) ProviderConfig.jsonToRows(finalJson) else extraParams
                                        onSave(
                        ProviderConfig(
                            id = initial?.id ?: UUID.randomUUID().toString().take(8),
                            label = label.ifBlank { "未命名" },
                            protocol = protocol,
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            capabilities = caps.toList().ifEmpty { listOf("CHAT") },
                            extraBodyJson = finalJson,
                            extraParams = finalRows,
                            extraHeaders = extraHeaders.trim(),
                                                        contextWindow = initial?.contextWindow ?: 0,
                        )
                    )
                },
                                enabled = label.isNotBlank() && baseUrl.length > 8,
            ) { Text(stringResource(R.string.set_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityChips(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val all = listOf("CHAT", "VISION", "IMAGE", "TTS", "ASR", "SEARCH", "REALTIME")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        all.forEach { c ->
            FilterChip(
                selected = c in selected,
                onClick = { onToggle(c) },
                label = { Text(c, fontSize = 11.sp) },
            )
        }
    }
}

// ==================== ⭐ v1.1.0 #5：逐条请求参数编辑器 ====================

/**
 * 单条 键 / 值 / 类型 的表单行。
 * - 键：单行输入（请求体 JSON 的 key）
 * - 值：单行输入（按 [types] 选定的类型解释）
 * - 类型：可点开的下拉菜单（string / number / bool / json）
 * - 排序 / 删除：行尾「↑ ↓ 删除」按钮（上移 / 下移 / 删除）
 */
@Composable
private fun ParamRowEditor(
    row: com.ai.android.provider.ParamRow,
    index: Int,
    count: Int,
    types: List<String>,
    typeMenuIdx: Int,
    onTypeMenu: () -> Unit,
    onChange: (com.ai.android.provider.ParamRow) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                    value = row.key,
                    onValueChange = { onChange(row.copy(key = it)) },
                    label = { Text(stringResource(R.string.param_key), fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("temperature", fontSize = 10.sp) },
                )
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { onChange(row.copy(value = it)) },
                    label = { Text(stringResource(R.string.param_value), fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("0.3", fontSize = 10.sp) },
                )
                                // 类型选择（⭐ 第五轮 #7：自绘 AppDialog 选择器，替代原生 DropdownMenu）
                Box {
                    OutlinedButton(
                        onClick = onTypeMenu,
                        modifier = Modifier.width(76.dp),
                    ) {
                        Text(row.type, fontSize = 10.sp)
                    }
                }
            }
            if (typeMenuIdx == index) {
                AppDialog(
                    onDismissRequest = onTypeMenu,
                    title = "参数类型",
                    dismissText = "取消",
                    onDismiss = onTypeMenu,
                ) {
                    types.forEach { t ->
                        TextButton(
                            onClick = {
                                onChange(row.copy(type = t))
                                onTypeMenu()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(t, fontSize = 13.sp) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onMoveUp, enabled = index > 0, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("↑", fontSize = 11.sp)
                }
                OutlinedButton(onClick = onMoveDown, enabled = index < count - 1, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("↓", fontSize = 11.sp)
                }
                OutlinedButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Default.Delete, null, Modifier.size(14.dp))
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ==================== AI 行为（beta5） ====================

@Composable
private fun AiBehaviorCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var autoTitle by remember { mutableStateOf(vm.settings.autoTitleSummary()) }
    LaunchedEffect(revision) { autoTitle = vm.settings.autoTitleSummary() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ai_behavior_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.auto_title_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoTitle,
                    onCheckedChange = { vm.settings.setAutoTitleSummary(it); autoTitle = it },
                )
            }
        }
    }
}

// ==================== AI 操控（beta5） ====================

@Composable
private fun AiControlCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    val context = LocalContext.current
    var aiControl by remember { mutableStateOf(vm.settings.aiControlEnabled()) }
    var mirror by remember { mutableStateOf(vm.settings.mirrorEnabled()) }
    var confirm by remember { mutableStateOf(vm.settings.confirmBeforeControl()) }
    // ⭐ v1.1.0 #1：无障碍权限引导（首次开启且服务未开时弹引导对话框 + 跳系统设置）
    var showGuide by remember { mutableStateOf(false) }
    var accessibilityGranted by remember {
        mutableStateOf(PermissionHelper.isAccessibilityEnabled(context))
    }

    LaunchedEffect(revision) {
        aiControl = vm.settings.aiControlEnabled()
        mirror = vm.settings.mirrorEnabled()
        confirm = vm.settings.confirmBeforeControl()
        accessibilityGranted = PermissionHelper.isAccessibilityEnabled(context)
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ai_control_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.ai_control_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AiControlRow(
                title = stringResource(R.string.ai_control_toggle),
                desc = if (aiControl && !accessibilityGranted)
                    stringResource(R.string.ai_control_not_enabled)
                else stringResource(R.string.ai_control_toggle_desc),
                checked = aiControl,
                onChange = { v ->
                    vm.settings.setAiControlEnabled(v); aiControl = v
                    // ⭐ v1.1.0 #1：首次开启且无障碍服务未开 → 弹引导
                    if (v && !accessibilityGranted) showGuide = true
                },
            )
            AiControlRow(
                title = stringResource(R.string.ai_control_mirror),
                desc = stringResource(R.string.ai_control_mirror_desc),
                checked = mirror,
                onChange = { vm.settings.setMirrorEnabled(it); mirror = it },
            )
            AiControlRow(
                title = stringResource(R.string.ai_control_confirm),
                desc = stringResource(R.string.ai_control_confirm_desc),
                checked = confirm,
                onChange = { vm.settings.setConfirmBeforeControl(it); confirm = it },
            )
        }
    }

        // ⭐ v1.1.0 #1：无障碍权限引导对话框
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = {
                Text(stringResource(R.string.a11y_guide_title), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.a11y_guide_body),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PermissionHelper.openAccessibilitySettings(context as? Activity ?: return@TextButton)
                    showGuide = false
                }) {
                    Text(stringResource(R.string.a11y_guide_go), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGuide = false }) {
                    Text(stringResource(R.string.a11y_guide_later), fontSize = 13.sp)
                }
            },
        )
    }
}

@Composable
private fun AiControlRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ==================== ⭐ v1.1.0 #2：Shizuku 提权 ====================

@Composable
private fun ShizukuCard(vm: MainViewModel, context: android.content.Context) {
    var installed by remember { mutableStateOf(com.ai.android.service.ShizukuManager.isInstalled(context)) }
    var available by remember { mutableStateOf(com.ai.android.service.ShizukuManager.isAvailable(context)) }

        // ⭐ v1.2.0 #1：binder 可能延迟到达（Shizuku 启动 / 授权后），周期性重新检测，
    //    让卡片从「已装未授权」自动跳到「已授权」，无需重进设置页。
    LaunchedEffect(Unit) {
        while (true) {
            installed = com.ai.android.service.ShizukuManager.isInstalled(context)
            available = com.ai.android.service.ShizukuManager.isAvailable(context)
            // setShizukuEnabled 内部只在值变化时 bump，重复调用无副作用
            runCatching { vm.settings.setShizukuEnabled(available) }
            delay(3000)
        }
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.shizuku_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.shizuku_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

                        when {
                !installed -> {
                    Text(stringResource(R.string.shizuku_not_installed),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = {
                        com.ai.android.service.ShizukuManager.ensureAuthorized(context)
                    }) { Text(stringResource(R.string.shizuku_request), fontSize = 12.sp) }
                }
                !available -> {
                    Text(stringResource(R.string.shizuku_installed),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = {
                        // 已装但未授权/服务不可达：请求 Shizuku 授权（本 App 会出现在 Shizuku 可授权列表）
                        com.ai.android.service.ShizukuManager.ensureAuthorized(context)
                        available = com.ai.android.service.ShizukuManager.isAvailable(context)
                        vm.settings.setShizukuEnabled(available)
                    }) { Text(stringResource(R.string.shizuku_request), fontSize = 12.sp) }
                    // ⭐ v1.2.0-hotfix #2：Shizuku 授权排障提示（列表看不到本 App / 授权不生效时）
                    TroubleshootingTip()
                }
                else -> {
                    Text(stringResource(R.string.shizuku_granted),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.shizuku_running_as),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * ⭐ v1.2.0-hotfix #2：Shizuku 授权排障提示（仅当「已装未授权」时显示在卡片里）。
 * 列出 4 步操作，对应 docs/Shizuku排障指南.md。
 */
@Composable
private fun TroubleshootingTip() {
    var showHelp by remember { mutableStateOf(false) }
    TextButton(onClick = { showHelp = true }) {
        Text("授权列表看不到本 App？排障指引", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Shizuku 授权排障（4 步）", fontSize = 14.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. 确认 Shizuku 服务已启动：打开 Shizuku 主界面，首页显示「已启动 / Running」。" +
                        "（adb 模式：连电脑跑 adb 命令或无线调试；root 模式：授权 Shizuku 后它自动拉起）", fontSize = 12.sp)
                    Text("2. 确认本 App 已安装成功：终端（Termux/adb）里跑 pm list packages | grep ai.android，能看到 com.ai.android 才说明装上了", fontSize = 12.sp)
                    Text("3. 确认 Shizuku 版本 ≥ 13（API_V23 权限名）：旧版 11.x 用 API_VXX，识别不到本 App。Shizuku App 关于页可看版本", fontSize = 12.sp)
                    Text("4. 强杀 Shizuku 再重启：系统「最近任务」里划掉 Shizuku → 重新打开 Shizuku App → 授权管理列表刷新，本 App 应出现", fontSize = 12.sp)
                    Text("若 4 步都做完仍不显示：到 Shizuku App 里找「手动添加」功能（部分版本支持）；或升级 Shizuku 到最新版（13.1.5+）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("知道了") }
            },
        )
    }
}

// ==================== ⭐ v1.1.0 #4：语言 / 语言包 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageCard(vm: MainViewModel, context: android.content.Context) {
    val revision by vm.settings.revision.collectAsState()
    var lang by remember { mutableStateOf(vm.settings.appLanguage()) }
    var hasPack by remember { mutableStateOf(com.ai.android.i18n.I18nManager.hasPack()) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = com.ai.android.i18n.I18nManager.importPack(uri, context)
        hasPack = com.ai.android.i18n.I18nManager.hasPack()
        Toast.makeText(context,
            if (ok) "语言包已导入" else "语言包导入失败（需为 key→翻译 的 JSON 对象）",
            Toast.LENGTH_LONG).show()
        (context as? Activity)?.let { com.ai.android.i18n.I18nManager.applyLocale(it) }
    }

    LaunchedEffect(revision, Unit) {
        lang = vm.settings.appLanguage()
        hasPack = com.ai.android.i18n.I18nManager.hasPack()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lang_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.lang_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.ai.android.i18n.I18nManager.supportedLanguages().forEach { (code, label) ->
                    FilterChip(
                        selected = lang == code,
                        onClick = {
                            vm.settings.setAppLanguage(code)
                            lang = code
                            (context as? Activity)?.let {
                                com.ai.android.i18n.I18nManager.applyLocale(it)
                            }
                        },
                        label = {
                            Text(
                                if (code == com.ai.android.i18n.I18nManager.FOLLOW)
                                    stringResource(R.string.lang_follow) else label,
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    openDocument.launch(arrayOf("application/json"))
                },
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.lang_import_pack), fontSize = 12.sp)
            }

            if (hasPack) {
                TextButton(onClick = {
                    com.ai.android.i18n.I18nManager.clearPack(context)
                    hasPack = false
                    (context as? Activity)?.let { com.ai.android.i18n.I18nManager.applyLocale(it) }
                    Toast.makeText(context, "已清除语言包", Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.lang_remove_pack), fontSize = 12.sp)
                }
            }
        }
    }
}

// ==================== Token 统计（beta5） ====================

@Composable
private fun TokenStatsCard(vm: MainViewModel, context: android.content.Context) {
    var totals by remember { mutableStateOf(vm.tokenTotalsForCurrent()) }
    var exporting by remember { mutableStateOf(false) }
    val tokenCleared = stringResource(R.string.token_cleared)

    // SAF 导出
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        exporting = false
        if (uri != null) {
            runCatching {
                val jsonStr = vm.exportTokenJson(vm.current.value?.id.orEmpty())
                if (jsonStr == null) {
                    Toast.makeText(context, "当前对话没有可导出的 Token 统计", Toast.LENGTH_SHORT).show()
                } else {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonStr.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "Token 统计已导出", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            totals = vm.tokenTotalsForCurrent()
            delay(1500)
        }
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.token_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.token_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TokenStatItem(stringResource(R.string.token_input), totals.promptTokens)
                TokenStatItem(stringResource(R.string.token_output), totals.completionTokens)
                TokenStatItem(stringResource(R.string.token_cache), totals.cacheHitTokens)
                TokenStatItem(stringResource(R.string.token_total), totals.totalTokens)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    exporting = true
                    createDocument.launch("token-stats-${System.currentTimeMillis()}.json")
                }) { Text(stringResource(R.string.token_export), fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    val cur = vm.current.value ?: return@OutlinedButton
                    vm.clearTokenStats(cur.id)
                    totals = vm.tokenTotalsForCurrent()
                    Toast.makeText(context, tokenCleared, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.token_clear), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun TokenStatItem(label: String, value: Int) {
    Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ==================== 插件管理（beta5） ====================

@Composable
private fun PluginManagerCard(vm: MainViewModel, context: android.content.Context) {
    val revision by vm.settings.revision.collectAsState()
    var plugins by remember { mutableStateOf(PluginManager.list(context).toList()) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val msg = PluginManager.importZip(context, uri)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            plugins = PluginManager.list(context).toList()
            // ⭐ 让悬浮窗重建挂件区 + 刷新 PluginRegistry
            com.ai.android.MainApp.instance.pluginRegistry.refresh()
            FloatingService.rebuildWidgets(context)
        }.onFailure {
            Toast.makeText(context, "导入出错：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) { plugins = PluginManager.list(context).toList() }
    LaunchedEffect(revision) { plugins = PluginManager.list(context).toList() }

            Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plugin_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.plugin_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { openDocument.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.plugin_import), fontSize = 13.sp) }

            if (plugins.isEmpty()) {
                Text(stringResource(R.string.plugin_empty), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                plugins.forEach { p ->
                    val enabled = PluginManager.isEnabled(context, p.id)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 13.sp)
                            Text(
                                "id=${p.id} · v${p.version} · 能力: ${p.capabilities.joinToString(",")}",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { v ->
                                PluginManager.setEnabled(context, p.id, v)
                                plugins = PluginManager.list(context).toList()
                                runCatching {
                                    com.ai.android.MainApp.instance.pluginRegistry.refresh()
                                    FloatingService.rebuildWidgets(context)
                                }
                            },
                        )
                        IconButton(onClick = {
                            PluginManager.remove(context, p.id)
                            plugins = PluginManager.list(context).toList()
                            runCatching {
                                com.ai.android.MainApp.instance.pluginRegistry.refresh()
                                FloatingService.rebuildWidgets(context)
                            }
                            Toast.makeText(context, "已删除 ${p.name}", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Delete, "删除", Modifier.width(16.dp))
                        }
                    }
                }
            }
        }
    }
}