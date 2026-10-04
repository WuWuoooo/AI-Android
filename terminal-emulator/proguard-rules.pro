# terminal-emulator ProGuard / R8 规则（consumer，会被 app 的 release 包消费）
#
# ⭐ 关键：JNI.java 含 native 方法（对应 jni/termux.c）+ System.loadLibrary("termux")。
#   release 开 minify 后若 R8 重命名该类的 native 方法签名，native 链接会失败。
#   保留整个 JNI 类与 native 方法。
-keep class com.termux.terminal.JNI { *; }

# TerminalSession / TerminalEmulator 等核心类被 Kotlin 侧直接 new 与调用，
# 保留 public 成员，避免被优化掉反射/接口调用点。
-keep class com.termux.terminal.TerminalSession { *; }
-keep class com.termux.terminal.TerminalSessionClient { *; }
-keep interface com.termux.terminal.TerminalSessionClient { *; }
-keep class com.termux.terminal.TerminalEmulator { *; }
-keep class com.termux.view.TerminalView { *; }
-keep interface com.termux.view.TerminalViewClient { *; }

# 保留行号便于调试
-keepattributes SourceFile,LineNumberTable
