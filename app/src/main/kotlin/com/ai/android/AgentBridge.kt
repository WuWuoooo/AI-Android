package com.ai.android

/**
 * ⭐ v1.2.0 #7.2：跨界面 / 跨进程「发消息给 Agent」桥。
 *
 * 悬浮窗（FloatingService，前台服务）需要**在 App 任何界面甚至其它 App 前台**
 * 直接给 Agent 发指令。Service 拿不到 per-Activity 的 MainViewModel，
 * 用一个进程级单例 holder 注册当前 MainViewModel，悬浮窗经此发消息 / 暂停。
 *
 * 同进程共享（Service 与 Activity 默认同进程），无 IPC 开销。
 */
object AgentBridge {

    /** 当前活跃的 MainViewModel（由 MainViewModel.init 注册，onCleared 释放） */
    @Volatile var vm: MainViewModel? = null

    fun setViewModel(instance: MainViewModel) {
        vm = instance
    }

    /** 悬浮窗发一条消息给 Agent（复用 MainViewModel.send 的发送 / 续跑逻辑） */
    fun postMessage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val v = vm
        if (v == null) return false
        // 切回主线程（悬浮窗按钮在 main 线程，这里稳妥再 hop 一次）
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { v.send(t) }.onFailure { }
        }
        return true
    }

    /** 悬浮窗暂停 Agent */
    fun stop() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { vm?.stop() }
        }
    }

    /** 是否有可接收消息的 ViewModel（悬浮窗据此决定是否禁用输入） */
    fun hasReceiver(): Boolean = vm != null
}
