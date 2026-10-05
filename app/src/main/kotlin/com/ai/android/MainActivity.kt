package com.ai.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ai.android.i18n.I18nManager
import com.ai.android.ui.call.CallScreen
import com.ai.android.ui.chat.ChatScreen
import com.ai.android.ui.chat.ConversationListScreen
import com.ai.android.ui.settings.SettingsScreen
import com.ai.android.ui.terminal.TerminalScreen
import com.ai.android.ui.theme.AiAndroidTheme
import com.ai.android.ui.theme.AppThemeStyle

class MainActivity : ComponentActivity() {

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * ⭐ v1.1.0 #4：多语言热切换。
     * attachBaseContext 里把 base 包成 I18nManager.LocaleWrapper（用持久化的当前语言），
     * 进程启动时资源即按所选 locale 解析；设置页切语言时再走 I18nManager.applyLocale 即时更新。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(
            runCatching { I18nManager.applyToContext(newBase) }.getOrDefault(newBase)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 键盘处理：让内容随键盘弹起而调整（配合 Compose 的 imePadding）
        WindowCompat.setDecorFitsSystemWindows(window, false)

        enableEdgeToEdge()

        // ⭐ v1.1.0 #4：把持久化语言应用到本 Activity 的 Resources（热切换生效）
        runCatching { I18nManager.applyLocale(this) }


                setContent {
            // ⭐ 第5项：插件 THEME 能力可定制软件主题风格（默认不变，不填插件则跟随系统 + 内置配色）
            val appTheme = runCatching {
                MainApp.instance.pluginRegistry.activeAppTheme
            }.getOrDefault(AppThemeStyle.DEFAULT)
            AiAndroidTheme(appTheme = appTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
                    val nav = rememberNavController()

                    NavHost(navController = nav, startDestination = "chat") {
                                                                        composable("chat") {
                            ChatScreen(
                                vm = vm,
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenConversations = { nav.navigate("conversations") },
                                onOpenCall = { video -> nav.navigate("call/${if (video) "video" else "voice"}") },
                                onOpenTerminal = { nav.navigate("terminal") },
                            )
                        }
                        composable("terminal") {
                            TerminalScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable(
                            "call/{mode}",
                            arguments = listOf(navArgument("mode") { defaultValue = "voice" }),
                        ) { back ->
                            val mode = back.arguments?.getString("mode") ?: "voice"
                            CallScreen(vm = vm, videoMode = mode == "video", onBack = { nav.popBackStack() })
                        }
                        composable("conversations") {
                            ConversationListScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) runCatching { notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}