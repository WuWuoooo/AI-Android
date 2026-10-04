package com.ai.android.termux

import android.content.Context
import java.io.File

class ProotRunner(private val context: Context) {

    private val root: File = BootstrapInstaller.installRoot(context)

    fun isReady(): Boolean {
        val proot = BootstrapInstaller.getProotPath(context) ?: return false
        return File(proot).exists()
            && File(root, "usr/bin").exists()
            && File(root, ".installed").exists()
    }

    fun buildCommand(userCommand: String): List<String> {
        val prootPath = BootstrapInstaller.getProotPath(context)
            ?: throw IllegalStateException("proot 未安装，请先到设置 → Termux 环境安装")

        val hostRoot = root.absolutePath
        val termuxPrefix = "/data/data/com.termux/files"

        return buildList {
            add(prootPath)
            add("--link2symlink")

            // ⭐ 去掉 -0：让 guest 保持真实 app uid，
            //    Termux 的 pkg 脚本检测 uid==0 会拒绝运行

            // rootfs = host 的 /
            add("-b"); add("$hostRoot:$termuxPrefix")
            add("-b"); add("$hostRoot/usr:/usr")

            // Android 系统目录
            add("-b"); add("/system:/system")
            add("-b"); add("/apex:/apex")
            add("-b"); add("/dev:/dev")
            add("-b"); add("/proc:/proc")
            add("-b"); add("/sys:/sys")
            add("-b"); add("/sdcard:/sdcard")
            add("-b"); add("/vendor:/vendor")
            add("-b"); add("/product:/product")

            add("-w"); add(termuxPrefix)

            add("$termuxPrefix/usr/bin/bash")
            add("--noprofile")
            add("--norc")
            add("-c"); add(userCommand)
        }
    }
}