package com.ai.android.ui.settings

import android.app.Activity
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.ai.android.provider.AIProvider
import com.ai.android.provider.ProviderConfig
import com.ai.android.provider.SearchManager
import com.ai.android.skills.SkillDefinition
import com.ai.android.util.PermissionHelper
import kotlinx.coroutines.delay
import java.util.UUID
import com.ai.android.termux.BootstrapInstaller
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                TopAppBar(
                    title = { Text("设置") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Provider
            item { SectionTitle("模型服务（Provider）") }
            items(configs, key = { it.id }) { cfg ->
                ProviderCard(
                    cfg = cfg,
                    isActive = cfg.id == activeId,
                    onActivate = {
                        vm.settings.setActive(cfg.id)
                        activeId = vm.settings.activeId()
                        Toast.makeText(context, "已切换到 ${cfg.label}", Toast.LENGTH_SHORT).show()
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
                ) { Text("＋ 添加 Provider") }
            }

            // 多模态能力绑定
            item { SectionTitle("多模态能力绑定") }
            item { MultimodalBindingCard(vm = vm, configs = configs) }

            // Agent 参数
            item { SectionTitle("Agent 参数") }
            item { AgentParamsCard(vm) }

// ========== 文件读取 ==========
item { SectionTitle("文件读取") }
item { ReadLimitCard(vm) }

// ========== 终端环境 ==========
item { SectionTitle("Termux 环境") }
item { TermuxCard(vm, context) }

            // 悬浮窗开关
            item { SectionTitle("显示") }
            item { FloatingToggleCard(vm) }

            // 项目文件夹管理
            item { SectionTitle("项目文件夹") }
            item { ProjectManagerCard(vm) }

            // 长期记忆
            item { SectionTitle("长期记忆") }
            item { MemoryManagerCard(vm) }

            // 技能
            item { SectionTitle("技能（Skills）") }
            items(vm.skills.all()) { skill ->
                SkillCard(skill) {
                    val path = runCatching { vm.skills.export(skill) }.getOrNull()
                    Toast.makeText(
                        context,
                        if (path != null) "已导出到 $path" else "导出失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            item {
                Text(
                    "把 SKILL.md 放进 /sdcard/AiAndroid/skills/ 可自定义技能，重启 App 后生效。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 权限
            item { SectionTitle("权限") }
            item {
                PermissionCard(
                    status = permStatus,
                    onRequestStorage = { activity?.let { PermissionHelper.requestStorage(it) } },
                    onRequestNotification = { activity?.let { PermissionHelper.requestNotification(it) } },
                    onRequestOverlay = { activity?.let { PermissionHelper.requestOverlay(it) } },
                    onRequestAccessibility = { activity?.let { PermissionHelper.openAccessibilitySettings(it) } },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
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

@Composable
private fun ReadLimitCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var maxChars by remember { mutableStateOf(vm.settings.maxReadChars()) }
    LaunchedEffect(revision) { maxChars = vm.settings.maxReadChars() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 读取文件最大字符数", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "超过此长度会被截断。设为 0 表示不限制（大文件可能超出模型上下文）。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 50_000, 100_000, 200_000, 500_000).forEach { v ->
                    FilterChip(
                        selected = maxChars == v,
                        onClick = { vm.settings.setMaxReadChars(v) },
                        label = { Text(if (v == 0) "不限" else "${v / 1000}K", fontSize = 11.sp) },
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

    val scope = rememberCoroutineScope()

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Termux 环境（pkg / apt / python）",
                fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "安装后可在内置终端使用 Termux 生态（pkg install xxx、pip、apt 等）。首次安装约 30MB，耗时 10-30 秒。",
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
                    ) { Text(if (installing) "安装中…" else "安装 Termux 环境") }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val ok = BootstrapInstaller.uninstall(context)
                                installed = false
                                statusText = if (ok) "已卸载" else "卸载失败"
                            }
                        },
                    ) { Text("卸载") }
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
    LaunchedEffect(revision) {
        pathPrefix = vm.settings.terminalPathPrefix()
        home = vm.settings.terminalHome()
        initScript = vm.settings.terminalInitScript()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("终端环境", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "把 Termux 的 \$PREFIX/bin 路径填到 PATH 前缀，即可在内置终端使用 pkg / pip / apt 等命令。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = home, onValueChange = { home = it; vm.settings.setTerminalHome(it) },
                label = { Text("HOME 目录", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pathPrefix,
                onValueChange = { pathPrefix = it; vm.settings.setTerminalPathPrefix(it) },
                label = { Text("PATH 前缀（: 分隔）", fontSize = 11.sp) },
                placeholder = { Text("/data/data/com.termux/files/usr/bin", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = initScript,
                onValueChange = { initScript = it; vm.settings.setTerminalInitScript(it) },
                label = { Text("启动初始化脚本（每行一条命令）", fontSize = 11.sp) },
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
                    if (cfg.apiKey.isBlank()) "未填 API Key" else "Key: ${cfg.apiKey.take(6)}••••",
                    fontSize = 10.sp,
                    color = if (cfg.apiKey.isBlank()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
        }
    }
}

// ==================== 多模态绑定 ====================

@Composable
private fun MultimodalBindingCard(vm: MainViewModel, configs: List<ProviderConfig>) {
    val revision by vm.settings.revision.collectAsState()

    val caps = listOf(
        "IMAGE" to "文生图",
        "VISION" to "识图",
        "TTS" to "语音合成",
        "ASR" to "语音识别",
        "SEARCH" to "联网搜索",
    )

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "为每种能力指定使用的 Provider。未指定时自动选择 active 或第一个具备该能力的 Provider。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "当前搜索：${SearchManager.currentName()}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary,
            )

            caps.forEach { (cap, label) ->
                var expanded by remember { mutableStateOf(false) }
                val boundId = vm.settings.multimodalBinding(cap)
                val bound = configs.firstOrNull { it.id == boundId }
                val display = bound?.let { "${it.label} · ${it.model}" }
                    ?: if (configs.none { cap in it.capabilities }) "（无 Provider 支持）" else "自动"

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(display, fontSize = 12.sp, maxLines = 1)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("自动", fontSize = 13.sp) },
                                onClick = {
                                    vm.settings.setMultimodalBinding(cap, "")
                                    expanded = false
                                },
                            )
                            configs.filter { cap in it.capabilities }.forEach { cfg ->
                                DropdownMenuItem(
                                    text = { Text("${cfg.label} · ${cfg.model}", fontSize = 13.sp) },
                                    onClick = {
                                        vm.settings.setMultimodalBinding(cap, cfg.id)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
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
            Text("最大工具循环: ${cfg.maxRounds} 次", fontSize = 13.sp)
            Slider(
                value = cfg.maxRounds.toFloat(),
                onValueChange = { vm.settings.setMaxRounds(it.toInt()) },
                valueRange = 1f..100f,
            )
            Text("温度: ${String.format("%.1f", cfg.temperature)}", fontSize = 13.sp)
            Slider(
                value = cfg.temperature,
                onValueChange = { vm.settings.setTemperature(it) },
                valueRange = 0f..2f,
            )
            Text("推理强度", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "默认", "low" to "低", "medium" to "中", "high" to "高").forEach { (v, label) ->
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
            text = if (current.isBlank()) "自定义系统提示词：未设置"
            else "自定义系统提示词：${current.take(20)}…",
            fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { editing = true }) { Text("编辑", fontSize = 12.sp) }
    }
    if (editing) {
        var text by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("自定义系统提示词") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text("例如：我是学生，请用简单语言回答…", fontSize = 12.sp) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.settings.setSystemPromptExtra(text)
                    editing = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
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
                Text("后台悬浮球", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Agent 运行时在屏幕边缘显示状态小球，点击展开查看进度",
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

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "把相关会话归入项目文件夹，方便分类管理。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (projects.isEmpty()) {
                Text("（暂无项目）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                projects.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { renaming = p }) {
                            Icon(Icons.Default.Edit, "重命名", Modifier.width(16.dp))
                        }
                        IconButton(onClick = {
                            vm.deleteProject(p.id)
                        }) {
                            Icon(Icons.Default.Delete, "删除", Modifier.width(16.dp))
                        }
                    }
                }
            }

            TextButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, null, Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建项目", fontSize = 12.sp)
            }
        }
    }

    if (creating) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建项目") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("项目名", fontSize = 13.sp) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createProject(text.trim().ifBlank { "未命名项目" })
                        creating = false
                    },
                    enabled = text.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }

    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名项目") },
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
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
}

// ==================== 长期记忆 ====================

@Composable
private fun MemoryManagerCard(vm: MainViewModel) {
    val memories by vm.memoryList.collectAsState()
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshMemoryList() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "AI 通过 save_memory 工具自动写入，也可手动添加。跨会话保留。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (memories.isEmpty()) {
                Text("（暂无记忆）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                memories.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(k, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (v.length > 120) v.take(120) + "…" else v,
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.deleteMemory(k) }) {
                            Icon(Icons.Default.Delete, "删除", Modifier.width(16.dp))
                        }
                    }
                }
            }

            TextButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, null, Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("手动添加", fontSize = 12.sp)
            }
        }
    }

    if (adding) {
        var key by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加记忆") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("标识（英文短词）", fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = content, onValueChange = { content = it },
                        label = { Text("内容", fontSize = 12.sp) },
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
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }
}

