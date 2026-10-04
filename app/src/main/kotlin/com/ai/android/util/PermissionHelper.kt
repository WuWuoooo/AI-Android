package com.ai.android.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ai.android.service.AgentAccessibilityService

/**
 * 权限申请与状态检查（存储 / 通知 / 悬浮窗 / 无障碍）。
 */
object PermissionHelper {

    private const val REQ_STORAGE = 101
    private const val REQ_NOTIFICATION = 102

    // ---------- 存储 ----------

    fun hasStorage(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun requestStorage(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
                )
            }.onFailure {
                runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        }
    }

    // ---------- 通知 ----------

    fun hasNotification(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    fun requestNotification(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    // ---------- 悬浮窗 ----------

    fun hasOverlay(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun requestOverlay(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
            )
        }
    }

    // ---------- 无障碍 ----------

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (AgentAccessibilityService.instance != null) return true
        val service = "${context.packageName}/${AgentAccessibilityService::class.java.name}"
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return false
        return enabled.split(':').any { it.equals(service, ignoreCase = true) }
    }

    fun openAccessibilitySettings(activity: Activity) {
        runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    // ---------- 汇总 ----------

    data class Status(
        val storage: Boolean,
        val notification: Boolean,
        val overlay: Boolean,
        val accessibility: Boolean,
    )

    fun status(context: Context): Status = Status(
        storage = hasStorage(context),
        notification = hasNotification(context),
        overlay = hasOverlay(context),
        accessibility = isAccessibilityEnabled(context),
    )

    /** 缺失项的引导文案 */
    fun missingHints(status: Status): List<String> = buildList {
        if (!status.storage) add("存储权限：读写 /sdcard 文件")
        if (!status.notification) add("通知权限：定时任务结果提醒")
        if (!status.overlay) add("悬浮窗权限：Agent 后台状态显示")
        if (!status.accessibility) add("无障碍服务：手机界面自动化操控")
    }
}
