package com.ai.android.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ⭐ v1.1.0 #2 + v1.2.0-hotfix #2：Shizuku 提权管理器（宿主侧，官方 API 对齐版）。
 *
 * 宿主侧引入的是 `dev.rikka.shizuku:api` + `:provider`（`shizuku-service` 是 Shizuku 自己的
 * 服务端模块，宿主**不**引入）。
 *
 * 本类做两件事：
 *  1. **授权打通**：让本 App 出现在 Shizuku 的「可授权 App」列表并能请求授权。
 *     前提是 AndroidManifest.xml 声明了 `<provider rikka.shizuku.ShizukuProvider .../>`。
 *     授权走官方 `Shizuku.requestPermission` / `checkSelfPermission`。
 *  2. **命令执行入口** [executeShell]：未授权/未装时回退 app 沙箱本地 `sh -c`。
 *
 * ⭐ v1.2.0-hotfix #2（对照官方 demo 修复）：
 *  - `initForApp()` 在 `MainApp.onCreate` 调用（比 Activity.onCreate 更早）：
 *    ① `Sui.init(packageName)` —— 支持 root 模式的 Sui
 *    ② `addBinderReceivedListenerSticky` —— 若 binder 已在 App 启动时经 Provider 推入，
 *       非 sticky 监听永远不会触发（我们旧代码在 Activity.onCreate 才注册）；
 *       sticky 版会**立即补发**，修复"授权列表看不到 / binder 到不了"
 *    ③ `addBinderDeadListener` —— 日志追踪
 *  - `isAvailable` / `ensureAuthorized` 先查 `Shizuku.isPreV11()`（v12+ 不支持旧版）
 *  - `ensureAuthorized` 加 `shouldShowRequestPermissionRationale()` 诊断
 *
 * TODO（后续）：真·以 shell uid 2000 执行命令（AIDL user-service + desugaring）。
 */
object ShizukuManager {
            private const val TAG = "ShizukuManager"
    /** ⭐ hotfix#2-fix：Shizuku 各变体包名（不同来源/版本包名不同，全量列出来命中即视为已装）
     *  真机 `pm list packages` 实测：GitHub 下载装出来是 moe.shizuku.privileged.api
     *  官方主 App（老版/新版）：moe.shizuku.manager；root 版 Sui：rikka.sui */
    private const val SHIZUKU_API_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"
    private const val SHIZUKU_OLD_PACKAGE = "moe.shizuku.privileged"
    private const val SUI_PACKAGE = "rikka.sui"
    private const val REQ_CODE = 1001

    /** 全量并集：任一存在即视为已装（真机诊断确认主包名在前） */
    private val SHIZUKU_PACKAGES = listOf(
        SHIZUKU_API_PACKAGE,
        SHIZUKU_MANAGER_PACKAGE,
        SHIZUKU_OLD_PACKAGE,
        SUI_PACKAGE,
    )

        // ⭐ hotfix #2：binder listener 注册标志（Application 层 + Activity 层共享）
    @Volatile private var listenersRegistered = false
    @Volatile private var suiInitialized = false

    // ⭐ hotfix #2（A）：user-service 真提权（以 shell uid 2000 / root uid 0 执行命令）
    /** App 级 context（供 binder listener 回调里请求绑定 user-service 用） */
    @Volatile private var appContext: Context? = null
    /** 跨进程 AIDL 代理（user-service 连上后由 onServiceConnected 赋值） */
        @Volatile private var shellServiceProxy: IShellService? = null
    /** ⭐ 10s 节流：上次尝试 bindUserService 的时间戳（不再用永久锁——首次 NPE/时序失败后还能重试） */
    @Volatile private var lastBindAttemptMs = 0L

