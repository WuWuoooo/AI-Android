package com.ai.android.util

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object ImageSaveUtil {

    /** 从 base64 保存图片到相册，返回文件路径 */
    fun saveBase64ToGallery(context: Context, base64: String, name: String = "ai_${System.currentTimeMillis()}.png"): String? {
        return runCatching {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIAndroid")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri.toString()
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AIAndroid")
                dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { it.write(bytes) }
                f.absolutePath
            }
        }.getOrNull()
    }
}