package com.ai.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.android.MainApp
import com.ai.android.MainActivity
import com.ai.android.agent.AgentEvent
import com.ai.android.agent.tools.TaskStore
import com.ai.android.model.ChatMessage
import com.ai.android.model.Conversation
import com.ai.android.model.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 定时任务执行服务：每 20 秒检查一次到期任务，用 AgentCore 执行。
 * 前台服务常驻（低优先级通知）。
 */
class SchedulerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundSafe()
        loopJob = scope.launch {
            while (isActive) {
                runCatching { checkAndRun() }
                    .onFailure { /* 单次检查失败不中断循环 */ }
                delay(20_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun checkAndRun() {
        val app = MainApp.instance
        TaskStore.init(app)
        val now = System.currentTimeMillis()
        val due = TaskStore.all().filter { it.enabled && it.nextRunAt in 1..now }
        for (task in due) {
            executeTask(app, task)
        }
    }

    private suspend fun executeTask(app: MainApp, task: ScheduledTask) {
        // 先推进执行时间，避免重复触发
        val next = if (task.type == ScheduledTask.TaskType.ONCE) 0L
        else TaskStore.computeNext(task, System.currentTimeMillis())
        TaskStore.update(
            task.copy(
                lastRunAt = System.currentTimeMillis(),
                nextRunAt = next,
                enabled = task.type != ScheduledTask.TaskType.ONCE,
            )
        )

        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = "[定时] ${task.name}",
        )
        conversation.addMessage(ChatMessage.user(task.prompt))

        var lastError: String? = null
        var lastText = ""

        app.agentCore.run(conversation).collect { ev ->
            when (ev) {
                is AgentEvent.State -> FloatingService.update(ev.state)
                is AgentEvent.ContentDelta -> lastText += ev.text
                is AgentEvent.MessageDone -> if (ev.message.content.isNotBlank()) lastText = ev.message.content
                is AgentEvent.Error -> lastError = ev.msg
                else -> Unit
            }
        }

        // 保存执行记录到 KV 表（供设置页展示）
        runCatching {
            app.database.kvDao().put(
                com.ai.android.storage.KvEntity(
                    key = "task_run_${task.id}_${System.currentTimeMillis()}",
                    value = buildString {
                        append("任务: ").append(task.name).append('\n')
                        append("时间: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())).append('\n')
                        if (lastError != null) append("错误: ").append(lastError) else append("结果: ").append(lastText)
                    },
                )
            )
        }

        notifyResult(task, lastError, lastText)
    }

    // ==================== 通知 ====================

    private fun startForegroundSafe() {
        runCatching {
            ensureChannel(CHANNEL_MONITOR, "定时任务监控")
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("AI Android 定时任务")
                .setContentText("正在监控定时任务")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            startForeground(NOTIF_MONITOR, notif)
        }
    }

    private fun notifyResult(task: ScheduledTask, error: String?, text: String) {
        // Android 13+ 检查通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        runCatching {
            ensureChannel(CHANNEL_RESULT, "定时任务结果")
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val body = if (error != null) "❌ $error" else text.ifBlank { "任务已执行" }
            val notif = NotificationCompat.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("⏰ ${task.name}")
                .setContentText(body.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(500)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_RESULT_BASE + (task.id.hashCode() and 0xFF), notif)
        }
    }

    private fun ensureChannel(id: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    companion object {
        private const val CHANNEL_MONITOR = "scheduler_monitor"
        private const val CHANNEL_RESULT = "scheduler_result"
        private const val NOTIF_MONITOR = 1002
        private const val NOTIF_RESULT_BASE = 2000

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, SchedulerService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, i)
                } else {
                    context.startService(i)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SchedulerService::class.java)) }
        }
    }
}
