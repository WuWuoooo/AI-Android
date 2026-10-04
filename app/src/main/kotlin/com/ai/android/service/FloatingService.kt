package com.ai.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.android.MainActivity
import com.ai.android.model.AgentState
import kotlin.math.abs

class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private var lastState: AgentState = AgentState()
    private var expanded = false

    private lateinit var ballView: TextView
    private lateinit var panelView: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var toolText: TextView
    private lateinit var timeText: TextView

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        startForegroundSafe()
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 若还没添加过 overlay 则加；否则复用
        if (rootView == null) addOverlay()
        render(lastState)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        if (instance === this) instance = null
        handler.post { runCatching { rootView?.let { wm?.removeView(it) } } }
        rootView = null
        super.onDestroy()
    }

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
                this, 0, Intent(this, MainActivity::class.java),
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (rootView != null) return

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = windowManager

        ballView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            text = "AI"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61F1F1F.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            isClickable = true
        }

        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF01F1F1F.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        progressText = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); text = "就绪"
        }
        toolText = TextView(this).apply {
            textSize = 11f; setTextColor(0xFFB0B0B0.toInt()); text = ""
        }
        timeText = TextView(this).apply {
            textSize = 11f; setTextColor(0xFFB0B0B0.toInt()); text = ""
        }
        val collapseBtn = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFAEC6FF.toInt())
            text = "收起"
            setPadding(0, dp(6), 0, 0)
            isClickable = true
        }
        collapseBtn.setOnClickListener { toggleExpand(false) }
        panelView.addView(progressText)
        panelView.addView(toolText)
        panelView.addView(timeText)
        panelView.addView(collapseBtn)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ballView)
            addView(panelView)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(300)
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        ballView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (ev.rawX - downX).toInt()
                    lp.y = startY + (ev.rawY - downY).toInt()
                    runCatching { windowManager.updateViewLayout(root, lp) }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(ev.rawX - downX) + abs(ev.rawY - downY) < 20) toggleExpand(!expanded)
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(root, lp) }
        rootView = root
        params = lp
    }

    private fun toggleExpand(expand: Boolean) {
        expanded = expand
        handler.post {
            ballView.visibility = if (expand) View.GONE else View.VISIBLE
            panelView.visibility = if (expand) View.VISIBLE else View.GONE
            render(lastState)
        }
    }

    private fun render(state: AgentState) {
        lastState = state
        val label = when (state.status) {
            AgentState.Status.IDLE -> "待机"
            AgentState.Status.THINKING -> "思考"
            AgentState.Status.TOOL_CALLING -> "工具"
            AgentState.Status.WAITING_USER -> "等待"
            AgentState.Status.COMPLETED -> "完成"
            AgentState.Status.ERROR -> "错误"
        }
        handler.post {
            ballView.text = label
            progressText.text = state.progressText.ifBlank { state.status.name }
            toolText.text = state.currentTool.takeIf { it.isNotBlank() }?.let { "工具: $it" } ?: ""
            timeText.text = if (state.startedAt > 0) {
                "已运行: ${(System.currentTimeMillis() - state.startedAt) / 1000}s"
            } else ""
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_status"
        private const val NOTIF_ID = 1001

        @Volatile private var instance: FloatingService? = null
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            // 已运行则不重复启动
            if (isRunning) return
            val i = Intent(context, FloatingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ContextCompat.startForegroundService(context, i)
                else context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingService::class.java)) }
        }

        fun update(state: AgentState) { instance?.render(state) }

        fun showWork(context: Context, state: AgentState) {
            if (state.isActive && !isRunning) start(context)
            update(state)
        }

        fun finishWork(context: Context, state: AgentState) {
            update(state)
            Handler(Looper.getMainLooper()).postDelayed({
                if (instance?.lastState?.isActive != true) stop(context)
            }, 3000)
        }
    }
}