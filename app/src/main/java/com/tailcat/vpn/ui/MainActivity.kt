package com.tailcat.vpn.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.ui.screens.home.HomeScreen
import com.tailcat.vpn.ui.screens.settings.SettingsScreen
import com.tailcat.vpn.ui.screens.speedtest.SpeedTestScreen
import com.tailcat.vpn.ui.theme.BgBottom
import com.tailcat.vpn.ui.theme.BgTop
import com.tailcat.vpn.ui.theme.TailcatTheme
import com.tailcat.vpn.ui.theme.rememberReducedMotion

/** One backdrop gradient behind every screen; screens draw on transparent. */
private val Backdrop = Brush.verticalGradient(listOf(BgTop, BgBottom))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TailcatApplication.instance.tunnelController.onUiResumed()
        // The UI is always dark: force light system-bar icons. The default
        // auto style would pick dark icons on a device set to light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        setContent {
            TailcatTheme {
                var currentScreen by rememberSaveable { mutableStateOf("home") }
                val reducedMotion = rememberReducedMotion()

                BackHandler(enabled = currentScreen != "home") {
                    currentScreen = "home"
                }

                Box(modifier = Modifier.fillMaxSize().background(Backdrop)) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            if (reducedMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                fadeIn(tween(160, delayMillis = 60)) togetherWith fadeOut(tween(100))
                            }
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
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
        }
    }

    override fun onResume() {
        super.onResume()
        TailcatApplication.instance.tunnelController.onUiResumed()
    }
}
