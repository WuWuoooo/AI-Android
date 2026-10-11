package com.ai.android.service

import android.content.Context
import android.os.RemoteException
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ⭐ v1.2.0-hotfix #2（A）：Shizuku user-service —— 以 shell(uid 2000) 身份执行命令。
 *
 * 工作原理（对照官方 demo/rikka/shizuku/demo/service/UserService.java）：
 *  - 本 Service 由 Shizuku 服务端在**独立的 user-service 进程**里实例化（非本 App 进程），
 *    该进程跑在 shell uid 2000（ADB）或 root uid 0（root）下。
 *  - 宿主侧（本 App）经 `Shizuku.bindUserService(UserServiceArgs, ServiceConnection)` 绑定；
 *    绑定后 `ServiceConnection.onServiceConnected` 拿到 `IBinder`，用
 *    `IShellService.Stub.asInterface(binder)` 拿到跨进程代理，调 [execute] 即经 AIDL 在
 *    user-service 进程里真执行命令。
 *  - 命令在该 shell 进程里跑，因此能突破 App 沙箱读 `/sdcard/Android/data`。
 *
 * 约定（Shizuku user-service）：
 *  - **必须有默认无参构造器**（旧版 Shizuku 反射实例化用它）。
 *  - v13 起可选带 `Context` 构造器（需 @Keep 防 ProGuard 删掉）。
 *  - [destroy] 是 Shizuku 保留方法（AIDL 里事务码 16777114），server unbind 时调用，
 *    最后必须 `System.exit(0)` 结束 user-service 进程（它不会自动被杀）。
 */
class ShizukuShellService() : IShellService.Stub() {

    companion object {
        private const val TAG = "ShizukuShellService"
    }

    /** ⭐ 旧版 Shizuku 反射实例化用的无参主构造器（primary） */
    init {
        Log.i(TAG, "ShizukuShellService() primary, uid=" + runCatching { Os.getuid() }.getOrNull())
    }

    /** ⭐ Shizuku v13 起支持带 Context 构造器；secondary 委托到 primary，@Keep 防 ProGuard 删除 */
    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "ShizukuShellService(Context), uid=" + runCatching { Os.getuid() }.getOrNull())
    }

    /**
     * ⭐ 核心方法（跨进程经 AIDL）：在 user-service 进程（shell uid 2000 / root uid 0）里执行命令。
     * 由宿主侧 [ShizukuManager] 经 bindUserService 调用。返回「退出码 + 输出」文本。
     */
    @Throws(RemoteException::class)
    override fun execute(command: String, timeoutSec: Int): String {
        val uid = runCatching { Os.getuid() }.getOrNull()
        val pid = runCatching { Os.getpid() }.getOrNull()
        Log.d(TAG, "execute(uid=$uid, pid=$pid) timeout=${timeoutSec}s cmd=${command.take(120)}")
        val output = execShell(command, if (timeoutSec > 0) timeoutSec else 60)
        return "✅ 经 Shizuku user-service 以 uid ${uid ?: "?"}（shell=2000 / root=0）执行\n$output"
    }

    /** 在 shell 身份下跑 `sh -c`（工作目录 /sdcard，与本地沙箱一致，便于对比） */
    private fun execShell(command: String, timeoutSec: Int): String {
        val proc = try {
            ProcessBuilder("sh", "-c", command)
                .directory(File("/sdcard"))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return "❌ 启动命令失败: ${e.message}"
        }
        val output = StringBuilder()
        val t = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { ls ->
                    ls.forEach { line ->
                        synchronized(output) { if (output.length < 20_000) output.append(line).append('\n') }
                    }
                }
            }
        }
        t.start()
        val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            t.join(2000)
            return "⏱ 命令超时（${timeoutSec}s）已终止\n--- 部分输出 ---\n${synchronized(output) { output.toString() }.take(8000)}"
        }
        t.join(2000)
        val exit = proc.exitValue()
        val text = synchronized(output) { output.toString() }.take(10_000)
        val head = if (exit == 0) "✅ 退出码: 0" else "⚠️ 退出码: $exit"
        return if (text.isBlank()) "$head\n（无输出）" else "$head\n$text"
    }

    /**
     * Shizuku 保留方法：server 在 unbind 时调（AIDL 事务码 16777114）。
     * user-service 进程**不会**被自动杀，必须手动 System.exit(0) 结束。
     */
    override fun destroy() {
        Log.i(TAG, "destroy() -> System.exit(0)")
        System.exit(0)
    }
}
