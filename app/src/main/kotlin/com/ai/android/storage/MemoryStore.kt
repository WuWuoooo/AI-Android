package com.ai.android.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * 长期记忆存储（SharedPreferences 键值，跨会话保留）。
 */
class MemoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai_memory", Context.MODE_PRIVATE)

    private val lock = Any()

    fun save(key: String, content: String) {
        synchronized(lock) {
            prefs.edit().putString(PREFIX + key.trim(), content).apply()
        }
    }

    fun read(key: String): String? = synchronized(lock) {
        prefs.getString(PREFIX + key.trim(), null)
    }

        fun list(): Map<String, String> = synchronized(lock) {
        prefs.all.entries
            .filter { it.key.startsWith(PREFIX) && it.value is String }
            // ⭐ 第五轮 #4：排除项目记忆条目（key 去 m: 前缀后以 "proj:" 开头的都是项目记忆，归各项目管理）
            .filter { !it.key.removePrefix(PREFIX).startsWith("proj:") }
            .associate { it.key.removePrefix(PREFIX) to (it.value as String) }
    }

    fun delete(key: String): Boolean {
        val k = PREFIX + key.trim()
        return synchronized(lock) {
            if (!prefs.contains(k)) return false
            prefs.edit().remove(k).apply()
            true
        }
    }

    fun clear() {
        synchronized(lock) {
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { edit.remove(it) }
            edit.apply()
        }
    }

        /** 生成给系统提示词用的记忆摘要（过长时截断） */
    fun summaryBlock(): String {
        val all = list()
        if (all.isEmpty()) return ""
        val sb = StringBuilder("【长期记忆】\n")
        all.forEach { (k, v) ->
            val text = if (v.length > 300) v.take(300) + "..." else v
            sb.append("- [$k] ").append(text).append('\n')
        }
        return sb.toString()
    }

    // ==================== ⭐ 第五轮 #4：项目作用域记忆（每个项目独立，前缀 proj:<projectId>:）====================

    /** 项目记忆前缀：proj:<id> —— 区分全局记忆和项目记忆 */
    fun projectMemoryPrefix(projectId: String) = "proj:$projectId:"

    /** 项目内记忆列表（key 不含前缀，value 完整） */
    fun projectMemoryList(projectId: String): Map<String, String> = synchronized(lock) {
        val prefix = projectMemoryPrefix(projectId)
        prefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value is String }
            .associate { it.key.removePrefix(prefix) to (it.value as String) }
    }

    /** 项目内存一条记忆（key 不带前缀） */
    fun saveProjectMemory(projectId: String, key: String, content: String) {
        synchronized(lock) {
            prefs.edit().putString(PREFIX + projectMemoryPrefix(projectId) + key.trim(), content).apply()
        }
    }

    /** 项目内读一条记忆（key 不带前缀） */
    fun readProjectMemory(projectId: String, key: String): String? = synchronized(lock) {
        prefs.getString(PREFIX + projectMemoryPrefix(projectId) + key.trim(), null)
    }

        /** 项目内删一条记忆（key 不带前缀） */
    fun deleteProjectMemory(projectId: String, key: String): Boolean = synchronized(lock) {
        val k = PREFIX + projectMemoryPrefix(projectId) + key.trim()
        if (!prefs.contains(k)) {
            false
        } else {
            prefs.edit().remove(k).apply()
            true
        }
    }

    /** 项目记忆摘要块（注入 AgentCore 系统提示词用，按 projectId 取） */
    fun projectSummaryBlock(projectId: String): String {
        val all = projectMemoryList(projectId)
        if (all.isEmpty()) return ""
        val sb = StringBuilder("【项目记忆（项目ID $projectId）】\n")
        all.forEach { (k, v) ->
            val text = if (v.length > 300) v.take(300) + "..." else v
            sb.append("- [$k] ").append(text).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private const val PREFIX = "m:"
    }
}
