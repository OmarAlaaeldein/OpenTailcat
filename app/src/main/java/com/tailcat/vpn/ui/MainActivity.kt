package com.tailcat.vpn.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.ui.screens.home.HomeScreen
import com.tailcat.vpn.ui.screens.settings.SettingsScreen
import com.tailcat.vpn.ui.screens.speedtest.SpeedTestScreen
import com.tailcat.vpn.ui.theme.TailcatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TailcatApplication.instance.tunnelController.onUiResumed()
        enableEdgeToEdge()

        setContent {
            TailcatTheme {
                var currentScreen by rememberSaveable { mutableStateOf("home") }

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
        TailcatApplication.instance.tunnelController.onUiResumed()
    }
}
