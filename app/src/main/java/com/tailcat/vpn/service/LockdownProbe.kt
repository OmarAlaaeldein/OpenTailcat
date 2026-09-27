package com.tailcat.vpn.service

/**
 * Always-on VPN and "Block connections without VPN" state, as reported by
 * the running [android.net.VpnService] (`isAlwaysOn()` / `isLockdownEnabled()`,
 * API 29+). Apps cannot read it otherwise: the `always_on_vpn_*` secure
 * settings are hidden and unreadable for apps targeting Android 12+, and
 * `isLockdownEnabled()` needs a live VPN network.
 */
data class AlwaysOnStatus(val alwaysOn: Boolean, val lockdown: Boolean)

object LockdownProbe {
    /** API level where VpnService reports Always-on and lockdown. */
    const val STATUS_API = 29

    /** Settings text for [status], which is null unless the VPN service is running. */
    fun statusText(sdkInt: Int, status: AlwaysOnStatus?): String = when {
        sdkInt < STATUS_API ->
            "Status: Android 8–9 do not report Always-on state to apps. Check Android VPN settings."
        status == null ->
            "Status: known only while the VPN is connected."
        status.alwaysOn && status.lockdown ->
            "Status: Always-on VPN with ‘Block connections without VPN’ is ON for this app."
        status.alwaysOn ->
            "Status: Always-on VPN is on, but ‘Block connections without VPN’ is off: apps use the device network whenever the VPN is reconnecting."
        else ->
            "Status: Always-on VPN is off for this app."
    }

    fun isProtected(status: AlwaysOnStatus?): Boolean = status?.alwaysOn == true && status.lockdown
}
