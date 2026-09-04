package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.TunnelState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRestoreTest {

    @Test
    fun restoresOnlyWhenWantedDisconnectedAndValid() {
        assertTrue(
            VpnRestore.shouldRestore(
                vpnWanted = true,
                state = TunnelState.DISCONNECTED,
                validationError = null
            )
        )
    }

    @Test
    fun skipsWhenUserDidNotWantVpn() {
        assertFalse(
            VpnRestore.shouldRestore(
                vpnWanted = false,
                state = TunnelState.DISCONNECTED,
                validationError = null
            )
        )
    }

    @Test
    fun skipsWhenAlreadyConnectingOrConnected() {
        assertFalse(
            VpnRestore.shouldRestore(
                vpnWanted = true,
                state = TunnelState.CONNECTING,
                validationError = null
            )
        )
        assertFalse(
            VpnRestore.shouldRestore(
                vpnWanted = true,
                state = TunnelState.CONNECTED,
                validationError = null
            )
        )
    }

    @Test
    fun skipsWhenStartWouldFailClosed() {
        assertFalse(
            VpnRestore.shouldRestore(
                vpnWanted = true,
                state = TunnelState.DISCONNECTED,
                validationError = "Pair a gateway token before connecting"
            )
        )
    }
}
