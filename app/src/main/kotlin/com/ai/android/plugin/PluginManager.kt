package com.ai.android.plugin

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 宿主侧插件管理：zip 导入 / manifest 扫描 / 启用禁用 / 删除。
 *
 * 说明：beta5 只支持"静态挂件"插件。一个插件是一个 zip 包，
 * 包内含 manifest.json 与图标资源，图标会被渲染进悬浮窗挂件区。
 * 动态（JS）挂件在 beta6 落地。
 */
object PluginManager {

    private const val PREFS = "ai_settings"
    private const val KEY_ENABLED = "enabled_plugin_ids"
    private const val TAG = "PluginManager"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 插件根目录：filesDir/plugins */
    fun pluginsDir(context: Context): File = File(context.filesDir, "plugins").apply { mkdirs() }

    /** 列出已导入的插件：读每个子目录下的 manifest.json */
    fun list(context: Context): List<PluginManifest> {
        val dir = pluginsDir(context)
        val subs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return subs
            .mapNotNull { d ->
                val mf = File(d, "manifest.json")
                if (!mf.exists()) return@mapNotNull null
                val parsed = runCatching {
                    json.decodeFromString(PluginManifest.serializer(), mf.readText())
                }.getOrNull()
                if (parsed == null || parsed.id.isBlank()) return@mapNotNull null
                if (parsed.id != d.name) return@mapNotNull null
                parsed
            }
            .sortedBy { it.name.lowercase() }
    }

    fun isEnabled(context: Context, pluginId: String): Boolean =
        enabledSet(context).contains(pluginId)

    fun setEnabled(context: Context, pluginId: String, enabled: Boolean) {
        val set = enabledSet(context).toMutableSet()
        if (enabled) set.add(pluginId) else set.remove(pluginId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENABLED, set).apply()
    }

    private fun enabledSet(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()

    /**
     * 从 SAF Uri 导入插件 zip（不直接读 /sdcard）。
     * zip 内需含 manifest.json（可位于包根或单一顶层目录），
     * 解压后落到 filesDir 下的 plugins 目录对应子目录中。
     *
     * 返回：成功提示；失败时返回 "导入失败: ..."
     */
    fun importZip(context: Context, uri: Uri): String {
        val tmp = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        return try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) return "导入失败：无法打开 zip 流"
                                    input.use { ins ->
                ZipInputStream(ins).use { zis ->
                                        while (true) {
                        val cur = zis.nextEntry
                        if (cur == null) break
                        val target = File(tmp, cur.name)
                        // 防 Zip Slip：目标必须落在 tmp 内
                        if (!target.canonicalFile.path.startsWith(tmp.canonicalFile.path)) {
                            throw SecurityException("非法 zip 条目: ${cur.name}")
                        }
                        if (cur.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                    }
                }
            }

            // manifest 允许在包根，或 zip 内单一顶层目录
            var manifestFile = File(tmp, "manifest.json")
            if (!manifestFile.exists()) {
                val sub = tmp.listFiles { f -> f.isDirectory }?.firstOrNull()
                manifestFile = sub?.let { File(it, "manifest.json") } ?: manifestFile
            }
            if (!manifestFile.exists()) return "导入失败：包内缺少 manifest.json"

            val manifest = json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
            if (manifest.id.isBlank()) return "导入失败：manifest 缺少 id 字段"

            val srcRoot = manifestFile.parentFile
            val targetDir = File(pluginsDir(context), manifest.id)
            if (targetDir.exists()) targetDir.deleteRecursively()
            runCatching { srcRoot.copyRecursively(targetDir, overwrite = true) }
                .onFailure { return "导入失败：写入插件目录出错（${it.message}）" }

            setEnabled(context, manifest.id, true)
            Log.d(TAG, "plugin imported: ${manifest.id} v${manifest.version}")
            "已导入插件：${manifest.name}（${manifest.id}）"
        } catch (ex: Exception) {
            Log.e(TAG, "import failed", ex)
            "导入失败：${ex.message}"
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun remove(context: Context, pluginId: String): Boolean {
        val d = File(pluginsDir(context), pluginId)
        val ok = if (d.exists()) d.deleteRecursively() else true
        setEnabled(context, pluginId, false)
        return ok
    }

    /** 渲染静态挂件 View（放进悬浮窗挂件区） */
    fun renderStaticWidget(context: Context, manifest: PluginManifest, onExpand: () -> Unit): View {
        val iconFile = File(pluginsDir(context), "${manifest.id}/${manifest.iconPath}")
        val bmp = if (iconFile.exists()) {
            runCatching { BitmapFactory.decodeFile(iconFile.absolutePath) }.getOrNull()
        } else null

        val size = (44 * context.resources.displayMetrics.density).toInt()
        return ImageView(context).apply {
            if (bmp != null) {
                setImageBitmap(bmp)
            } else {
                // 无图标时显示纯色圆点占位
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF2E7CF6.toInt())
                }
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = manifest.name
            isClickable = true
            setOnClickListener { onExpand() }
        }
    }
}