// ==================== 技能 ====================

@Composable
private fun SkillCard(skill: SkillDefinition, onExport: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(skill.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(skill.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "触发词: ${skill.triggers.joinToString(", ")}",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onExport) { Text("分享", fontSize = 12.sp) }
        }
    }
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
            PermissionRow("存储权限（文件读写）", status.storage, onRequestStorage)
            PermissionRow("通知权限（任务提醒）", status.notification, onRequestNotification)
            PermissionRow("悬浮窗（后台状态球）", status.overlay, onRequestOverlay)
            PermissionRow("无障碍服务（手机操控）", status.accessibility, onRequestAccessibility)
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = if (granted) "已开启" else "未开启", fontSize = 11.sp,
            color = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!granted) {
            TextButton(onClick = onRequest) { Text("去开启", fontSize = 12.sp) }
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
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var protocol by remember { mutableStateOf(initial?.protocol ?: AIProvider.Protocol.OPENAI) }
    var caps by remember { mutableStateOf(initial?.capabilities?.toSet() ?: setOf("CHAT")) }
    var extraBodyJson by remember { mutableStateOf(initial?.extraBodyJson ?: "") }
    var extraHeaders by remember { mutableStateOf(initial?.extraHeaders ?: "") }
    var advOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加 Provider" else "编辑 Provider") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("名称", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = protocol == AIProvider.Protocol.OPENAI,
                        onClick = { protocol = AIProvider.Protocol.OPENAI },
                        label = { Text("OpenAI 兼容", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = protocol == AIProvider.Protocol.ANTHROPIC,
                        onClick = { protocol = AIProvider.Protocol.ANTHROPIC },
                        label = { Text("Anthropic", fontSize = 11.sp) },
                    )
                }
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型名", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                Text("支持的能力", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                CapabilityChips(
                    selected = caps,
                    onToggle = { c -> caps = if (c in caps) caps - c else caps + c },
                )

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
                    Text("请求体设置（高级）", fontSize = 12.sp)
                }

                if (advOpen) {
                    OutlinedTextField(
                        value = extraBodyJson, onValueChange = { extraBodyJson = it },
                        label = { Text("自定义请求体 JSON", fontSize = 11.sp) },
                        placeholder = { Text("{\"top_p\":0.9}", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    OutlinedTextField(
                        value = extraHeaders, onValueChange = { extraHeaders = it },
                        label = { Text("自定义请求头（每行 Key: Value）", fontSize = 11.sp) },
                        placeholder = { Text("X-Custom: abc", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    Text(
                        "请求体 JSON 会 merge 进每次请求；键冲突时以你填的为准。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ProviderConfig(
                            id = initial?.id ?: UUID.randomUUID().toString().take(8),
                            label = label.ifBlank { "未命名" },
                            protocol = protocol,
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            capabilities = caps.toList().ifEmpty { listOf("CHAT") },
                            extraBodyJson = extraBodyJson.trim(),
                            extraHeaders = extraHeaders.trim(),
                        )
                    )
                },
                enabled = label.isNotBlank() && baseUrl.length > 8,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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