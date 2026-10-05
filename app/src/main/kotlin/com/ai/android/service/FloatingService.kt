package com.ai.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.android.MainActivity
import com.ai.android.MainApp
import com.ai.android.model.AgentState
import com.ai.android.plugin.FloatingStyle
import com.ai.android.plugin.WidgetSlot
import kotlin.math.abs

/**
 * 悬浮窗前台服务（参考 AndLua 悬浮球布局：可拖动悬浮球 ↔ 展开面板）。
 *
 * 特性（beta5 增强）：
 * - 单一 overlay 实例：创建新进度悬浮窗前清除旧的（幂等）。
 * - 46dp 悬浮球可拖动；点击展开 200dp 面板；面板标题栏可拖动。
 * - ⭐ 实时输出区（折叠的 AI 思考 / 生成内容），随内容增加自动上下滚动（滚到底部）。
 * - ⭐ 悬浮球外观可由插件定制（PluginRegistry 提供 WIDGET 挂件渲染球）。
 * - 镜像小窗（MediaProjection 帧）+ Token 行 + "暂停 AI" 按钮。
 */
class FloatingService : Service() {

        private var wm: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    // ⭐ 拖拽 / 点击状态（统一 handler，绑定到球 / 默认球 / 插件球 / 挂件 / 面板标题栏）
    @Volatile private var downRawX = 0f
    @Volatile private var downRawY = 0f
    @Volatile private var startLpX = 0
    @Volatile private var startLpY = 0
    @Volatile private var dragMoved = false
    /** 统一触摸处理：子 view 若消费 DOWN，其 listener 仍先触发，保证球 / 挂件都能拖 / 点 */
    private val onDrag = View.OnTouchListener { _, ev -> handleDrag(ev) }

    private var lastState: AgentState = AgentState()
    private var lastTokenText: String = ""
    var expanded = false
        private set

    // 球容器（拖动 / 点击手柄）
    private lateinit var ballRow: LinearLayout
    private lateinit var defaultBall: TextView      // 默认 "AI" 圆球（无插件时显示）
    private val pluginBallViews = mutableListOf<View>()  // 插件定制球

    // 面板
        private lateinit var panelView: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var toolText: TextView
    private lateinit var timeText: TextView
    private lateinit var realTimeScroll: ScrollView   // ⭐ 实时输出滚动区
    private lateinit var realTimeText: TextView
    private lateinit var mirrorView: ImageView
    private lateinit var mirrorCaption: TextView
    private lateinit var tokenText: TextView
    private lateinit var pauseBtn: TextView
    private lateinit var closeBtn: TextView   // ⭐ 关闭悬浮窗按钮

    /** 插件挂件（球旁的静态图标） */
    private val pluginWidgetViews = mutableListOf<View>()
    private val pluginSlots = mutableListOf<WidgetSlot>()

    /** 实时输出缓冲（保留尾部，防内存膨胀） */
    @Volatile private var reasoningBuffer = ""
    @Volatile private var contentBuffer = ""
    private val MAX_RT = 3_000

