package com.ai.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.android.MainActivity
import com.ai.android.model.AgentState
import kotlin.math.abs

/**
 * 悬浮状态窗：Agent 后台工作时在屏幕边缘显示实时状态，可拖动、点击回到 App。
 */
class FloatingService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastState: AgentState = AgentState()

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        startForegroundSafe()
        addOverlayIfAllowed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) addOverlayIfAllowed()
        render(lastState)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        if (instance === this) instance = null
        mainHandler.post {
            runCatching {
                overlayView?.let { windowManager?.removeView(it) }
            }
        }
        overlayView = null
        super.onDestroy()
    }

    // ==================== 通知 ====================

    private fun startForegroundSafe() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Agent 运行状态", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("AI Android")
                .setContentText("Agent 正在后台工作")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            startForeground(NOTIF_ID, notif)
        }
    }

    // ==================== 悬浮窗 ====================

    private fun addOverlayIfAllowed() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC1F1F1F.toInt())
            setPadding(28, 18, 28, 18)
            text = "🐱 Agent 就绪"
            isClickable = true
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 240
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        tv.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX - (ev.rawX - downX).toInt()
                    lp.y = startY + (ev.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(tv, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - downX) + abs(ev.rawY - downY)
                    if (moved < 24) {
                        runCatching {
                            val i = Intent(this@FloatingService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(tv, lp) }
        overlayView = tv
        params = lp
    }

    // ==================== 状态渲染 ====================

    private fun render(state: AgentState) {
        lastState = state
        val icon = when (state.status) {
            AgentState.Status.IDLE -> "💤"
            AgentState.Status.THINKING -> "🤔"
            AgentState.Status.TOOL_CALLING -> "🔧"
            AgentState.Status.WAITING_USER -> "❓"
            AgentState.Status.COMPLETED -> "✅"
            AgentState.Status.ERROR -> "❌"
        }
        val text = state.progressText.ifBlank { state.status.name }
        mainHandler.post {
            overlayView?.text = "$icon ${text.take(40)}"
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_status"
        private const val NOTIF_ID = 1001

        @Volatile
        private var instance: FloatingService? = null

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, FloatingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, i)
                } else {
                    context.startService(i)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingService::class.java)) }
        }

        /** 更新悬浮窗内容（未运行时忽略） */
        fun update(state: AgentState) {
            instance?.render(state)
        }

        /** Agent 开始工作时：自动启动 + 更新 */
        fun showWork(context: Context, state: AgentState) {
            if (state.isActive && !isRunning) start(context)
            update(state)
        }

        /** Agent 结束时：更新最终状态，短暂延迟后收工 */
        fun finishWork(context: Context, state: AgentState) {
            update(state)
            Handler(Looper.getMainLooper()).postDelayed({
                if (instance?.lastState?.isActive != true) stop(context)
            }, 4000)
        }
    }
}
