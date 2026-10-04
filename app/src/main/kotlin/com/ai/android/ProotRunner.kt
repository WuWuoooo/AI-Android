package com.ai.android.termux

import android.content.Context
import java.io.File

/**
 * 构建 proot 命令。
 *
 * ⭐ v1.0.0-Stable 终端优化：
 *  - 默认 [interactive] = true（终端 UI / 常驻 shell）：末尾 `bash -i`，
 *    开启交互 + job control，配合 PTY 可获得真 Ctrl-C/D/Z、jobs/fg/bg。
 *  - [interactive] = false：`bash -c "命令"`，一次性执行（向后兼容）。
 *
 * 关于 PTY / 真 Ctrl-C：
 *  - 纯管道（ProcessBuilder）架构下，bash -i 能处理"行编辑状态"的 Ctrl-C，
 *    但**无法给正在跑的前台子进程发 SIGINT**（信号生成依赖 tty/termios）。
 *  - 要完整信号（Ctrl-C 中断 sleep、Ctrl-Z 挂起、jobs/fg/bg、vim/top），
 *    需要 PTY，见 docs/PTY-终端接入指南.md（com.termux:terminal-view + libtermux.so）。
 *  - 这里已挂载 /dev 全量（含 ptmx/tty/urandom），为 PTY 升级铺路，不再单独 bind 易缺失的路径。
 */
class ProotRunner(private val context: Context) {

    private val root: File = BootstrapInstaller.installRoot(context)

    fun isReady(): Boolean {
        val proot = BootstrapInstaller.getProotPath(context) ?: return false
        return File(proot).exists()
            && File(root, "usr/bin").exists()
            && File(root, ".installed").exists()
    }

            /** 交互式常驻 shell（终端 UI 默认调用） */
    fun buildInteractiveCommand(): List<String> =
        buildCommand(userCommand = "bash", interactive = true)

                    /**
     * ⭐ 交互式 shell 的 PS1 初始化命令串（PtySessionManager 在 forkpty 后写入 pty 一次）。
     *  - 若用户 HOME/.bashrc 存在 → source 它（尊重用户自定义 alias / PS1）；
     *  - 否则 → 设一个 Termux 风格彩色提示符（绿色路径，HOME 下显示为 ~，结尾为美元符），
     *    让终端"仿真"有 ~/.bash 提示符，而不是默认的裸 bash 提示符。
     *
     * 注意（Kotlin 原始字符串）：字面美元符一律写 ${'$'}；提示符里的转义序列
     * （颜色 / 路径 / 非打印标记）直接写进下方原始字符串，原样保留、由 bash 提示符机制解释。
     *
     * ⚠️ 本文件注释里禁止再出现任何「反斜杠」开头的序列：KAPT 生成 Java stub 时注释原样保留，
     * Java 词法阶段会对全文做 unicode-escape 预扫描，注释里裸写 反斜杠+u 会报
     * "illegal unicode escape" 导致 kaptDebugKotlin 失败（字符串字面量里的反斜杠则会被
     * KAPT 正确转义，不受影响）。故下文用「反斜杠」文字指代，不直接打出来。
     */
        fun ps1InitCommand(): String = """
cd "${'$'}HOME" 2>/dev/null || true
if [ -f "${'$'}HOME/.bashrc" ]; then
    . "${'$'}HOME/.bashrc" 2>/dev/null
else
    PS1='\[\e[1;32m\]\w\[\e[0m\] ${'$'} '
    export PS1
fi
echo "AI Android PTY ready"
""".trimIndent()

    /**
     * 构建 proot 命令。
     * @param userCommand 在 guest 里执行的命令；interactive=true 时忽略（直接进 bash -i）
     * @param interactive 是否交互式（-i 开启交互 + job control）
     */
    fun buildCommand(userCommand: String, interactive: Boolean = true): List<String> {
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

            // Android 系统目录（/dev 全量映射：含 ptmx / tty / urandom，为 PTY 铺路）
            add("-b"); add("/system:/system")
            add("-b"); add("/apex:/apex")
            add("-b"); add("/dev:/dev")
            add("-b"); add("/proc:/proc")
            add("-b"); add("/sys:/sys")
            add("-b"); add("/sdcard:/sdcard")
            add("-b"); add("/vendor:/vendor")
            add("-b"); add("/product:/product")

            add("-w"); add(termuxPrefix)

            // ⭐ 交互式常驻：直接进 bash -i（带 job control + prompt），而非旧的 bash -c bash
            if (interactive) {
                add("$termuxPrefix/usr/bin/bash")
                add("--norc")
                add("-i")
            } else {
                add("$termuxPrefix/usr/bin/bash")
                add("--noprofile")
                add("--norc")
                add("-c"); add(userCommand)
            }
        }
    }
}
