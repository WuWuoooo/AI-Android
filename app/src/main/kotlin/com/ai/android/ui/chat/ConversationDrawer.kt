package com.ai.android.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.model.Conversation
import com.ai.android.ui.components.AppMenuPanel
import com.ai.android.ui.components.AppMenuItem
import java.util.Calendar

/**
 * ⭐ v1.2.0 对话历史侧边面板（完全按图2 风格重做）：
 *
 * 布局（垂直 Flex，无底部区域）：
 *  - 顶部固定：搜索框（大圆角胶囊），置顶，不带 X 关闭 / 不带筛选标题栏
 *  - 中间自适应：可滚动列表（分组 + 对话项，宽松间距）
 *
 * 列表项（极简、左对齐、留白）：
 *  - 只有标题单行，过长省略号截断
 *  - 选中条：浅紫圆角背景 + 左侧 4dp 紫色高亮指示条 + 加粗 + 右侧 ⋯ 菜单
 *  - 未选中：白底默认字色
 *  - 项间距宽松（上下 padding）
 *
 * 分组（按 updatedAt）：今天 / 昨天 / 近 7 天 / 近 30 天 / 更早（没内容不显示）；
 * 组头是小字灰标题，第一组右侧带排序图标（点击切升/降序）。
 *
 * 面板高度由父级（ChatScreen）铺满到导航栏，列表直接到底，不受主界面输入框影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDrawer(
    vm: MainViewModel,
    currentId: String?,
    onClose: () -> Unit,
) {
        val context = LocalContext.current
    val conversations by vm.conversations.collectAsState()
    val projects by vm.projects.collectAsState()

        var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var sortAsc by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var exportConvId by remember { mutableStateOf<String?>(null) }

    // ⭐ #8 多选模式：点"今天"右侧按钮进入多选；底栏提供删除/置顶/取消
    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

        // ⭐ 第五轮 #3：项目分区——可展开的项目集合（记住哪些项目当前展开）
    var expandedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // ⭐ 第五轮 #3：正在「移入项目」的对话（弹项目选择框）
    var movingConv by remember { mutableStateOf<Conversation?>(null) }

    // 导出对话（SAF）
    val createDocument = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val convId = exportConvId
        exportConvId = null
        if (uri != null && convId != null) {
            runCatching {
                val c = conversations.firstOrNull { it.id == convId } ?: return@runCatching
                val json = vm.exportConversationJson(c.id) ?: return@runCatching
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(context, "已导出对话：${c.title}", Toast.LENGTH_SHORT).show()
            }.onFailure {
                                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
                        }
        }
    }

    // 时间分段
    val (todayStart, dayMs) = remember {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        c.timeInMillis to 86400000L
    }
    val yesterdayStart = todayStart - dayMs
    val day7Start = todayStart - 6 * dayMs
    val day30Start = todayStart - 29 * dayMs

    val filtered = remember(conversations, query) {
        conversations.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .let { list -> if (sortAsc) list.sortedBy { it.updatedAt } else list.sortedByDescending { it.updatedAt } }
    }

    // 分组（没内容的组不显示）
    val groups = remember(filtered) {
        val today = mutableListOf<Conversation>()
        val yesterday = mutableListOf<Conversation>()
        val d7 = mutableListOf<Conversation>()
        val d30 = mutableListOf<Conversation>()
        val earlier = mutableListOf<Conversation>()
        filtered.forEach { c ->
            when {
                c.updatedAt >= todayStart -> today.add(c)
                c.updatedAt >= yesterdayStart -> yesterday.add(c)
                c.updatedAt >= day7Start -> d7.add(c)
                c.updatedAt >= day30Start -> d30.add(c)
                else -> earlier.add(c)
            }
        }
        listOf(
            "今天" to today,
            "昨天" to yesterday,
            "近 7 天" to d7,
            "近 30 天" to d30,
            "更早" to earlier,
        ).filter { it.second.isNotEmpty() }
    }

        // ⭐ 第五轮 #1：抽屉配色随主题（原写死紫色，切风格/深浅色不跟着变）
    val accent = MaterialTheme.colorScheme.primary
    val accentBg = MaterialTheme.colorScheme.primaryContainer
    val gray = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

            // ⭐ 垂直布局：顶部固定搜索栏 + 中间自适应可滚动列表，无底部区域
    // 最外层加状态栏安全区 padding：背景铺到屏幕物理顶（含状态栏后面），
    // 内容（搜索框）从状态栏下方开始，避开状态栏
        Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // ===== 顶部固定：搜索框（胶囊大圆角），置顶，无 X / 无筛选标题栏 =====
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索对话内容…", fontSize = 14.sp, color = Color.Gray) },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Color.Gray)
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )
        }

        // ===== 中间自适应：可滚动列表（分组 + 对话项，宽松间距）=====
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
                        if (filtered.isEmpty()) {
                item {
                    Text(
                        "没有找到对话", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            // ⭐ 第五轮 #3：项目分区（搜索框下、时间分组上）。每个项目可展开，看项目内对话并进入。
            if (projects.isNotEmpty()) {
                item(key = "projects_header") {
                    Text(
                        "项目", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                projects.forEach { proj ->
                    val projConvs = conversations
                        .filter { it.projectId == proj.id }
                        .let { list -> if (sortAsc) list.sortedBy { it.updatedAt } else list.sortedByDescending { it.updatedAt } }
                        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
                    val expanded = proj.id in expandedProjectIds
                    item(key = "proj_${proj.id}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (expanded) accentBg else Color.Transparent)
                                .clickable {
                                    expandedProjectIds = if (expanded) expandedProjectIds - proj.id
                                    else expandedProjectIds + proj.id
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                null, Modifier.size(18.dp), tint = accent,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                proj.name, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${projConvs.size}", fontSize = 11.sp,
                                color = gray, modifier = Modifier.padding(end = 6.dp),
                            )
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, Modifier.size(18.dp), tint = gray,
                            )
                        }
                    }
                    if (expanded) {
                        items(projConvs, key = { "pcc_${proj.id}_${it.id}" }) { c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (c.id == currentId) accentBg else Color.Transparent
                                    )
                                    .clickable {
                                        vm.switchConversation(c.id); onClose()
                                    }
                                    .padding(start = 28.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    c.title.ifBlank { "未命名" },
                                    fontSize = 13.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (c.id == currentId) accent else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (c.id == currentId) FontWeight.Medium else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                item(key = "projects_divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            groups.forEachIndexed { gi, (label, items) ->
                                                // 组头：小字灰标题（左）+ 排序/多选按钮（右，仅第一组）
                item(key = "h_$label") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = gray,
                            modifier = Modifier.weight(1f),
                        )
                        if (gi == 0) {
                            // ⭐ #8：点「今天」右侧按钮 → 进入多选模式（再点退出）
                            IconButton(
                                onClick = {
                                    multiSelect = !multiSelect
                                    if (!multiSelect) selectedIds = emptySet()
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                                                Icon(
                                    if (multiSelect) Icons.Default.Check else Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = if (multiSelect) "退出多选" else "排序/多选",
                                    Modifier.size(18.dp),
                                    tint = if (multiSelect) accent else gray,
                                )
                            }
                        }
                    }
                }
                                // 列表项（宽松间距 + 选中态；#8 多选模式下显示勾选框）
                items(items, key = { it.id }) { c ->
                    DrawerItemRow(
                        conv = c,
                        isCurrent = c.id == currentId,
                        menuOpen = menuFor == c.id,
                        onMenuOpenChange = { menuFor = if (it) c.id else null },
                        onOpen = {
                            if (multiSelect) {
                                selectedIds = if (c.id in selectedIds) selectedIds - c.id
                                else selectedIds + c.id
                            } else {
                                vm.switchConversation(c.id); onClose()
                            }
                        },
                        onRename = { renaming = c; menuFor = null },
                        onExport = {
                            exportConvId = c.id; menuFor = null
                            createDocument.launch(vm.suggestedExportFileName(c))
                        },
                                                onDelete = {
                            if (c.id != currentId) vm.deleteConversation(c.id)
                            menuFor = null
                        },
                        onMoveToProject = { movingConv = c; menuFor = null },
                                                multiSelect = multiSelect,
                        isSelected = c.id in selectedIds,
                    )
                }
            }
        }

                // ⭐ #8 多选模式底部操作栏：删除 / 置顶 / 取消（在外层 Column、LazyColumn 之后）
        // ⭐ #1：加过渡动画（从底部滑入 + 淡入），进出都有动画
        AnimatedVisibility(
            visible = multiSelect,
            enter = slideInVertically(animationSpec = tween(250), initialOffsetY = { it }) +
                fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(animationSpec = tween(250), targetOffsetY = { it }) +
                fadeOut(animationSpec = tween(200)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已选 ${selectedIds.size} 条",
                    fontSize = 13.sp,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selectedIds.forEach { id -> if (id != currentId) vm.deleteConversation(id) }
                    Toast.makeText(context, "已删除 ${selectedIds.size} 条", Toast.LENGTH_SHORT).show()
                    multiSelect = false; selectedIds = emptySet()
                }) { Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = {
                    selectedIds.forEach { id ->
                        val c = conversations.firstOrNull { it.id == id }
                        if (c != null && !c.pinned) vm.togglePin(id)
                    }
                    Toast.makeText(context, "已置顶 ${selectedIds.size} 条", Toast.LENGTH_SHORT).show()
                    multiSelect = false; selectedIds = emptySet()
                }) { Text("置顶", fontSize = 13.sp, color = accent) }
                TextButton(onClick = { multiSelect = false; selectedIds = emptySet() }) {
                    Text("取消", fontSize = 13.sp)
                }
            }
            HorizontalDivider()
        }
    }

    // 重命名对话框
    renaming?.let { c ->
        var text by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            },
                        confirmButton = {
                TextButton(onClick = {
                    vm.renameConversation(c.id, text.trim().ifBlank { c.title })
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    // ⭐ 第五轮 #3：移入项目选择框（列出所有项目 + 「移出项目」）
    movingConv?.let { c ->
        AlertDialog(
            onDismissRequest = { movingConv = null },
            title = { Text("移入项目", fontSize = 14.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (projects.isEmpty()) {
                        Text("还没有项目，请到 设置 → 对话管理 → 项目管理 里新建",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        projects.forEach { p ->
                            TextButton(
                                onClick = {
                                    vm.moveConversationToProject(c.id, p.id)
                                    expandedProjectIds = expandedProjectIds + p.id
                                    movingConv = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    p.name + if (c.projectId == p.id) "（当前）" else "",
                                    fontSize = 13.sp,
                                    color = if (c.projectId == p.id) accent else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    if (c.projectId.isNotBlank()) {
                        TextButton(
                            onClick = {
                                vm.moveConversationToProject(c.id, "")
                                movingConv = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("移出项目", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingConv = null }) { Text("取消", fontSize = 13.sp) } },
        )
    }
}

/**
 * 列表项（极简、左对齐、宽松留白）：
 *  - 只有标题单行（过长省略号截断）
 *  - 选中：浅紫圆角背景 + 左侧 4dp 紫色高亮指示条 + 加粗 + 右侧 ⋯ 菜单
 *  - 未选中：白底默认字色
 */
