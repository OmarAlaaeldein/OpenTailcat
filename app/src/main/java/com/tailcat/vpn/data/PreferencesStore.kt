package com.tailcat.vpn.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface PreferencesStorage {
    var activeProfileId: String?
    var defaultMtu: Int
    var defaultDns: String
    var splitTunnelExcludedApps: Set<String>
    var savedProfilesJson: String?
}

class PreferencesStore(context: Context) : PreferencesStorage {

    private val prefs: SharedPreferences = createEncryptedPreferences(context)

    init {
        migrateLegacyPreferences(context)
    }

    override var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        set(value) = prefs.edit { putString(KEY_ACTIVE_PROFILE_ID, value) }

    override var defaultMtu: Int
        get() = prefs.getInt(KEY_DEFAULT_MTU, 1280)
        set(value) = prefs.edit { putInt(KEY_DEFAULT_MTU, value.coerceIn(MIN_MTU, MAX_MTU)) }

    override var defaultDns: String
        get() = prefs.getString(KEY_DEFAULT_DNS, "1.1.1.1") ?: "1.1.1.1"
        set(value) = prefs.edit { putString(KEY_DEFAULT_DNS, value) }

    override var splitTunnelExcludedApps: Set<String>
        get() = prefs.getStringSet(KEY_SPLIT_TUNNEL_EXCLUDED, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_SPLIT_TUNNEL_EXCLUDED, value.toSet()) }

    override var savedProfilesJson: String?
        get() = prefs.getString(KEY_SAVED_PROFILES, null)
        set(value) = prefs.edit { putString(KEY_SAVED_PROFILES, value) }

    @SuppressLint("UseKtx") // A direct Editor lets us verify commit before deleting plaintext.
    private fun migrateLegacyPreferences(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return

        val editor = prefs.edit()
        for ((key, value) in legacy.all) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "Could not migrate encrypted preferences" }
        legacy.edit(commit = true) { clear() }
    }

    private fun createEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val LEGACY_PREFERENCES_NAME = "tailcat_preferences"
        private const val ENCRYPTED_PREFERENCES_NAME = "tailcat_secure_preferences"

        private const val KEY_ACTIVE_PROFILE_ID = "key_active_profile_id"
        private const val KEY_DEFAULT_MTU = "key_default_mtu"
        private const val KEY_DEFAULT_DNS = "key_default_dns"
        private const val KEY_SPLIT_TUNNEL_EXCLUDED = "key_split_tunnel_excluded"
        private const val KEY_SAVED_PROFILES = "key_saved_profiles"

        private const val MIN_MTU = 1_280
        private const val MAX_MTU = 1_500
    }
}
