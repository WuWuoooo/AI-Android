package com.ai.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * ⭐ v1.2.0-next #10：自绘弹出菜单通用组件（替代原生 DropdownMenu / 居中 AlertDialog）。
 *
 * 规格：
 *  - 圆角卡片（14dp）+ 阴影 + 1dp 描边，配色跟随主题（surface / primary / onSurface）
 *  - 选中项：primaryContainer 高亮底 + 主题色对勾（Icons.Default.Check，core 图标无坑）
 *  - 每项支持：主标题 + 副标题 + 可选 leading 图标
 *  - 可选标题行 / 空态提示 / 内部滚动（项多时封顶 280dp）
 *
 * 调用方负责（组件本身不含遮罩/手势，保证可复用）：
 *  - 锚定位置：align / offset / zIndex
 *  - 外部关闭：scrim 点击 / toggle / BackHandler
 *
 * 逐个替换路线（避免大爆炸）：先替换 InputBar 加号菜单 + ChatScreen 模型切换，
 * 后续可把 SettingsScreen / MessageBubble 里的原生菜单逐步换成本组件。
 */

/** 自绘菜单项（AppMenuPanel 的输入模型） */
data class AppMenuItem(
    val label: String,
    val sub: String = "",
    val icon: ImageVector? = null,
    val active: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
fun AppMenuPanel(
    items: List<AppMenuItem>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    emptyText: String? = null,
    scrollable: Boolean = true,
    maxWidth: Dp = 300.dp,
    /**
     * ⭐ 第五轮：true=自带圆角卡片装饰（shadow + surface + border，独立使用）。
     * false=只画内容（无 Card 装饰），交给外层容器（如 material3 DropdownMenu）提供圆角/背景/动画，
     *   避免 DropdownMenu 自带框 + AppMenuPanel 自带框的"双框"。
     */
    chrome: Boolean = true,
) {
    Column(
        modifier = modifier
            .widthIn(max = maxWidth)
            .then(
                if (chrome) Modifier
                    .shadow(8.dp, RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp),
                    )
                else Modifier
            )
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (scrollable) Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                else Modifier
            )
            .padding(vertical = 4.dp),
    ) {
        if (title != null) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        if (items.isEmpty()) {
            if (emptyText != null) {
                Text(
                    emptyText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else {
            items.forEachIndexed { idx, it ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (it.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else Color.Transparent
                        )
                        .then(if (it.enabled) Modifier.clickable { onPick(idx) } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    it.icon?.let { ic ->
                        Icon(
                            ic, null,
                            Modifier.size(18.dp),
                            tint = if (it.active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            it.label,
                            fontSize = 14.sp,
                            fontWeight = if (it.active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (it.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            maxLines = 1,
                        )
                        if (it.sub.isNotEmpty()) {
                            Text(
                                it.sub,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                                        if (it.active) {
                        Icon(
                            Icons.Default.Check, null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * ⭐ 第五轮 #7：自绘对话框（替代原生 material3 AlertDialog，统一圆角卡片风格）。
 * 规格：圆角 20dp + 阴影 + 1dp 描边 + surface 配色；标题 primary 色；按钮区右对齐。
 * 用法：
 * ```
 * AppDialog(onDismissRequest = {...}, title = "标题",
 *     confirmText = "保存", onConfirm = {...},
 *     dismissText = "取消", onDismiss = {...}) {
 *     // 内容（ColumnScope）
 * }
 * ```
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    /** 额外按钮（放在「取消/确认」左边，例如「保存并重新生成」） */
    extraButtons: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(20.dp),
                )
                .clip(RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
            if (confirmText != null || dismissText != null || extraButtons != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    extraButtons?.invoke()
                    dismissText?.let {
                        TextButton(onClick = { onDismiss?.invoke() }) { Text(it, fontSize = 13.sp) }
                    }
                    confirmText?.let {
                        TextButton(onClick = { onConfirm?.invoke() }, enabled = confirmEnabled) {
                            Text(it, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
