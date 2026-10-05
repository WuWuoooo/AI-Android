package com.ai.android.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.content.pm.ServiceInfo
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.android.MainActivity

/**
 * 屏幕镜像前台服务：MediaProjection + VirtualDisplay + ImageReader，
 * 以约 2fps 截取屏幕画面，推送给悬浮窗扩展面板（FloatingService）。
 *
 * 无投影授权（用户取消 / 未授权）时退化为"仅通知栏"模式：
 * 只显示「AI 正在操作中」通知。
 *
 * 启动前需通过 MediaProjectionBridge 透明 Activity 完成投屏授权。
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastFrameAt = 0L
    private var frameWidth = 0
    private var frameHeight = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        startForegroundSafe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setupProjection()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        teardown()
        isRunning = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 按当前授权状态建立 / 重建 VirtualDisplay（幂等） */
    private fun setupProjection() {
        runCatching {
            teardown()
            val pending = pendingIntent
            if (pending == null) {
                // 未授权投屏 → 仅通知栏模式
                return
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(Activity.RESULT_OK, pending)
            val dm = resources.displayMetrics
            frameWidth = 720
            frameHeight = (720 * dm.heightPixels / dm.widthPixels).coerceAtLeast(480)

            val mReader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2)
            // 帧可用监听器；注册走双参重载（API 26+），24/25 不注册（退化为通知栏模式）
            val frameListener = object : ImageReader.OnImageAvailableListener {
                override fun onImageAvailable(imageReader: ImageReader) {
                    // 限 2fps：500ms 内的帧直接丢弃
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt < 500L) {
                        imageReader.acquireLatestImage()?.close()
                        return
                    }
                    lastFrameAt = now
                    val img = imageReader.acquireLatestImage() ?: return
                    runCatching {
                        val buf = img.planes[0].buffer
                        buf.rewind()
                        val bmp = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(buf)
                        val sw = 400
                        val sh = (400 * frameHeight / frameWidth).coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                        if (scaled !== bmp) bmp.recycle()
                        FloatingService.pushFrame(scaled)
                    }.onFailure { }
                    img.close()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 显式双参重载（API 26+），彻底避免与单参版重载决议歧义
                mReader.setOnImageAvailableListener(frameListener, handler)
            }
            // API 24/25：无 handler 版监听器，不注册帧回调（推帧不可用），VirtualDisplay 仍创建，退化为通知栏模式
            reader = mReader

            // createVirtualDisplay(name, w, h, dpi, flags, surface, callback, handler)
            virtualDisplay = proj.createVirtualDisplay(
                "ai_mirror", frameWidth, frameHeight, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mReader.surface, null, handler
            )
            projection = proj

            val projCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post {
                        teardown()
                        stopSelf()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 显式双参重载（API 26+），彻底避免与单参版重载决议歧义
                proj.registerCallback(projCallback, handler)
            }
            // API 24/25：回调不注册，投影停止时不会自动 teardown（可接受，onDestroy 兜底）
        }.onFailure {
            // 建立失败退化为仅通知栏模式
            teardown()
        }
    }

    private fun teardown() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        // ⚠️ 不在此处关闭悬浮窗面板 / 停自身，避免与 onStartCommand→teardown 形成自停环。
        //    镜像面板的显隐统一由 AiControlGuard.startMirror / stopMirror 控制。
    }

    private fun startForegroundSafe() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "AI 操控 / 镜像", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val hasMirror = pendingIntent != null
                        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("AI 正在操作中")
                .setContentText(if (hasMirror) "实时镜像中（点击小窗可暂停）" else "后台操控中（未开启屏幕镜像）")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "ai_mirror"
        private const val NOTIF_ID = 1003

        @Volatile private var instance: MirrorService? = null
        @Volatile var isRunning: Boolean = false
            private set

        /** MediaProjectionBridge 授权成功后写入的投屏 Intent */
        @Volatile private var pendingIntent: Intent? = null

        fun setProjectionIntent(intent: Intent?) { pendingIntent = intent }
        fun hasProjection(): Boolean = pendingIntent != null

        /**
         * 启动镜像服务。
         * @param forceSetup 即使已运行也重新执行 setupProjection（授权补到位后升级用）
         */
        fun start(context: Context, forceSetup: Boolean = false) {
            if (isRunning && !forceSetup) return
            val i = Intent(context, MirrorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, i)
                } else {
                    context.startService(i)
                }
            }.onFailure { }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MirrorService::class.java)) }
            isRunning = false
        }
    }
}
