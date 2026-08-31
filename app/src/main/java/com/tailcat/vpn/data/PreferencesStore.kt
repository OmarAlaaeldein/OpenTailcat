package com.tailcat.vpn.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tailcat_preferences", Context.MODE_PRIVATE)

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, value).apply()

    var isKillSwitchEnabled: Boolean
        get() = prefs.getBoolean(KEY_KILL_SWITCH, true)
        set(value) = prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply()

    var isAutoDerpOnEnterpriseWifi: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DERP_ENTERPRISE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DERP_ENTERPRISE, value).apply()

    var defaultMtu: Int
        get() = prefs.getInt(KEY_DEFAULT_MTU, 1280)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_MTU, value).apply()

    var defaultTcpMss: Int
        get() = prefs.getInt(KEY_DEFAULT_TCP_MSS, 1120)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_TCP_MSS, value).apply()

    var splitTunnelExcludedApps: Set<String>
        get() = prefs.getStringSet(KEY_SPLIT_TUNNEL_EXCLUDED, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SPLIT_TUNNEL_EXCLUDED, value).apply()

    var savedProfilesJson: String?
        get() = prefs.getString(KEY_SAVED_PROFILES, null)
        set(value) = prefs.edit().putString(KEY_SAVED_PROFILES, value).apply()

    companion object {
        private const val KEY_ACTIVE_PROFILE_ID = "key_active_profile_id"
        private const val KEY_KILL_SWITCH = "key_kill_switch"
        private const val KEY_AUTO_DERP_ENTERPRISE = "key_auto_derp_enterprise"
        private const val KEY_DEFAULT_MTU = "key_default_mtu"
        private const val KEY_DEFAULT_TCP_MSS = "key_default_tcp_mss"
        private const val KEY_SPLIT_TUNNEL_EXCLUDED = "key_split_tunnel_excluded"
        private const val KEY_SAVED_PROFILES = "key_saved_profiles"
    }
}
