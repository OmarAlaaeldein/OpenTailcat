package com.tailcat.vpn.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.tailcat.vpn.ui.screens.home.HomeScreen
import com.tailcat.vpn.ui.screens.settings.SettingsScreen
import com.tailcat.vpn.ui.screens.speedtest.SpeedTestScreen
import com.tailcat.vpn.ui.theme.TailcatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        com.tailcat.vpn.TailcatApplication.instance.tunnelController.resyncFromEngine()
        enableEdgeToEdge()

        setContent {
            TailcatTheme {
                var currentScreen by rememberSaveable { androidx.compose.runtime.mutableStateOf("home") }

                BackHandler(enabled = currentScreen != "home") {
                    currentScreen = "home"
                }

                when (currentScreen) {
                    "home" -> HomeScreen(
                        onNavigateToSettings = { currentScreen = "settings" },
                        onNavigateToSpeedTest = { currentScreen = "speedtest" }
                    )
                    "settings" -> SettingsScreen(
                        onNavigateBack = { currentScreen = "home" }
                    )
                    "speedtest" -> SpeedTestScreen(
                        onNavigateBack = { currentScreen = "home" }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.tailcat.vpn.TailcatApplication.instance.tunnelController.resyncFromEngine()
    }
}
