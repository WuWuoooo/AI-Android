package com.ai.android.termux

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object BootstrapInstaller {

    private const val TAG = "BootstrapInstaller"
    private const val ROOT_DIR = "termux"
    private const val MARKER = ".installed"
    private const val PROOT_PATH_FILE = ".proot_path"
    private const val APT_FIX_MARK = ".apt_fixed"

    private val EXTERNAL_ZIP_PATHS = listOf(
        "/sdcard/AiAndroid/bootstrap.zip",
        "/sdcard/Download/bootstrap-aarch64.zip",
    )
    private val EXTERNAL_PROOT_PATHS = listOf(
        "/sdcard/AiAndroid/proot",
        "/sdcard/Download/proot",
    )

    /** 国内镜像源（Termux 官方 & 第三方） */
    private val TERMUX_MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
        "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
        "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
    )

    data class InstallResult(
        val success: Boolean,
        val message: String,
        val rootFsPath: String = "",
        val prootPath: String = "",
    )

    fun installRoot(context: Context): File = File(context.filesDir, ROOT_DIR)

    fun isInstalled(context: Context): Boolean {
        val root = installRoot(context)
        if (!File(root, MARKER).exists()) return false
        if (!File(root, PROOT_PATH_FILE).exists()) return false
        if (!File(root, "usr/bin").exists()) return false

        // 每次启动都跑一遍（幂等）
        runCatching {
            val n = ensureVersionedSonames(root)
            if (n > 0) Log.i(TAG, "启动时补链 $n 个")
        }.onFailure { Log.e(TAG, "ensureVersionedSonames 失败", it) }

        runCatching {
            fixAptConfig(root)
        }.onFailure { Log.e(TAG, "fixAptConfig 失败", it) }

        return true
    }

    /** ⭐ 获取 proot 真实路径：优先 nativeLibraryDir */
    fun getProotPath(context: Context): String? {
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (nativeLib.exists() && nativeLib.length() > 10_000) {
            return nativeLib.absolutePath
        }
        val marker = File(installRoot(context), PROOT_PATH_FILE)
        if (marker.exists()) {
            val p = marker.readText().trim()
            if (p.isNotBlank() && File(p).exists()) return p
        }
        val fallback = File(installRoot(context), "proot")
        if (fallback.exists() && fallback.length() > 10_000) return fallback.absolutePath
        return null
    }

    private fun bootstrapAssetForDevice(): String {
        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else ""
        return if (abi.contains("arm64")) "bootstrap-aarch64.zip" else "bootstrap-arm.zip"
    }

    suspend fun install(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        var tmpZip: File? = null
        try {
            val root = installRoot(context)
            onProgress("清理旧文件…")
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()

            // 1. 准备 zip
            tmpZip = File(context.cacheDir, "bootstrap_tmp.zip")
            if (tmpZip.exists()) tmpZip.delete()

            val external = EXTERNAL_ZIP_PATHS.map { File(it) }.firstOrNull { it.exists() }
            val sourceDesc: String
            if (external != null) {
                external.copyTo(tmpZip, overwrite = true)
                sourceDesc = "外部文件（${external.absolutePath}）"
            } else {
                val assetName = bootstrapAssetForDevice()
                context.assets.open(assetName).use { input ->
                    FileOutputStream(tmpZip).use { output -> input.copyTo(output) }
                }
                sourceDesc = "assets/$assetName"
            }

            val sizeBytes = tmpZip.length()
            Log.i(TAG, "zip 来源: $sourceDesc, 大小: $sizeBytes 字节")

            if (sizeBytes < 10_000_000) {
                return@withContext InstallResult(false,
                    "bootstrap 文件只有 $sizeBytes 字节（正常约 30MB）")
            }

            // 2. 解压
            onProgress("解压 $sourceDesc …")
            val extractedCount = unzipWithZipFile(tmpZip, root)
            Log.i(TAG, "解压完成，共 $extractedCount 个条目")

            if (extractedCount < 100) {
                return@withContext InstallResult(false, "解压条目数异常（$extractedCount）")
            }
            if (!File(root, "usr/bin").exists()) {
                return@withContext InstallResult(false, "解压后缺少 usr/bin")
            }

            // 3. SYMLINKS
            onProgress("创建符号链接…")
            runCatching { createSymlinks(root) }

            // 4. 补 soname 软链
            onProgress("修复库文件软链…")
            val fixed = runCatching { ensureVersionedSonames(root) }.getOrDefault(0)
            Log.i(TAG, "sonames 补链 $fixed 个")

            // 5. 权限
            onProgress("设置执行权限…")
            runCatching {
                setExecutablePermissions(File(root, "usr/bin"))
                setExecutablePermissions(File(root, "usr/libexec"))
                setExecutablePermissions(File(root, "usr/lib/apt/methods"))
            }

            // 6. 修复 apt 配置（DNS + https 驱动 + 禁用 GPG 校验 + 换国内源）
            onProgress("配置 apt…")
            runCatching { fixAptConfig(root) }

            // 7. proot
            onProgress("查找 proot …")
            val prootPath = findProotSource(context)
                ?: return@withContext InstallResult(
                    false,
                    "未找到 proot。请把 proot 放到 app/src/main/jniLibs/arm64-v8a/libproot.so"
                )
            File(root, PROOT_PATH_FILE).writeText(prootPath)
            Log.i(TAG, "proot 路径：$prootPath")
            onProgress("proot：$prootPath")

            // 8. 写标记
            File(root, MARKER).writeText("installed@${System.currentTimeMillis()}")
            File(root, APT_FIX_MARK).writeText("fixed@${System.currentTimeMillis()}")

            onProgress("完成")
            InstallResult(
                success = true,
                message = "Termux 环境安装成功（$sourceDesc，$extractedCount 个文件，补链 $fixed 个）\nproot: $prootPath",
                rootFsPath = File(root, "usr").absolutePath,
                prootPath = prootPath,
            )
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            InstallResult(false, "安装失败: ${e.message}")
        } finally {
            runCatching { tmpZip?.delete() }
        }
    }

    private fun findProotSource(context: Context): String? {
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (nativeLib.exists() && nativeLib.length() > 10_000) {
            Log.i(TAG, "proot 来源：nativeLibraryDir（${nativeLib.length()} 字节）")
            return nativeLib.absolutePath
        }
        val external = EXTERNAL_PROOT_PATHS.map { File(it) }
            .firstOrNull { it.exists() && it.length() > 10_000 }
        if (external != null) {
            Log.w(TAG, "proot 来源：外部文件 ${external.absolutePath}（可能被 SELinux 拦截）")
            return external.absolutePath
        }
        return null
    }

    suspend fun uninstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching { installRoot(context).deleteRecursively() }.getOrDefault(false)
    }

    // ==================== 内部工具 ====================

    private fun unzipWithZipFile(zip: File, outDir: File): Int {
        var count = 0
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val rawName = entry.name

                val relativeName = when {
                    rawName == "SYMLINKS.txt" -> "SYMLINKS.txt"
                    rawName.startsWith("usr/") -> rawName
                    else -> "usr/$rawName"
                }

                val outFile = File(outDir, relativeName)
                if (!outFile.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) {
                    throw SecurityException("Zip entry 路径越界: $rawName")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                }
                count++
            }
        }
        return count
    }

    private fun createSymlinks(root: File) {
        val symlinksFile = File(root, "SYMLINKS.txt")
        if (!symlinksFile.exists()) {
            Log.w(TAG, "SYMLINKS.txt 不存在，跳过")
            return
        }
        val oldPrefix = "/data/data/com.termux/files"
        var ok = 0; var fail = 0
        symlinksFile.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split("←")
                if (parts.size != 2) return@forEach
                val target = parts[0].trim()
                val linkRaw = parts[1].trim()
                val linkPath = linkRaw.replace(oldPrefix, root.absolutePath)
                val targetPath = target.replace(oldPrefix, root.absolutePath)
                val linkFile = File(linkPath)
                linkFile.parentFile?.mkdirs()
                val r = runCatching {
                    if (linkFile.exists()) linkFile.delete()
                    java.nio.file.Files.createSymbolicLink(
                        linkFile.toPath(), File(targetPath).toPath(),
                    )
                }
                if (r.isSuccess) ok++ else fail++
            }
        }
        Log.i(TAG, "symlinks: ok=$ok fail=$fail")
    }

    /**
     * ⭐ 逐段去尾，把所有带版本号的 .so 生成中间版本软链
     *    例：libbz2.so.1.0.8 → libbz2.so.1.0 / libbz2.so.1 / libbz2.so
     */
    private fun ensureVersionedSonames(root: File): Int {
        val libDirs = listOf(
            File(root, "usr/lib"),
            File(root, "usr/libexec"),
            File(root, "usr/lib/apt/methods"),
        )
        val soNameRe = Regex("""^(lib.+?\.so)((?:\.[0-9]+)+)$""")
        var created = 0
        var scanned = 0

        libDirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val isSymlink = runCatching {
                    java.nio.file.Files.isSymbolicLink(f.toPath())
                }.getOrDefault(false)
                if (isSymlink) return@forEach

                val m = soNameRe.matchEntire(f.name) ?: return@forEach
                scanned++
                val base = m.groupValues[1]
                val versions = m.groupValues[2]
                val parts = versions.trimStart('.').split('.')

                for (keep in parts.size - 1 downTo 0) {
                    val candidate = if (keep == 0) base
                                    else "$base.${parts.take(keep).joinToString(".")}"
                    val target = File(f.parentFile, candidate)
                    if (target.exists()) continue

                    val linked = runCatching {
                        java.nio.file.Files.createSymbolicLink(target.toPath(), f.toPath())
                    }.isSuccess
                    if (!linked) {
                        runCatching { f.copyTo(target, overwrite = false) }
                    }
                    if (target.exists()) created++
                }
            }
        }
        Log.i(TAG, "ensureVersionedSonames: 扫描 $scanned 个版本化 .so，新增 $created 个短名")
        return created
    }

    /**
     * ⭐ 修复 apt 配置：
     *   1. 写 DNS（解决 Failed to fetch）
     *   2. 确保 https 方法驱动存在
     *   3. 关闭 GPG 签名校验（proot 里 apt-key 常跑不起来）
     *   4. 换国内源（加速）
     */
    private fun fixAptConfig(root: File) {
        val prefix = File(root, "usr")
        if (!prefix.isDirectory) {
            Log.w(TAG, "usr 目录不存在，跳过 apt 配置")
            return
        }

        // ---------- 1. DNS ----------
        val etcDir = File(prefix, "etc")
        etcDir.mkdirs()
        val resolvConf = File(etcDir, "resolv.conf")
        if (!resolvConf.exists() || resolvConf.readText().isBlank()) {
            resolvConf.writeText(
                "nameserver 8.8.8.8\n" +
                "nameserver 1.1.1.1\n" +
                "nameserver 114.114.114.114\n"
            )
            Log.i(TAG, "DNS 已写入")
        }

        // ---------- 2. https 方法驱动 ----------
        val methodsDir = File(prefix, "lib/apt/methods")
        if (methodsDir.isDirectory) {
            val httpsFile = File(methodsDir, "https")
            val httpFile = File(methodsDir, "http")

            if (httpFile.exists()) {
                if (!httpsFile.exists()) {
                    val linked = runCatching {
                        java.nio.file.Files.createSymbolicLink(httpsFile.toPath(), httpFile.toPath())
                    }.isSuccess
                    if (!linked) {
                        runCatching { httpFile.copyTo(httpsFile, overwrite = false) }
                    }
                    Log.i(TAG, "https 方法驱动已创建")
                }
                runCatching {
                    httpsFile.setExecutable(true, false)
                    httpFile.setExecutable(true, false)
                }
            }
        }

        // ---------- 3. 关闭 GPG 校验 ----------
        // 3a. 全局配置：允许不安全的仓库
        val aptConfD = File(etcDir, "apt/apt.conf.d")
        aptConfD.mkdirs()
        File(aptConfD, "99no-gpg-check").writeText(
            """
            // 由 AI Android 自动生成：关闭 GPG 校验
            Acquire::AllowInsecureRepositories "true";
            Acquire::AllowDowngradeToInsecureRepositories "true";
            APT::Get::AllowUnauthenticated "true";
            APT::Get::AllowInsecureRepositories "true";
            Acquire::Check-Valid-Until "false";
            """.trimIndent()
        )
        Log.i(TAG, "GPG 校验已关闭")

        // ---------- 4. 换国内源 + 加 [trusted=yes] ----------
        val sourcesList = File(etcDir, "apt/sources.list")
        sourcesList.parentFile?.mkdirs()

        val mirror = TERMUX_MIRRORS.first()
        val newContent = buildString {
            appendLine("# 由 AI Android 自动生成：trusted=yes 跳过 GPG 校验")
            appendLine("deb [trusted=yes] $mirror stable main")
            appendLine("deb [trusted=yes] $mirror stable main")
        }
        sourcesList.writeText(newContent)
        Log.i(TAG, "sources.list 已重写：$mirror")

        // 保险起见，把 sources.list.d 里原有的也标记为 trusted
        val sourcesListD = File(etcDir, "apt/sources.list.d")
        if (sourcesListD.isDirectory) {
            sourcesListD.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".list")) {
                    runCatching {
                        val lines = f.readLines().map { line ->
                            val t = line.trim()
                            when {
                                t.isBlank() || t.startsWith("#") -> line
                                t.startsWith("deb ") && !t.contains("trusted=yes") ->
                                    t.replaceFirst("deb ", "deb [trusted=yes] ")
                                else -> line
                            }
                        }
                        f.writeText(lines.joinToString("\n"))
                    }
                }
            }
        }
    }

    private fun setExecutablePermissions(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                runCatching { f.setExecutable(true, false) }
                setExecutablePermissions(f)
            } else {
                runCatching { f.setExecutable(true, false) }
            }
        }
    }
}