package com.ai.android.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

// ==================== 文件工具集 ====================

/** 读取文件全文（大文件截断到 100KB） */
class ReadFileTool : ToolExecutor {
    override val name = "read_file"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        val f = File(path)
        if (!f.exists()) throw IllegalStateException("文件不存在: $path")
        if (f.isDirectory) throw IllegalStateException("这是一个目录，请用 list_files 或 list_dir_tree")
        val text = if (f.length() > 100_000) {
            f.readText().take(100_000) + "\n\n...(文件过大已截断，总大小 ${f.length()} 字节)"
        } else f.readText()
        text
    }
}

/** 创建或覆写文件 */
class WriteFileTool : ToolExecutor {
    override val name = "write_file"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        if (path.isBlank()) throw IllegalStateException("path 不能为空")
        val content = args.str("content")
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        "已写入文件: $path（${content.length} 字符）"
    }
}

/** 精确替换文件局部内容 */
class EditFileTool : ToolExecutor {
    override val name = "edit_file"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        val old = args.str("old_string")
        val new = args.str("new_string")
        val all = args.bool("replace_all")
        if (old.isEmpty()) throw IllegalStateException("old_string 不能为空")
        val f = File(path)
        if (!f.exists()) throw IllegalStateException("文件不存在: $path")
        val text = f.readText()
        if (!text.contains(old)) throw IllegalStateException("未找到要替换的内容，请先 read_file 读取目标区域，再从返回结果中逐字符复制")
        val count = Regex(Regex.escape(old)).findAll(text).count()
        val result = if (all) text.replace(old, new) else text.replaceFirst(old, new)
        f.writeText(result)
        "已替换: $path（替换了 ${if (all) count else 1} 处）"
    }
}

/** 列出目录一层内容 */
class ListFilesTool : ToolExecutor {
    override val name = "list_files"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        val f = File(path)
        if (!f.exists()) throw IllegalStateException("目录不存在: $path")
        if (!f.isDirectory) throw IllegalStateException("不是目录: $path（请用 read_file）")
        val entries = f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: throw IllegalStateException("无法读取目录: $path（权限不足？）")
        if (entries.isEmpty()) return@withContext "（空目录）"
        entries.joinToString("\n") { c ->
            if (c.isDirectory) "📁 ${c.name}/" else "📄 ${c.name} (${c.length()}B)"
        }
    }
}

/** 目录树（缩进形式） */
class ListDirTreeTool : ToolExecutor {
    override val name = "list_dir_tree"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        val maxDepth = args.int("max_depth", 3).coerceIn(1, 8)
        val maxEntries = args.int("max_entries", 300).coerceIn(10, 2000)
        val root = File(path)
        if (!root.exists()) throw IllegalStateException("路径不存在: $path")
        val sb = StringBuilder(root.name + "/\n")
        var count = 0
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || count >= maxEntries) return
            val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (c in children) {
                if (count >= maxEntries) return
                count++
                sb.append("  ".repeat(depth))
                sb.append(c.name).append(if (c.isDirectory) "/" else " (${c.length()}B)").append("\n")
                if (c.isDirectory && !c.name.startsWith(".")) walk(c, depth + 1)
            }
        }
        walk(root, 1)
        if (count >= maxEntries) sb.append("...(省略，超过 $maxEntries 条)\n")
        sb.toString()
    }
}

