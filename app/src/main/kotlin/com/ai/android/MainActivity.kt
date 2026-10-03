package com.ai.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.android.ui.chat.ChatScreen
import com.ai.android.ui.settings.SettingsScreen
import com.ai.android.ui.theme.AiAndroidTheme

/**
 * 主 Activity：NavGraph（chat / settings）+ 初始权限申请。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果由设置页展示 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AiAndroidTheme {
                // Activity 级共享 ViewModel
                val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "chat") {
                    composable("chat") {
                        ChatScreen(
                            vm = vm,
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            vm = vm,
                            onBack = { navController.popBackStack() },
                        )
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
            if (!granted) {
                runCatching { notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}
