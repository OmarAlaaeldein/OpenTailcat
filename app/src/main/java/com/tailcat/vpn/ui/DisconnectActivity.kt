package com.tailcat.vpn.ui

import android.app.Activity
import android.os.Bundle
import com.tailcat.vpn.TailcatApplication

/**
 * Target of the notification's Disconnect action. Android makes the user
 * unlock the device before it starts an activity from a lock-screen
 * notification, so the VPN cannot be switched off on a locked phone. (A
 * service PendingIntent ran straight from the lock screen.)
 */
class DisconnectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TailcatApplication.instance.tunnelController.stopTunnel()
        finish()
    }
}
