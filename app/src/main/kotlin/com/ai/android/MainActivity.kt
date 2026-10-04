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
import com.ai.android.ui.call.CallScreen
import com.ai.android.ui.chat.ChatScreen
import com.ai.android.ui.chat.ConversationListScreen
import com.ai.android.ui.settings.SettingsScreen
import com.ai.android.ui.theme.AiAndroidTheme

class MainActivity : ComponentActivity() {

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 键盘处理：让内容随键盘弹起而调整（配合 Compose 的 imePadding）
        WindowCompat.setDecorFitsSystemWindows(window, false)

        enableEdgeToEdge()

        setContent {
            AiAndroidTheme {
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
                            )
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