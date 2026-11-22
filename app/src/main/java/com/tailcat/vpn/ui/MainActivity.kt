package com.tailcat.vpn.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.ui.screens.home.HomeScreen
import com.tailcat.vpn.ui.screens.settings.SettingsScreen
import com.tailcat.vpn.ui.screens.share.ShareScreen
import com.tailcat.vpn.ui.screens.speedtest.SpeedTestScreen
import com.tailcat.vpn.ui.theme.BgBottom
import com.tailcat.vpn.ui.theme.BgTop
import com.tailcat.vpn.ui.theme.TailcatTheme
import com.tailcat.vpn.ui.theme.rememberReducedMotion

/** One backdrop gradient behind every screen; screens draw on transparent. */
private val Backdrop = Brush.verticalGradient(listOf(BgTop, BgBottom))

/** Reference-counted coordinator so overlapping screens never clear each other's FLAG_SECURE. */
internal class SecureWindowController(private val window: Window? = null) {
    private val activeKeys = LinkedHashSet<Any>()

    fun setSecure(key: Any, secure: Boolean) {
        if (secure) activeKeys.add(key) else activeKeys.remove(key)
        if (activeKeys.isNotEmpty()) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

internal val LocalSecureWindow = staticCompositionLocalOf { SecureWindowController() }

@Composable
internal fun SecureWindowWhile(enabled: Boolean) {
    val controller = LocalSecureWindow.current
    val key = remember { Any() }
    DisposableEffect(controller, key, enabled) {
        controller.setSecure(key, enabled)
        onDispose { controller.setSecure(key, false) }
    }
}

class MainActivity : ComponentActivity() {
    /** A screen a notification asked for; consumed by the composition. */
    private val requestedScreen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            requestedScreen.value = intent?.getStringExtra(EXTRA_SCREEN)
            intent?.removeExtra(EXTRA_SCREEN)
        }
        TailcatApplication.instance.tunnelController.onUiResumed()
        // The UI is always dark: force light system-bar icons. The default
        // auto style would pick dark icons on a device set to light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        setContent {
            TailcatTheme {
                val secureController = remember(window) { SecureWindowController(window) }
                CompositionLocalProvider(LocalSecureWindow provides secureController) {
                    var currentScreen by rememberSaveable { mutableStateOf("home") }
                    val reducedMotion = rememberReducedMotion()

                    LaunchedEffect(requestedScreen.value) {
                        requestedScreen.value?.let { wanted ->
                            if (wanted == SCREEN_SHARE) currentScreen = wanted
                            requestedScreen.value = null
                        }
                    }

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
                                    onNavigateToSpeedTest = { currentScreen = "speedtest" },
                                    onNavigateToShare = { currentScreen = "share" }
                                )
                                "settings" -> SettingsScreen(
                                    onNavigateBack = { currentScreen = "home" }
                                )
                                "speedtest" -> SpeedTestScreen(
                                    onNavigateBack = { currentScreen = "home" }
                                )
                                "share" -> ShareScreen(
                                    onNavigateBack = { currentScreen = "home" }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedScreen.value = intent.getStringExtra(EXTRA_SCREEN)
        intent.removeExtra(EXTRA_SCREEN)
    }

    override fun onResume() {
        super.onResume()
        TailcatApplication.instance.tunnelController.onUiResumed()
    }

    companion object {
        /** Intent extra naming the screen to open. Only known screens are honoured. */
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_SHARE = "share"
    }
}
