# Ai Android ProGuard 规则（release 构建用）

# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.ai.android.**$$serializer { *; }
-keepclassmembers class com.ai.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.ai.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebView JS 桥（run_js 工具依赖反射调用）
-keepclassmembers class com.ai.android.agent.tools.JsTool$JsBridge {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# 无障碍服务 / 接收器 / 服务（manifest 引用）
-keep class com.ai.android.service.** { *; }
