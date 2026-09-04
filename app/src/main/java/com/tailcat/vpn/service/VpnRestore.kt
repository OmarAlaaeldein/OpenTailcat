package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.TunnelState

object VpnRestore {
    fun shouldRestore(
        vpnWanted: Boolean,
        state: TunnelState,
        validationError: String?
    ): Boolean {
        if (!vpnWanted) return false
        if (validationError != null) return false
        return state == TunnelState.DISCONNECTED
    }
}
