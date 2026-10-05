# terminal-view ProGuard / R8 规则（consumer）
-keep class com.termux.view.TerminalView { *; }
-keep interface com.termux.view.TerminalViewClient { *; }
-keep class com.termux.view.TerminalRenderer { *; }
-keep class com.termux.view.support.** { *; }
-keep class com.termux.view.textselection.** { *; }
-keepattributes SourceFile,LineNumberTable
