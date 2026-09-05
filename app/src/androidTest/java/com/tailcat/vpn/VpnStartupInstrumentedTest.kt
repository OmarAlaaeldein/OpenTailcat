package com.tailcat.vpn

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.service.TailcatVpnService
import com.tailcat.vpn.service.LeakGuard
import com.tailcat.vpn.service.VpnNotificationManager
import com.tailcat.vpn.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a dedicated emulator: changes VPN consent for the target package. */
@RunWith(AndroidJUnit4::class)
class VpnStartupInstrumentedTest {
    @Test
    fun startWithoutVpnConsentReportsFailureAndKeepsAppAlive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as TailcatApplication
        app.preferencesStore.vpnWanted = false
        // Synthetic public keys: no live gateway credentials or network handshake needed.
        val cbor = byteArrayOf(0xa3.toByte(), 0x61, 0x70, 0x58, 0x20) +
            ByteArray(32) { 1 } + byteArrayOf(0x61, 0x6b, 0x58, 0x20) +
            ByteArray(32) { 2 } + byteArrayOf(0x61, 0x69, 0x01)
        val token = "tc" + Base64.encodeToString(cbor, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val profile = app.profileRepository.addOrUpdateFromToken("Startup regression", token).getOrThrow()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${app.packageName} ACTIVATE_VPN ignore"
            )).use { it.readBytes() }
            assertNotNull("Test requires VPN consent to be absent", VpnService.prepare(app))
            ActivityScenario.launch(MainActivity::class.java).use {
                await("Emulator must have validated internet") { app.networkMonitor.isOnline }
                assertNull(app.tunnelController.validateStartRequest())
                app.preferencesStore.vpnWanted = true
                assertFalse("Restore must check consent before starting a service", app.tunnelController.restoreIfWanted())
                assertFalse(app.preferencesStore.vpnWanted)
                assertEquals(TailcatVpnService.VPN_PERMISSION_REQUIRED, app.tunnelController.lastError.value)
                // Bypass the controller to exercise consent lost after a service
                // request was queued, as well as starts initiated by Android.
                app.preferencesStore.vpnWanted = true
                app.tunnelController.setTunnelState(TunnelState.CONNECTING)
                instrumentation.runOnMainSync {
                    ContextCompat.startForegroundService(app, Intent(app, TailcatVpnService::class.java).apply {
                        action = TailcatVpnService.ACTION_START_VPN
                    })
                }
                await("Startup failure was not reported") {
                    app.tunnelController.lastError.value != null &&
                        app.tunnelController.tunnelState.value == TunnelState.DISCONNECTED
                }
                assertFalse("Failed start must not be restored repeatedly", app.preferencesStore.vpnWanted)
                assertEquals(TailcatVpnService.VPN_PERMISSION_REQUIRED, app.tunnelController.lastError.value)
                // Allow delayed Android service errors to reach the process.
                SystemClock.sleep(6_000)
                assertFalse(app.tunnelController.restoreIfWanted())
                assertEquals(TunnelState.DISCONNECTED, app.tunnelController.tunnelState.value)
                assertStopped(app)

                if (Build.VERSION.SDK_INT >= 29) {
                    // An explicit retry after consent must reach normal startup.
                    // This emulator has no lockdown; that existing guard must
                    // report its error and clean up the real foreground service.
                    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                        "appops set ${app.packageName} ACTIVATE_VPN allow"
                    )).use { it.readBytes() }
                    assertNull(VpnService.prepare(app))
                    instrumentation.runOnMainSync {
                        assertTrue(app.tunnelController.startTunnel())
                    }
                    await("Consented retry did not report the lockdown requirement") {
                        app.tunnelController.lastError.value == LeakGuard.LOCKDOWN_REQUIRED &&
                            app.tunnelController.tunnelState.value == TunnelState.DISCONNECTED
                    }
                    await("Retried VPN service did not stop") {
                        app.getSystemService(NotificationManager::class.java).activeNotifications
                            .none { it.id == VpnNotificationManager.NOTIFICATION_ID }
                    }
                    assertFalse(app.preferencesStore.vpnWanted)
                    assertStopped(app)
                }
            }
        } finally {
            app.preferencesStore.vpnWanted = false
            app.profileRepository.deleteProfile(profile.id)
        }
    }

    @Suppress("DEPRECATION") // Android still exposes the caller's own services.
    private fun assertStopped(app: TailcatApplication) {
        val manager = app.getSystemService(ActivityManager::class.java)
        assertFalse("Rejected VPN service was left running", manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == TailcatVpnService::class.java.name
        })
        assertFalse("VPN notification was left behind", app.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == VpnNotificationManager.NOTIFICATION_ID })
        assertEquals("STOPPED", app.tunnelEngine.getStats().state)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
}