    /** overlay 视图是否已就绪（lateinit 视图可安全访问） */
    @Volatile private var overlayReady = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        startForegroundSafe()
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null) addOverlay()
        render(lastState)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

        override fun onDestroy() {
        // ⭐ 修复"点关闭后悬浮窗卡死"：旧实现用 handler.post 延迟移除 overlay，
        //    服务销毁后该 runnable 常执行不到 → 残留窗口卡死。改为同步移除 + 已置空。
        removeOverlaySync()
        super.onDestroy()
    }

    /**
     * ⭐ 主线程同步移除全部 overlay 视图并停止服务。
     * Service 的所有方法都在主线程执行，故可直接操作 wm.removeView，无需 handler.post。
     * 幂等：rootView / 子 view 已移除时重复调用安全。
     */
    fun close() {
        removeOverlaySync()
        stopSelf()
    }

    /** 同步拆除 overlay（关闭按钮 / onDestroy 共用） */
    private fun removeOverlaySync() {
        isRunning = false
        if (instance === this) instance = null
        runCatching {
            pluginWidgetViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
            pluginBallViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
            if (rootView != null) {
                runCatching { wm?.removeView(rootView!!) }
            }
        }
        pluginWidgetViews.clear()
        pluginBallViews.clear()
        pluginSlots.clear()
        rootView = null
        overlayReady = false
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

        /**
     * ⭐ v1.1.0 #3：按 style 的 ballShape / ballSize / ballIcon / ballBg 构建悬浮球背景。
     *  - ballIcon 非空且文件存在 → 用插件自带 PNG 图标（非仅 tint）
     *  - ballShape：circle（圆）/ rounded（圆角矩形）/ square（方块）/ capsule（胶囊）
     */
    private fun ballDrawable(s: com.ai.android.plugin.FloatingStyle): android.graphics.drawable.Drawable {
                // 优先：插件自带图标（相对插件目录）
        if (s.ballIcon.isNotBlank()) {
            val host = com.ai.android.MainApp.instance.pluginRegistry
            val pluginId = host.activeThemePluginId()
                ?: host.allWidgets().firstOrNull()?.manifest?.id
            if (pluginId != null) {
                val iconFile = java.io.File(
                    com.ai.android.plugin.PluginManager.pluginsDir(this),
                    "$pluginId/${s.ballIcon}",
                )
                val bmp = runCatching {
                    android.graphics.BitmapFactory.decodeFile(iconFile.takeIf { it.exists() }?.absolutePath)
                }.getOrNull()
                if (bmp != null) {
                    return android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
                        setBounds(0, 0, dp(s.ballSize), dp(s.ballSize))
                    }
                }
            }
        }
        val gd = android.graphics.drawable.GradientDrawable()
        gd.setColor(s.ballBg)
        gd.cornerRadius = when (s.ballShape) {
            "capsule" -> dp(s.ballSize).toFloat() / 2f
            "rounded" -> (dp(s.ballSize).toFloat() * 0.28f)
            "square" -> 0f
            else -> dp(s.ballSize).toFloat() / 2f  // circle
        }
        return gd
    }

    /** ⭐ 当前生效的悬浮窗样式（首个 THEME 插件；无则 AndLua 默认白卡风格） */
    private fun style(): FloatingStyle =
        runCatching { MainApp.instance.pluginRegistry.activeFloatingStyle }.getOrDefault(FloatingStyle.DEFAULT)

    /**
     * 构建 overlay。
     * ⭐ 单一实例保证：rootView 已存在则先移除旧的再重建（"创建新进度悬浮窗前清除上一个"）。
     */
    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        // 清除上一个 overlay（幂等去重）
        if (rootView != null) {
            runCatching {
                pluginWidgetViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
                pluginBallViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
                rootView?.let { wm?.removeView(it) }
            }
            pluginWidgetViews.clear(); pluginBallViews.clear()
            overlayReady = false
        }

                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = windowManager

                // ⭐ 插件可定制的悬浮窗样式（默认 AndLua MyApp1 白卡风格；THEME 插件可覆盖）
        val s = style()

        // ⭐ v1.1.0 #3：悬浮球尺寸 / 形状（ballSize / ballShape），旧插件无这些字段时回默认
        val ballSize = s.ballSize.coerceIn(24, 96)

        // ---- 默认悬浮球（尺寸/形状可被插件定制；无插件时显示）----
        defaultBall = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(s.ballText)
            text = s.ballLabel
            background = ballDrawable(s)
            layoutParams = LinearLayout.LayoutParams(dp(ballSize), dp(ballSize))
            isClickable = true
        }

        // ---- 球容器：默认球 + 插件球（横向排列，整体作为拖动 / 点击手柄）----
        ballRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(defaultBall, LinearLayout.LayoutParams(dp(ballSize), dp(ballSize)))
        }
        renderPluginBall()
        renderPluginWidgets()

                // ---- 面板 ----
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ⭐ 默认 AndLua 白卡（圆角 + 白底）；THEME 插件可改色
            val bg = GradientDrawable()
            bg.cornerRadius = dp(s.cornerRadius).toFloat()
            bg.setColor(s.panelBg)
            background = bg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }

        // 标题栏（可拖动手柄 + 状态 + 收起 / 关闭）
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val panelTitleText = TextView(this).apply {
            textSize = 14f; setTextColor(s.panelText); text = s.panelTitle
                        typeface = Typeface.DEFAULT_BOLD
        }
        progressText = TextView(this).apply {
            textSize = 13f; setTextColor(s.panelText); text = "就绪"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f) // weight=1 占满
        }
        val collapseBtn = TextView(this).apply {
            textSize = 15f
            setTextColor(s.panelActionText)
            text = "—"; isClickable = true
            setOnClickListener { toggleExpand(false) }
        }
        titleBar.addView(panelTitleText)
        titleBar.addView(progressText)
        titleBar.addView(collapseBtn)

                // ⭐ 实时输出滚动区（折叠的 AI 思考 / 生成，随内容增长自动滚底）
        realTimeText = TextView(this).apply {
            textSize = 11f
            setTextColor(s.realtimeText)
            setLineSpacing(dp(2).toFloat(), 1f)
            text = ""
            setPadding(0, dp(4), 0, dp(4))
        }
        realTimeScroll = ScrollView(this).apply {
            addView(realTimeText)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE   // 无内容时隐藏
        }
        val rtLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
        realTimeScroll.layoutParams = rtLp

                toolText = TextView(this).apply {
            textSize = 11f; setTextColor(s.panelTextSecondary); text = ""
        }
        timeText = TextView(this).apply {
            textSize = 11f; setTextColor(s.panelTextSecondary); text = ""
        }
        tokenText = TextView(this).apply {
            textSize = 11f; setTextColor(s.accent)
                        typeface = Typeface.DEFAULT_BOLD
            text = ""
            visibility = View.GONE
        }

        // 镜像小窗（200×300dp，默认隐藏；AI 操控手机时显示）
        mirrorView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            isClickable = true
            setOnClickListener { MainApp.instance.agentStopCallback?.invoke() }
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(300)).also { it.bottomMargin = dp(4) }
        }
        mirrorCaption = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            text = "AI 操作中…（点击暂停）"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x66000000.toInt())
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { MainApp.instance.agentStopCallback?.invoke() }
        }

                pauseBtn = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(s.accent)
            text = "⏸ 暂停 AI"
            isClickable = true
            setOnClickListener { MainApp.instance.agentStopCallback?.invoke() }
        }

                panelView.addView(titleBar)
        panelView.addView(realTimeScroll)
        panelView.addView(toolText)
        panelView.addView(timeText)
        panelView.addView(tokenText)
        panelView.addView(mirrorView)
        panelView.addView(mirrorCaption)
        panelView.addView(pauseBtn)

                // ⭐ 关闭悬浮窗按钮（常驻直到用户手动关闭或 App 被杀）
        closeBtn = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFE08080.toInt())
            text = "✕ 关闭悬浮窗"
            isClickable = true
            setOnClickListener { close() }   // ⭐ 主线程同步移除 overlay + stopSelf，避免卡死
            setPadding(0, dp(6), 0, 0)
        }
        panelView.addView(closeBtn)

        // ---- 根容器 ----
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ballRow)
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

                // 拖动 / 点击手柄：统一 onDrag 绑到球容器、默认球、标题栏（插件球/挂件在 render* 里再绑）
        // ⭐ 子 view 设了 isClickable 会消费 DOWN，故必须逐个子 view 也绑，否则拖不动 / 点不开
        ballRow.setOnTouchListener(onDrag)
        defaultBall.setOnTouchListener(onDrag)
        titleBar.setOnTouchListener(onDrag)

        runCatching { windowManager.addView(root, lp) }
        rootView = root
        params = lp
        overlayReady = true
    }

    /** ⭐ 悬浮球外观由插件定制：有启用 WIDGET 的插件则渲染插件球（隐藏默认球） */
    private fun renderPluginBall() {
        pluginBallViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
        pluginBallViews.clear()
        if (!::ballRow.isInitialized) return
        val ctx = this
        val slots = runCatching { MainApp.instance.pluginRegistry.allWidgets() }.getOrDefault(emptyList())
        val ballSlot = slots.firstOrNull()   // 首个插件作为球
        if (ballSlot != null) {
            val v = runCatching { ballSlot.render(ctx) { toggleExpand(true) } }.getOrNull()
                        if (v != null) {
                v.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                v.setOnTouchListener(onDrag)   // ⭐ 插件球也要可拖 / 点
                ballRow.addView(v)
                pluginBallViews.add(v)
                defaultBall.visibility = View.INVISIBLE   // 有插件球时隐藏默认球
                runCatching { ballSlot.onBind(Bundle()) }
            }
        }
    }

    /** 插件挂件区：从 PluginRegistry 取已启用的静态挂件渲染进球旁 */
    private fun renderPluginWidgets() {
        pluginWidgetViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
        pluginWidgetViews.clear()
        pluginSlots.clear()
        val slots = runCatching { MainApp.instance.pluginRegistry.allWidgets() }.getOrDefault(emptyList())
        // 首个插件已用作球，其余作为球旁挂件
        slots.drop(1).forEach { slot ->
            val v = runCatching { slot.render(this) { toggleExpand(true) } }.getOrNull()
                        if (v != null) {
                pluginWidgetViews.add(v)
                v.setOnTouchListener(onDrag)   // ⭐ 挂件也可拖 / 点（点击展开面板）
                ballRow.addView(v, LinearLayout.LayoutParams(dp(44), dp(44)).also {
                    val p = dp(6); it.setMargins(0, 0, p, 0)
                })
                pluginSlots.add(slot)
            }
        }
    }

            /** 插件导入 / 删除 / 启停后重建球 + 挂件区 + 重新应用样式（设置页调用） */
    fun rebuildWidgets() {
        handler.post {
            if (!overlayReady) return@post
            // ⭐ 重新应用插件样式（THEME 插件变更后热更新球 / 面板配色）
            runCatching {
                renderPluginBall()
                renderPluginWidgets()
                applyStyleRecolor()
                render(lastState)
            }
        }
    }

    /** ⭐ 短期待办 5：屏幕旋转 / 尺寸变化后重新 clamp 悬浮窗位置（被 MainActivity.onConfigurationChanged 调用） */
    fun reclamp() {
        handler.post {
            runCatching { clampToScreen() }
        }
    }

    /** 插件样式变更后，对已存在的球 / 面板重新配色（无需重建 overlay） */
    private fun applyStyleRecolor() {
        if (!::defaultBall.isInitialized) return
        val s = style()
                runCatching {
            defaultBall.setTextColor(s.ballText)
            // ⭐ v1.1.0 #3：球形状/尺寸/图标变了也要重建背景（不只是重配色）
            defaultBall.background = ballDrawable(s)
            (panelView.background as? GradientDrawable)?.let {
                it.setColor(s.panelBg); it.cornerRadius = dp(s.cornerRadius).toFloat()
            }
            progressText.setTextColor(s.panelText)
            toolText.setTextColor(s.panelTextSecondary)
            timeText.setTextColor(s.panelTextSecondary)
            tokenText.setTextColor(s.accent)
            realTimeText.setTextColor(s.realtimeText)
            pauseBtn.setTextColor(s.accent)
        }
    }

        private fun toggleExpand(expand: Boolean) {
        expanded = expand
        handler.post {
            if (!overlayReady) return@post
            // ⭐ ballRow / panelView 用 GONE（不占布局空间），避免展开面板后
            //    root 宽度突增导致定位偏移（之前 INVISIBLE 还占位 → 拖不动/溢出）
            ballRow.visibility = if (expand) View.GONE else View.VISIBLE
            panelView.visibility = if (expand) View.VISIBLE else View.GONE
            // ⭐ 宽高变化后 post 回调，clamp 到屏幕内再 updateViewLayout
            rootView?.post { clampToScreen() }
            render(lastState)
        }
    }

    /**
     * ⭐ 把 params.x/y clamp 到屏幕内（rootView 宽高变化 / 旋转后调用）。
     * 修复：面板展开（宽度 ~210dp）后若 x 超出屏幕右边界，整个悬浮窗无法拖动
     *       —— 因为 updateViewLayout 时 root 的宽高已变，x 相对旧值偏移导致落空。
     */
    private fun clampToScreen() {
        val root = rootView ?: return
        val p = params ?: return
        val dm = resources.displayMetrics
        val rootW = root.width
        val rootH = root.height
        if (rootW <= 0 || rootH <= 0) return
        val maxX = (dm.widthPixels - rootW).coerceAtLeast(0)
        val maxY = (dm.heightPixels - rootH).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
        runCatching { wm?.updateViewLayout(root, p) }
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
            if (!overlayReady) return@post
            progressText.text = label + if (state.progressText.isNotBlank()) " · ${state.progressText.take(20)}" else ""
            toolText.text = state.currentTool.takeIf { it.isNotBlank() }?.let { "工具: $it" } ?: ""
            timeText.text = if (state.startedAt > 0) {
                "已运行: ${(System.currentTimeMillis() - state.startedAt) / 1000}s"
            } else ""
            if (lastTokenText.isNotBlank()) {
                tokenText.visibility = View.VISIBLE
                tokenText.text = lastTokenText
            }
            // ⭐ 向已注册的插件球 / 挂件推送数据（beta5 静态挂件可消费 token / 状态）
            val bundle = Bundle().apply {
                putString("state", state.status.name)
                putString("progress", state.progressText)
                putString("tool", state.currentTool)
                putString("token_text", lastTokenText)
            }
            pluginBallViews.forEach { v ->
                val slot = runCatching { MainApp.instance.pluginRegistry.allWidgets().firstOrNull() }.getOrNull()
                if (slot != null) runCatching { slot.onBind(bundle) }
            }
            pluginSlots.forEach { slot -> runCatching { slot.onBind(bundle) } }
            // 实时输出区：有内容才显示
            val hasRt = reasoningBuffer.isNotEmpty() || contentBuffer.isNotEmpty()
            realTimeScroll.visibility = if (hasRt) View.VISIBLE else View.GONE
        }
    }

    /** 设置页 / ViewModel 推送 Token 统计文本到悬浮窗面板 */
    private fun setTokenText(text: String) {
        lastTokenText = text
        handler.post {
            if (!overlayReady) return@post
            tokenText.visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
            tokenText.text = text
        }
    }

    // ==================== ⭐ 实时输出（折叠 AI 思考 / 生成，自动滚底）====================

    /** 追加思考 / 生成增量到实时区（由 MainViewModel 在 delta 事件调用） */
    private fun pushStreaming(kind: String, fragment: String) {
        if (fragment.isEmpty()) return
        if (kind == "reasoning") {
            reasoningBuffer = (reasoningBuffer + fragment).takeLast(MAX_RT)
        } else {
            contentBuffer = (contentBuffer + fragment).takeLast(MAX_RT)
        }
        refreshRealTime()
    }

    /** 复位实时输出区（新一轮消息开始时调用） */
    private fun resetStreaming() {
        reasoningBuffer = ""
        contentBuffer = ""
        handler.post { if (overlayReady) refreshRealTime() }
    }

    private fun refreshRealTime() {
        handler.post {
            if (!overlayReady) return@post
            val sb = StringBuilder()
            if (reasoningBuffer.isNotEmpty()) {
                sb.append("💭 思考：").append('\n').append(reasoningBuffer).append("\n\n")
            }
            if (contentBuffer.isNotEmpty()) {
                sb.append("📝 生成：").append('\n').append(contentBuffer)
            }
            val has = sb.isNotEmpty()
            realTimeText.text = sb.toString()
            realTimeScroll.visibility = if (has) View.VISIBLE else View.GONE
            // ⭐ 内容增加 → 自动滚到底部
            realTimeScroll.post {
                realTimeScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

        /**
     * 统一拖拽 / 点击处理。
     * ⭐ 关键：挂在球、默认球、插件球、挂件、面板标题栏上（而非仅父容器 ballRow），
     * 因为 isClickable 的子 View 会消费 ACTION_DOWN，导致父容器 OnTouchListener 收不到后续 MOVE/UP。
     * 返回 true 阻止子 view 自身 onClick，点击逻辑统一在 ACTION_UP 处理。
     */
        private fun handleDrag(ev: MotionEvent): Boolean {
        val w = wm ?: return false
        val root = rootView ?: return false
        val p = params ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                startLpX = p.x; startLpY = p.y
                dragMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragMoved && (abs(dx) > 10f || abs(dy) > 10f)) dragMoved = true
                if (dragMoved) {
                    val dm = resources.displayMetrics
                    val rootW = root.width
                    val rootH = root.height
                    // ⭐ 边界 clamp（参考 AndLua 悬浮球示例的 math.max/min）：
                    //    拖到屏幕外会被弹回边界，保证悬浮窗始终可继续拖
                    val maxX = if (rootW > 0) (dm.widthPixels - rootW).coerceAtLeast(0) else 0
                    val maxY = if (rootH > 0) (dm.heightPixels - rootH).coerceAtLeast(0) else 0
                    p.x = (startLpX + dx.toInt()).coerceIn(0, maxX)
                    p.y = (startLpY + dy.toInt()).coerceIn(0, maxY)
                    runCatching { w.updateViewLayout(root, p) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasMoved = dragMoved
                dragMoved = false
                if (!wasMoved) toggleExpand(!expanded)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragMoved = false
                return true
            }
            else -> return false
        }
    }

    /** 镜像帧到达（MirrorService 推送） */
    private fun showFrame(bmp: Bitmap) {
        handler.post {
            if (!overlayReady) return@post
            mirrorView.setImageBitmap(bmp)
        }
    }

    /** 显示 / 隐藏镜像小窗（自动展开面板） */
    private fun setMirrorPanel(visible: Boolean) {
        handler.post {
            if (!overlayReady) return@post
            mirrorView.visibility = if (visible) View.VISIBLE else View.GONE
            mirrorCaption.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible && !expanded) toggleExpand(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_status"
        private const val NOTIF_ID = 1001

        @Volatile private var instance: FloatingService? = null
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            // ⭐ 单一实例：已运行则不重复创建（新进度悬浮窗由 onStartCommand 更新，不会叠加）
            if (isRunning) { runCatching { context.startService(Intent(context, FloatingService::class.java)) } ; return }
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
            // ⭐ 新一轮工作：复位实时输出区
            instance?.resetStreaming()
            update(state)
        }

                fun finishWork(context: Context, state: AgentState) {
            update(state)
            // ⭐ v1.0.0-Stable：悬浮窗常驻，AI 完成后不再自动消失，
            //    一直保留到用户手动点「✕ 关闭悬浮窗」或 App 被系统回收。
            //    新一轮工作到来时 showWork() 会幂等刷新同一实例。
        }

        /** 推送 Token 文本到面板（MainViewModel 在 Stats 事件调用） */
        fun setTokenText(context: Context, text: String) { instance?.setTokenText(text) }

        /** ⭐ 推送 AI 实时思考 / 生成增量（kind = "reasoning" / "content"） */
        fun pushStreaming(context: Context, kind: String, fragment: String) {
            instance?.pushStreaming(kind, fragment)
        }

        /** 显示 / 隐藏镜像小窗（ToolRegistry 在 accessibility_control 前后调用） */
        fun setMirrorActive(context: Context, active: Boolean) {
            val ins = instance
            if (active && ins == null) start(context)
            handlerFor(context).post { instance?.setMirrorPanel(active) }
            if (active) MirrorService.start(context) else MirrorService.stop(context)
        }

        /** 镜像帧推送入口（MirrorService 调用） */
        fun pushFrame(bmp: Bitmap) { instance?.showFrame(bmp) }

        /** 插件导入 / 启停 / 删除后重建球 + 挂件区（设置页调用） */
        fun rebuildWidgets(context: Context) {
            Handler(Looper.getMainLooper()).post { instance?.rebuildWidgets() }
        }

        private fun handlerFor(context: Context) = Handler(Looper.getMainLooper())
    }
}
