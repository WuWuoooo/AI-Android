package com.ai.android.ui.chat

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⭐ v1.2.0-next #14 + #9：自绘全量相册（MediaStore 主路，替代系统 GetContent SAF 与旧的 File 双目录扫描）。
 *
 * 规格（QQ 发图体验）：
 *  - 顶部 Tab：「全部」（MediaStore 全量，跨 /sdcard/Pictures、/DCIM、/Download 等任意目录）
 *               「相册」（按 MediaStore BUCKET_DISPLAY_NAME 分组筛选）
 *  - 网格：LazyVerticalGrid 3 列，缩略图用 Coil（AsyncImage）加载 MediaStore URI
 *  - 勾选角标 + 底部「取消 / 确认（已选 N）」
 *  - 确认时读原图字节 → base64(NO_WRAP) → onConfirm
 *  - 权限：API 33+ 需 READ_MEDIA_IMAGES；旧版 READ_EXTERNAL_STORAGE。缺权限时给申请按钮
 */
private data class ImgItem(val id: Long, val uri: Uri, val album: String, val dateTaken: Long)

@Composable
fun ImagePickDrawer(
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------- 权限 ----------
    fun mediaPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

        // ---------- 数据（先声明，供 loadImages / requestPermission 使用，避免前向引用） ----------
    var images by remember { mutableStateOf<List<ImgItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }      // 0=全部  1=相册
    var album by remember { mutableStateOf<String?>("全部") }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun loadImages(ctx: Context, sink: (List<ImgItem>) -> Unit) {
        loading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { queryAllImages(ctx) }
            sink(list)
            loading = false
        }
    }

    var permGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, mediaPerm()) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permGranted = ok
        if (ok) loadImages(context, { images = it })
    }


    LaunchedEffect(Unit) {
        if (permGranted) loadImages(context, { images = it })
    }

    val albums = remember(images) {
        images.map { it.album }.distinct().sorted()
    }
    val visible = remember(images, tab, album) {
        when {
            tab == 1 && album != null && album != "全部" -> images.filter { it.album == album }
            else -> images
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.heightIn(max = 440.dp)) {
            // ===== 顶部：Tab（全部 / 相册）+ 关闭 =====
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0; selected = emptySet() },
                    label = { Text("全部", fontSize = 12.sp) },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1; selected = emptySet() },
                    label = { Text("相册", fontSize = 12.sp) },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭")
                }
            }

            // ===== 相册筛选 chip（仅「相册」Tab 显示，可横向滚动）=====
            if (tab == 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    FilterChip(selected = album == "全部", onClick = { album = "全部"; selected = emptySet() },
                        label = { Text("全部相册", fontSize = 11.sp) })
                    Spacer(Modifier.width(6.dp))
                    albums.forEach { a ->
                        FilterChip(selected = album == a, onClick = { album = a; selected = emptySet() },
                            label = { Text(a, fontSize = 11.sp) })
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            HorizontalDivider()

            // ===== 主体 =====
            when {
                !permGranted -> {
                    // 缺权限：提示 + 申请按钮
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text("需要照片权限才能读取相册", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { requestPermission.launch(mediaPerm()) }) {
                            Text("授予照片权限", fontSize = 13.sp)
                        }
                    }
                }
                loading -> {
                    Row(Modifier.fillMaxWidth().padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("正在读取相册…", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                visible.isEmpty() -> {
                    Text("（相册为空或无权限读取）", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(visible, key = { it.id }) { item ->
                            MediaGridItem(
                                context = context,
                                item = item,
                                selected = selected.contains(item.id),
                                onToggle = {
                                    selected = if (selected.contains(item.id)) selected - item.id
                                    else selected + item.id
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            // ===== 底部操作栏 =====
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消", fontSize = 14.sp)
                }
                TextButton(
                    onClick = {
                        val chosen = visible.filter { it.id in selected }
                        scope.launch {
                            val b64List = withContext(Dispatchers.IO) {
                                chosen.mapNotNull { item ->
                                    runCatching {
                                        context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                                            ?.let { bytes ->
                                                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                            }
                                    }.getOrNull()
                                }
                            }
                            if (b64List.isNotEmpty()) onConfirm(b64List)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = selected.isNotEmpty(),
                ) {
                    Text("确认（已选 ${selected.size}）", fontSize = 14.sp)
                }
            }
        }
    }
}

/** 单个网格单元：Coil 缩略图 + 勾选角标。 */
@Composable
private fun MediaGridItem(
    context: Context,
    item: ImgItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable { onToggle() },
    ) {
                AsyncImage(
            model = item.uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
            }
        }
    }
}

/** 查 MediaStore 全量图片（跨目录、按拍摄时间倒序；缺权限时返回空）。 */
private fun queryAllImages(ctx: Context): List<ImgItem> {
    val out = mutableListOf<ImgItem>()
    val proj = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
    )
    runCatching {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj, null, null,
            MediaStore.Images.Media.DATE_TAKEN + " DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                out.add(ImgItem(id, uri, c.getString(bucketCol).orEmpty(), c.getLong(dateCol)))
            }
        }
    }
    return out
}
