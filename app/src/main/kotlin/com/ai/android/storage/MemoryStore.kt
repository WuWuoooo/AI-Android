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

    companion object {
        private const val PREFIX = "m:"
    }
}
