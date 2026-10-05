package com.ai.android.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ⭐ v1.1.0 #4：多语言管理器。
 *
 * - 支持语言：中文（默认，跟随系统资源）/ 英文 / 跟随系统。
 * - 持久化当前选择（SharedPreferences ai_settings / app_language）。
 * - 支持导入自定义 .json 语言包（key→翻译），合并进内存词典，覆盖指定文案。
 * - 热切换：经 [applyToContext] 在 Activity.attachBaseContext 包一层 [LocaleWrapper]；
 *   配合 [applyLocale] 调 Resources.updateConfiguration 即时切换，无需杀进程。
 */
object I18nManager {

    /** 语言模式常量 */
    const val ZH = "zh"
    const val EN = "en"
    const val FOLLOW = "follow"

    private const val PREFS = "ai_settings"
    private const val KEY_LANG = "app_language"
    private const val KEY_PACK = "i18n_pack"

    private val json = Json { ignoreUnknownKeys = true }

    private val _locale = MutableStateFlow(ZH)
    /** 当前生效语言代码（"zh" / "en" / "follow"） */
    val locale: StateFlow<String> = _locale

    /** 用户导入的自定义语言包（key→翻译），运行时覆盖资源文案 */
    @Volatile private var userDict: Map<String, String> = emptyMap()

    /** 外部应用上下文（init 时注入） */
    @Volatile private var appCtx: Context? = null

    /** 用外部应用上下文初始化（MainApp.onCreate 调一次） */
    fun init(context: Context) {
        appCtx = context.applicationContext
        // 载入已持久化的语言 + 已启用语言包
        _locale.value = loadPersisted()
        loadPersistedPack()
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPersisted(): String =
        prefs()?.getString(KEY_LANG, ZH) ?: ZH

    /** 当前应使用的 Locale */
    fun currentLocale(): Locale = when (_locale.value) {
        EN -> Locale.ENGLISH
        ZH -> Locale.SIMPLIFIED_CHINESE
        else -> Locale.getDefault()
    }

    /** 持久化选择（重启后保留）；返回切换后的语言代码 */
    fun setLanguage(code: String) {
        _locale.value = code
        prefs()?.edit()?.putString(KEY_LANG, code)?.apply()
    }

    /**
     * 热切换入口：把给定 Activity 的 base context 换成应用了新语言配置的 [LocaleWrapper]。
     * 在 Activity.attachBaseContext 里调用。
     */
    fun applyToContext(context: Context): Context = LocaleWrapper.wrap(context, currentLocale())

        /** 即时更新给定 Activity 的 Resources（updateConfiguration），不重启热切换。 */
    fun applyLocale(context: Context) {
        val locale = currentLocale()
        if (context is Activity) {
            context.setLocale(locale)
        } else {
            // 非 Activity（如 Application / Service）无法热更新，仅记录；下次 Activity 启动会
            // 经 attachBaseContext 走 LocaleWrapper 应用
            runCatching {
                val config = Configuration(context.resources.configuration)
                if (Build.VERSION.SDK_INT >= 24) config.setLocales(LocaleList(locale))
                else @Suppress("DEPRECATION") config.locale = locale
                context.resources.updateConfiguration(config, context.resources.displayMetrics)
            }
        }
    }

    /** 导入自定义 .json 语言包（key→翻译），立即生效并持久化 URI。返回是否成功。 */
    fun importPack(uri: Uri, context: Context): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: return false
        applyPackText(text)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PACK, uri.toString()).apply()
        true
    }.getOrDefault(false)

    /** 导入文件语言包（从用户目录 / assets）。 */
    fun importPackFile(file: File): Boolean = runCatching {
        applyPackText(file.readText())
        true
    }.getOrDefault(false)

    /** 解析 key→翻译 JSON 文本，合并进内存词典 */
    private fun applyPackText(text: String) {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return
        userDict = obj.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content ?: v.toString()
        }
        _locale.value = _locale.value  // 触发 UI 订阅重组
    }

    private fun loadPersistedPack() {
        val path = prefs()?.getString(KEY_PACK, "").orEmpty()
        if (path.isNotBlank()) {
            val f = File(path)
            if (f.exists()) runCatching { applyPackText(f.readText()) }
        }
    }

    /** 查词典（用户语言包优先，未命中返回原 key） */
    fun t(key: String): String = userDict[key] ?: key

    /** 是否有已导入的自定义语言包 */
    fun hasPack(): Boolean = userDict.isNotEmpty()

    /** 清除用户语言包（恢复纯资源文案） */
    fun clearPack(context: Context) {
        userDict = emptyMap()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PACK).apply()
        _locale.value = _locale.value
    }

    /**
     * 包一层 base context：把 [locale] 写进 Configuration，资源即按该语言解析。
     * 在 Activity.attachBaseContext 里包外部 context。
     */
        class LocaleWrapper @JvmOverloads constructor(
        baseContext: Context,
        private val locale: Locale,
    ) : ContextWrapper(baseContext) {

                override fun getResources(): android.content.res.Resources {
            val res = super.getResources()
            // 已是目标 locale 就直接用；否则按新 locale 创建一份 Resources
            if (res.configuration.locale == locale) return res
            val config = Configuration(res.configuration)
            if (Build.VERSION.SDK_INT >= 24) {
                config.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            return getBaseContext().createConfigurationContext(config).resources
        }

        companion object {
            fun wrap(context: Context, locale: Locale): Context =
                if (context is LocaleWrapper) context else LocaleWrapper(context, locale)
        }
    }

        /** 已支持的语言列表（供设置页展示） */
    fun supportedLanguages(): List<Pair<String, String>> = listOf(
        ZH to "简体中文",
        EN to "English",
        FOLLOW to "跟随系统",
    )
}

/** Activity 设 locale 的辅助扩展（applyLocale 用）：更新 Resources 并刷新布局 */
fun Activity.setLocale(locale: Locale) {
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    if (Build.VERSION.SDK_INT >= 24) config.setLocales(LocaleList(locale))
    resources.updateConfiguration(config, resources.displayMetrics)
}
