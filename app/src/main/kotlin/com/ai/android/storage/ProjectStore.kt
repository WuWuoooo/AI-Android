package com.ai.android.storage

import com.ai.android.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ProjectStore(private val db: AppDatabase) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(Project.serializer())

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    /** 首次加载（App 启动时由 MainViewModel 调用） */
    suspend fun init() = withContext(Dispatchers.IO) {
        runCatching {
            val row = db.kvDao().get(KEY)
            val list = row?.value?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
            _projects.value = list
        }
    }

    suspend fun upsert(p: Project) {
        val list = _projects.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        _projects.value = list
        persist()
    }

    suspend fun delete(id: String) {
        _projects.value = _projects.value.filterNot { it.id == id }
        persist()
    }

    fun all(): List<Project> = _projects.value
    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching { db.kvDao().put(KvEntity(KEY, json.encodeToString(ser, _projects.value))) }
    }

    companion object {
        private const val KEY = "projects"
    }
}