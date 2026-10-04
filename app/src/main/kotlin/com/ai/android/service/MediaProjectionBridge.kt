package com.ai.android.service

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.ai.android.MainApp

/**
 * 透明 Activity：处理 MediaProjection 投屏授权弹窗。
 *
 * 授权成功 → 把 Intent 存入 [MirrorService] 并启动服务（镜像模式）；
 * 用户取消 → 仍启动服务，退化为"仅通知栏"模式。
 */
class MediaProjectionBridge : Activity() {

    private var waiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching {
            val intent = mpm.createScreenCaptureIntent()
            startActivityForResult(intent, REQ_CODE)
            waiting = true
        }.onFailure {
            MirrorService.start(MainApp.instance)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!waiting || requestCode != REQ_CODE) return
        waiting = false
        val app = MainApp.instance
        if (resultCode == Activity.RESULT_OK && data != null) {
            MirrorService.setProjectionIntent(data)
        } else {
            MirrorService.setProjectionIntent(null)
        }
        // forceSetup：即使已在"仅通知栏"模式也升级
        MirrorService.start(app, forceSetup = true)
        finish()
    }

    companion object {
        private const val REQ_CODE = 1001
    }
}