    /** user-service 的 ServiceConnection：onServiceConnected 拿到 binder 后转成 IShellService 代理 */
    private val shellServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (binder != null && binder.pingBinder()) {
                shellServiceProxy = IShellService.Stub.asInterface(binder)
                Log.d(TAG, "user-service connected, proxy ready")
            } else {
                Log.w(TAG, "user-service onServiceConnected: invalid binder")
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "user-service disconnected")
            shellServiceProxy = null
        }
    }

            /** 构造 UserServiceArgs（指向本 App 的 ShizukuShellService；version 1）
     *  ⭐ processNameSuffix 必填：bindUserService 内部 forAdd 会 requireNonNull(processNameSuffix)，
     *  漏掉会 NPE（真机日志已确认）；对照官方 demo 用 "service" */
    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs {
        // ComponentName 只有 (String, String) 构造器（Kotlin 无 Class 重载）
        val cn = ComponentName(context.packageName, ShizukuShellService::class.java.name)
        return Shizuku.UserServiceArgs(cn)
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(true)
            .version(1)
    }

        /** 请求绑定 user-service（10s 节流可重试）。
     *  ⭐ 已连上 / 刚尝试过（<10s）直接返回，避免频繁 bind；否则发起。
     *  不再用永久锁：首次 NPE（如漏 processNameSuffix）或时序失败后，仍能自动重试 */
    private fun requestBindShellService() {
        if (shellServiceProxy != null) return              // 已连上
        val now = System.currentTimeMillis()
        if (now - lastBindAttemptMs < 10_000L) return       // 10s 节流
        lastBindAttemptMs = now
        val ctx = appContext ?: return
        runCatching {
            Shizuku.bindUserService(userServiceArgs(ctx), shellServiceConnection)
            Log.d(TAG, "bindUserService 已发起（节流 $now）")
        }.onFailure {
            Log.w(TAG, "bindUserService 失败（binder 未连接 / 参数错 / 服务未启动），10s 后可重试", it)
        }
    }

    /**
     * ⭐ v1.2.0-hotfix #2：在 [MainApp.onCreate] 调用（比 Activity.onCreate 早）。
     *
     * 做三件事（对照官方 demo）：
     *  1. [Sui.init] —— 支持 root 模式的 Sui（v12.1.0+ ShizukuProvider 会自动调，但显式调一次更稳）
     *  2. [Shizuku.addBinderReceivedListenerSticky] —— 若 binder 已在 Provider.onCreate 推入，
     *     非 sticky 监听不会补发 → 这里用 sticky 保证能收到
     *  3. [Shizuku.addBinderDeadListener] —— binder 断开时打日志
     */
        fun initForApp(context: Context) {
        // 保存 appContext（供 binder listener 回调里请求绑定 user-service）
        appContext = context.applicationContext

        // 1. Sui.init（官方 demo Application 静态块做法；Kotlin Application 没静态块，在 onCreate 调）
        if (!suiInitialized) {
            suiInitialized = true
            runCatching {
                val isSui = Sui.init(context.packageName)
                Log.d(TAG, "Sui.init(${context.packageName}) = $isSui")
            }.onFailure { Log.w(TAG, "Sui.init 失败", it) }
        }

        // 2. binder listener（必须 sticky！binder 经 Provider 在 Application.onCreate 之前推入）
        if (!listenersRegistered) {
            listenersRegistered = true
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
                    override fun onBinderReceived() {
                        Log.d(TAG, "Shizuku binder received (sticky), isAvailable=" + isAvailableInternal())
                        // ⭐ binder 到达 → 此时可请求绑定 user-service（真提权通道）
                        requestBindShellService()
                    }
                })
                Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                    override fun onBinderDead() {
                        Log.w(TAG, "Shizuku binder dead, 清除 user-service 代理")
                        shellServiceProxy = null
                    }
                })
            }.onFailure { Log.w(TAG, "addBinderReceivedListenerSticky 失败（API 差异）", it) }
        }

        // 3. 启动时立即 ping 一次（若 Shizuku 服务已在跑则 binder 已收到 → 直接请求绑定）
        runCatching {
            if (Shizuku.pingBinder()) {
                Log.d(TAG, "Shizuku binder already connected at initForApp, 请求绑定 user-service")
                requestBindShellService()
            } else {
                Log.d(TAG, "Shizuku binder not yet connected (server may not be running)")
            }
        }
    }

    /** 是否检测到已安装 Shizuku（或 Sui） */
    fun isInstalled(context: Context): Boolean {
        val detail = SHIZUKU_PACKAGES.map { pkg ->
            val found = runCatching {
                context.packageManager.getPackageInfo(pkg, 0); true
            }.getOrDefault(false)
            Log.d(TAG, "isInstalled 查询 $pkg -> $found")
            found
        }
        val installed = detail.any { it }
        if (!installed) Log.w(TAG, "isInstalled=false（三个 Shizuku/Sui 包名均未查到）")
        return installed
    }

    /**
     * 当前 Shizuku 是否**可用**（binder 已收到 + 本 App 已被 Shizuku 授权）。
     * 未启动 Shizuku / 未授权 / binder 未连接时返回 false。
     *
     * ⭐ hotfix #2：先查 isPreV11（v12+ 不支持旧版），再查 binder + 授权。
     */
    fun isAvailable(context: Context): Boolean = runCatching {
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku pre-v11 not supported (v12+ API 已弃用)")
            return@runCatching false
        }
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 内部：binder + 授权检查（不查 isPreV11，供 listener 回调里用） */
    private fun isAvailableInternal(): Boolean = runCatching {
        if (Shizuku.isPreV11()) return@runCatching false
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Activity 回到前台时复检 binder + 授权，写回 SettingsRepository。
     */
    fun recheck(context: Context) {
        val available = isAvailable(context)
        runCatching {
            val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("shizuku_enabled", false) != available) {
                prefs.edit().putBoolean("shizuku_enabled", available).apply()
                Log.d(TAG, "recheck: shizuku_enabled -> $available")
            }
        }
    }

    /**
     * 请求 Shizuku 授权（在 Activity 调用）。
     *
     * ⭐ hotfix #2：对齐官方 demo checkPermission 流程：
     *  isPreV11 → false；已授权 → true；shouldShowRationale → false（用户选了"不再询问"）；
     *  否则 requestPermission 弹 Shizuku 授权对话框。
     */
    fun ensureAuthorized(context: Context): Boolean {
        if (!isInstalled(context)) {
            android.widget.Toast.makeText(
                context, "未检测到 Shizuku / Sui，请先安装并启动它",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            openShizukuApp(context)
            return false
        }
        if (isAvailable(context)) return true

        // ⭐ 先查 isPreV11
        runCatching {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku pre-v11，不支持 v12+ API，请升级 Shizuku")
                return false
            }
        }

        // 诊断：binder 是否可达
        val binderOk = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        Log.d(TAG, "ensureAuthorized: binderOk=$binderOk, 尝试 requestPermission")
        if (!binderOk) {
            Log.w(TAG, "binder 不可达（Shizuku 服务未启动或本 App 未重启）")
        }

        // 官方 demo 流程：shouldShowRequestPermissionRationale → 用户选过"不再询问"
        val deniedForever = runCatching {
            Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)
        if (deniedForever) {
            Log.w(TAG, "shouldShowRequestPermissionRationale=true：用户之前选了'不再询问'")
            android.widget.Toast.makeText(
                context, "Shizuku 授权被拒绝过，请到 Shizuku App 里手动勾选本 App",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }

        runCatching { Shizuku.requestPermission(REQ_CODE) }
            .onSuccess { Log.d(TAG, "requestPermission 已发起") }
            .onFailure { Log.w(TAG, "requestPermission 失败（binder 未连接 / 服务未启动）", it) }
        openShizukuApp(context)
        return isAvailable(context)
    }

        /** 打开 Shizuku 主界面 */
    fun openShizukuApp(context: Context) {
        runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_API_PACKAGE, 0)
            context.startActivity(
                Intent(Intent.ACTION_MAIN).setPackage(SHIZUKU_API_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).setPackage(SHIZUKU_OLD_PACKAGE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$SHIZUKU_OLD_PACKAGE"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    /**
     * 执行命令：可用且 user-service 代理就绪时，**经 Shizuku 以 shell uid 2000 真执行**
     * （突破 app 沙箱，可读 /sdcard/Android/data）；否则回退 app 沙箱本地 `sh -c`。
     */
    fun executeShell(context: Context, command: String, timeoutSec: Int = 60): String {
        // ⭐ 真提权路径：user-service 代理已连上（shell uid 2000 进程在跑）→ 跨进程经 AIDL 执行
        val proxy = shellServiceProxy
        if (proxy != null && isAvailable(context)) {
            return runCatching {
                proxy.execute(command, timeoutSec)
            }.getOrElse {
                Log.w(TAG, "user-service 调用失败，降级沙箱", it)
                "⚠️ Shizuku user-service 调用失败（" + it.message?.take(60) + "），已降级 app 沙箱\n" +
                    runSandboxShell(command, timeoutSec)
            }
        }

        if (isAvailable(context)) {
            // 已授权但 user-service 代理未连上（首次绑定中 / user-service 进程未起）
            requestBindShellService()
            return "✅ 已通过 Shizuku 授权（user-service 绑定中，本轮经本地沙箱 sh；后续走 shell 提权）\n" +
                runSandboxShell(command, timeoutSec)
        }
        val note = if (isInstalled(context))
            "⚠️ Shizuku 已装但未授权/服务未启动，已回退 app 沙箱（请先到 设置→Shizuku 权限 点「请求授权」）\n"
        else
            "⚠️ 未安装 Shizuku，使用 app 沙箱执行（无法写 Android/data）\n"
        return note + runSandboxShell(command, timeoutSec)
    }

    private fun runSandboxShell(command: String, timeoutSec: Int): String {
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
            proc.destroyForcibly(); t.join(2000)
            return "⏱ 命令超时（${timeoutSec}s）已终止\n--- 部分输出 ---\n${synchronized(output) { output.toString() }.take(8000)}"
        }
        t.join(2000)
        val exit = proc.exitValue()
        val text = synchronized(output) { output.toString() }.take(10_000)
        val head = if (exit == 0) "✅ 退出码: 0" else "⚠️ 退出码: $exit"
        return if (text.isBlank()) "$head\n（无输出）" else "$head\n$text"
    }

    fun statusText(context: Context): String = when {
        !isInstalled(context) -> "未安装 Shizuku（功能不可用，自动回退 app 沙箱）"
        !isAvailable(context) -> "已安装，未授权/服务未启动（自动回退 app 沙箱）"
        else -> "已授权，提权通道可用"
    }
}
