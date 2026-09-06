package com.tailcat.vpn.service

import android.content.ContentResolver
import android.os.Build
import android.provider.Settings

/**
 * Detects Always-on VPN + lockdown without requiring a live VPN NetworkAgent.
 *
 * [VpnService.isLockdownEnabled] delegates to
 * `isCallerCurrentAlwaysOnVpnLockdownApp`, which needs
 * [UnderlyingNetworkInfo] from a running VPN (AUDIT H1). Settings.Secure
 * `always_on_vpn_app` / `always_on_vpn_lockdown` are written when the user
 * enables Always-on lockdown in system Settings and are observable before
 * [Builder.establish]. Prefer that signal; keep the framework query as a
 * corroborating OR after a warm TUN exists.
 */
object LockdownProbe {
    const val SECURE_ALWAYS_ON_VPN_APP = "always_on_vpn_app"
    const val SECURE_ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown"

    /**
     * @return true when this package is the Always-on VPN with lockdown;
     * false when Settings are readable and lockdown is not configured;
     * null when the secure settings cannot be read (fall back to framework).
     * Below [LeakGuard.LOCKDOWN_REQUIRED_API] returns true (lockdown not required).
     */
    fun alwaysOnLockdownConfigured(
        resolver: ContentResolver?,
        packageName: String,
        sdkInt: Int = Build.VERSION.SDK_INT,
        readString: (ContentResolver?, String) -> String? = { cr, key ->
            Settings.Secure.getString(requireNotNull(cr), key)
        },
        readInt: (ContentResolver?, String, Int) -> Int = { cr, key, def ->
            try {
                Settings.Secure.getInt(requireNotNull(cr), key)
            } catch (_: Settings.SettingNotFoundException) {
                def
            }
        }
    ): Boolean? {
        if (sdkInt < LeakGuard.LOCKDOWN_REQUIRED_API) return true
        return try {
            val app = readString(resolver, SECURE_ALWAYS_ON_VPN_APP)
            val lockdown = readInt(resolver, SECURE_ALWAYS_ON_VPN_LOCKDOWN, 0)
            app == packageName && lockdown == 1
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * True when default routes may be installed for leak policy on this API.
     * Settings.Secure=true wins even if [frameworkLockdownEnabled] is still false
     * after a host-only warm TUN (framework UnderlyingNetworkInfo race / quirk).
     */
    fun lockdownSatisfied(
        sdkInt: Int,
        settingsLockdown: Boolean?,
        frameworkLockdownEnabled: Boolean
    ): Boolean {
        if (sdkInt < LeakGuard.LOCKDOWN_REQUIRED_API) return true
        return settingsLockdown == true || frameworkLockdownEnabled
    }
}
