package com.ai.android.agent.tools

import android.content.Context
import android.content.SharedPreferences
import com.ai.android.model.ScheduledTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 定时任务持久化存储（SharedPreferences + JSON）。
 */
object TaskStore {

    private const val PREF = "scheduled_tasks"
    private const val KEY = "list"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(ScheduledTask.serializer())

    private var prefs: SharedPreferences? = null
    private val tasks = mutableListOf<ScheduledTask>()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val raw = prefs?.getString(KEY, null) ?: return
        runCatching {
            val list = json.decodeFromString(ser, raw)
            tasks.clear()
            tasks.addAll(list)
        }
    }

    fun save() {
        runCatching {
            prefs?.edit()?.putString(KEY, json.encodeToString(ser, tasks.toList()))?.apply()
        }
    }

    fun all(): List<ScheduledTask> = tasks.toList()

    fun add(t: ScheduledTask) {
        tasks.add(t)
        save()
    }

    fun remove(id: String): Boolean {
        val ok = tasks.removeAll { it.id == id }
        if (ok) save()
        return ok
    }

    fun update(t: ScheduledTask) {
        val i = tasks.indexOfFirst { it.id == t.id }
        if (i >= 0) {
            tasks[i] = t
            save()
        }
    }

    /** 按类型计算下次执行时间 */
    fun computeNext(t: ScheduledTask, from: Long = System.currentTimeMillis()): Long = when (t.type) {
        ScheduledTask.TaskType.ONCE -> from + 3_000
        ScheduledTask.TaskType.INTERVAL -> from + t.intervalMinutes.coerceAtLeast(1) * 60_000L
        ScheduledTask.TaskType.DAILY -> nextDaily(t.timeOfDay, from)
        ScheduledTask.TaskType.WEEKLY -> nextWeekly(t.daysOfWeek, t.timeOfDay, from)
    }

    private fun nextDaily(timeOfDay: String, from: Long): Long {
        val parts = timeOfDay.split(":").mapNotNull { it.trim().toIntOrNull() }
        val h = parts.getOrNull(0) ?: 8
        val m = parts.getOrNull(1) ?: 0
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun nextWeekly(daysOfWeek: String, timeOfDay: String, from: Long): Long {
        val days = daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            .ifEmpty { setOf(1) }
        val parts = timeOfDay.split(":").mapNotNull { it.trim().toIntOrNull() }
        val h = parts.getOrNull(0) ?: 8
        val m = parts.getOrNull(1) ?: 0
        for (i in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = from; add(Calendar.DAY_OF_YEAR, i)
            }
            val cnDow = if (c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
            else c.get(Calendar.DAY_OF_WEEK) - 1
            if (cnDow in days) {
                c.set(Calendar.HOUR_OF_DAY, h)
                c.set(Calendar.MINUTE, m)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (c.timeInMillis > from) return c.timeInMillis
            }
        }
        return from + 24 * 60 * 60 * 1000L
    }
}

private val fmtTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

/** 创建定时任务 */
class SchedulerTool(private val context: Context) : ToolExecutor {

    override val name = "create_scheduled_task"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        TaskStore.init(context)
        val name = args.str("name").ifBlank { "未命名任务" }
        val prompt = args.requireStr("prompt", "要执行的独立指令，例如「告诉我今天天气」")

        val typeStr = args.str("type", "INTERVAL").uppercase()
        val type = runCatching { ScheduledTask.TaskType.valueOf(typeStr) }
            .getOrElse {
                throw IllegalStateException(
                    "无效的 type: $typeStr（可选值：ONCE / INTERVAL / DAILY / WEEKLY）"
                )
            }

        // 类型相关参数校验
        when (type) {
            ScheduledTask.TaskType.INTERVAL -> {
                val interval = args.int("interval_minutes")
                if (interval <= 0) {
                    throw IllegalStateException("INTERVAL 类型必须提供 interval_minutes（正整数，单位分钟）")
                }
            }
            ScheduledTask.TaskType.DAILY -> {
                if (args.str("time_of_day").isBlank()) {
                    throw IllegalStateException("DAILY 类型必须提供 time_of_day，格式 HH:mm，例如 08:00")
                }
            }
            ScheduledTask.TaskType.WEEKLY -> {
                if (args.str("time_of_day").isBlank()) {
                    throw IllegalStateException("WEEKLY 类型必须提供 time_of_day，格式 HH:mm")
                }
                if (args.str("days_of_week").isBlank()) {
                    throw IllegalStateException("WEEKLY 类型必须提供 days_of_week，例如 1,3,5（1=周一）")
                }
            }
            ScheduledTask.TaskType.ONCE -> Unit
        }

        val base = ScheduledTask(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = name,
            prompt = prompt,
            type = type,
            intervalMinutes = args.int("interval_minutes"),
            timeOfDay = args.str("time_of_day"),
            daysOfWeek = args.str("days_of_week"),
        )
        val task = base.copy(nextRunAt = TaskStore.computeNext(base), enabled = true)
        TaskStore.add(task)
        "✅ 已创建定时任务「$name」\nid: ${task.id}\n类型: $type\n" +
            "下次执行: ${fmtTime.format(Date(task.nextRunAt))}\n指令: $prompt"
    }
}

/** 列出定时任务 */
class ListTasksTool(private val context: Context) : ToolExecutor {

    override val name = "list_scheduled_tasks"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        TaskStore.init(context)
        val list = TaskStore.all()
        if (list.isEmpty()) return@withContext "当前没有定时任务"
        list.joinToString("\n\n") { t ->
            val state = if (t.enabled) "启用" else "停用"
            "【${t.name}】id=${t.id} ($state)\n类型: ${t.type}" +
                (if (t.type == ScheduledTask.TaskType.INTERVAL) " 每 ${t.intervalMinutes} 分钟" else "") +
                (if (t.timeOfDay.isNotBlank()) " @ ${t.timeOfDay}" else "") +
                "\n下次: ${fmtTime.format(Date(t.nextRunAt))}\n指令: ${t.prompt.take(100)}"
        }
    }
}

/** 取消定时任务 */
class CancelTaskTool(private val context: Context) : ToolExecutor {

    override val name = "cancel_scheduled_task"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        TaskStore.init(context)
        val id = args.requireStr("id", "任务 ID（来自 list_scheduled_tasks）")
        if (TaskStore.remove(id)) "✅ 已取消任务 $id" else "未找到任务: $id"
    }
}