@Composable
private fun DrawerItemRow(
    conv: Conversation,
    isCurrent: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    /** ⭐ 第五轮 #3：移入/移出项目（点菜单里「移入项目」触发） */
    onMoveToProject: () -> Unit = {},
    multiSelect: Boolean = false,
    isSelected: Boolean = false,
) {
            // ⭐ 第五轮 #1：抽屉配色随主题（原写死紫色，切风格/深浅色不跟着变）
    val accent = MaterialTheme.colorScheme.primary
    val accentBg = MaterialTheme.colorScheme.primaryContainer
    val gray = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

        Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    multiSelect && isSelected -> accentBg
                    isCurrent -> accentBg
                    else -> Color.Transparent
                }
            )
            .clickable { onOpen() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⭐ #8 多选模式下左侧显示勾选框
        if (multiSelect) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onOpen() },
                modifier = Modifier.size(24.dp),
                colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = gray),
            )
            Spacer(Modifier.width(8.dp))
        }
        // 左侧高亮指示条（选中时显示，非多选模式）
        if (isCurrent && !multiSelect) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(10.dp))
        }

        // 标题（单行截断；选中加粗 + 深紫）
        Text(
            conv.title.ifBlank { "未命名" },
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (multiSelect && isSelected || isCurrent) accent else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (multiSelect && isSelected || isCurrent) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )

                                                // 右侧 ⋯ 菜单（非多选模式、选中条显示）
        // ⭐ 第五轮：⋯ 面板改 material3 DropdownMenu（锚定本按钮右下角 + 浮层不挤占 + 内建过渡动画 + wrap 不过长）
        if (isCurrent && !multiSelect) {
            Box {
                IconButton(
                    onClick = { onMenuOpenChange(true) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.MoreVert, "更多", Modifier.size(18.dp), tint = accent)
                }
                                                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                    modifier = Modifier.widthIn(min = 110.dp),
                    shape = RoundedCornerShape(10.dp),
                    content = {
                                                AppMenuPanel(
                            items = listOf(
                                AppMenuItem(label = "重命名"),
                                AppMenuItem(label = "移入项目"),
                                AppMenuItem(label = "导出对话"),
                                AppMenuItem(label = "删除"),
                            ),
                            onPick = { idx ->
                                onMenuOpenChange(false)
                                when (idx) {
                                    0 -> onRename()
                                    1 -> onMoveToProject()
                                    2 -> onExport()
                                    3 -> onDelete()
                                }
                            },
                            chrome = false,
                            title = null,
                        )
                    },
                )
            }
        }
    }
}
