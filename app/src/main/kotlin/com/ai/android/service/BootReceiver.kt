package com.ai.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.android.agent.tools.TaskStore

/**
 * 开机自启：重新排程定时任务并拉起调度服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        runCatching {
            TaskStore.init(context)
            val now = System.currentTimeMillis()

            // 错过的任务重新计算下一次执行时间
            TaskStore.all().forEach { t ->
                if (!t.enabled) return@forEach
                if (t.nextRunAt <= now) {
                    TaskStore.update(t.copy(nextRunAt = TaskStore.computeNext(t, now)))
                }
            }

            // 有启用中的任务才启动服务
            if (TaskStore.all().any { it.enabled }) {
                SchedulerService.start(context)
            }
        }
    }
}