/** 按文件名或内容搜索 */
class SearchFilesTool : ToolExecutor {
    override val name = "search_files"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        val keyword = args.str("keyword")
        val inContent = args.bool("in_content")
        if (keyword.isBlank()) throw IllegalStateException("keyword 不能为空")
        val root = File(path)
        if (!root.exists()) throw IllegalStateException("路径不存在: $path")
        val results = mutableListOf<String>()
        var scanned = 0
        fun scan(dir: File) {
            if (scanned > 5000 || results.size >= 100) return
            val kids = dir.listFiles() ?: return
            for (c in kids) {
                if (scanned > 5000 || results.size >= 100) return
                scanned++
                if (c.isDirectory) {
                    if (!c.name.startsWith(".")) scan(c)
                } else if (c.name.contains(keyword, ignoreCase = true)) {
                    results.add(c.absolutePath)
                } else if (inContent && c.length() < 300_000 && isTextFile(c)) {
                    runCatching {
                        c.bufferedReader().useLines { lines ->
                            lines.forEachIndexed { i, line ->
                                if (line.contains(keyword, ignoreCase = true) && results.size < 100) {
                                    results.add("${c.absolutePath}:${i + 1}: ${line.trim().take(200)}")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (root.isFile) {
            if (root.name.contains(keyword, ignoreCase = true)) results.add(root.absolutePath)
        } else scan(root)
        if (results.isEmpty()) "未找到匹配「$keyword」的结果（共扫描 $scanned 项）"
        else "找到 ${results.size} 个结果:\n" + results.joinToString("\n")
    }

    private fun isTextFile(f: File): Boolean {
        val ext = f.extension.lowercase()
        return ext in setOf("txt", "md", "kt", "java", "js", "json", "xml", "gradle", "py", "sh", "html", "css", "yaml", "yml", "properties", "log", "csv", "toml", "ini")
    }
}

/** 创建目录 */
class CreateDirTool : ToolExecutor {
    override val name = "create_dir"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        val f = File(path)
        if (f.exists()) "目录已存在（视为成功）: $path"
        else if (f.mkdirs()) "已创建目录: $path"
        else throw IllegalStateException("创建目录失败: $path")
    }
}

/** 删除文件或目录 */
class DeleteFileTool : ToolExecutor {
    override val name = "delete_file"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        val recursive = args.bool("recursive")
        val f = File(path)
        if (!f.exists()) throw IllegalStateException("路径不存在: $path")
        val ok = if (f.isDirectory) {
            if (recursive) f.deleteRecursively() else throw IllegalStateException("目标是目录，需要 recursive=true 才能删除")
        } else f.delete()
        if (ok) "已删除: $path" else throw IllegalStateException("删除失败: $path")
    }
}

/** 移动文件或目录 */
class MoveFileTool : ToolExecutor {
    override val name = "move_file"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val src = args.str("src")
        val dst = args.str("dst")
        val overwrite = args.bool("overwrite")
        val s = File(src)
        if (!s.exists()) throw IllegalStateException("源路径不存在: $src")
        val d = File(dst)
        if (d.exists() && !overwrite) throw IllegalStateException("目标已存在: $dst（如需覆盖请传 overwrite=true）")
        if (d.exists() && overwrite) {
            if (d.isDirectory) d.deleteRecursively() else d.delete()
        }
        d.parentFile?.mkdirs()
        val ok = s.renameTo(d)
        if (ok) "已移动: $src → $dst"
        else {
            val copied = s.copyRecursively(d, overwrite = true)
            if (copied) { if (s.isDirectory) s.deleteRecursively() else s.delete(); "已移动（跨区复制）: $src → $dst" }
            else throw IllegalStateException("移动失败: $src → $dst")
        }
    }
}

/** 复制文件或目录 */
class CopyFileTool : ToolExecutor {
    override val name = "copy_file"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val src = args.str("src")
        val dst = args.str("dst")
        val overwrite = args.bool("overwrite")
        val s = File(src)
        if (!s.exists()) throw IllegalStateException("源路径不存在: $src")
        val d = File(dst)
        if (d.exists() && !overwrite) throw IllegalStateException("目标已存在: $dst（如需覆盖请传 overwrite=true）")
        d.parentFile?.mkdirs()
        val ok = if (s.isDirectory) s.copyRecursively(d, overwrite = overwrite)
        else { d.writeBytes(s.readBytes()); true }
        if (ok) "已复制: $src → $dst" else throw IllegalStateException("复制失败: $src → $dst")
    }
}

/** 重命名 */
class RenameFileTool : ToolExecutor {
    override val name = "rename_file"
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args.str("path")
        val newName = args.str("new_name")
        if (newName.contains("/")) throw IllegalStateException("new_name 不能包含路径分隔符")
        val f = File(path)
        if (!f.exists()) throw IllegalStateException("路径不存在: $path")
        val target = File(f.parentFile, newName)
        if (target.exists()) throw IllegalStateException("同名文件已存在: ${target.absolutePath}")
        if (f.renameTo(target)) "已重命名: ${f.name} → $newName"
        else throw IllegalStateException("重命名失败: $path")
    }
}
