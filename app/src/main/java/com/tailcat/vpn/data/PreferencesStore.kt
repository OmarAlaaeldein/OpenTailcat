package com.tailcat.vpn.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface PreferencesStorage {
    var activeProfileId: String?
    var defaultMtu: Int
    var defaultDns: String
    var splitTunnelExcludedApps: Set<String>
    /**
     * ISO 3166-1 alpha-2 codes (upper case) of the countries whose IP ranges the
     * tunnel refuses to connect to. Applied when the tunnel starts; empty = off.
     */
    var blockedCountries: Set<String>
    var savedProfilesJson: String?
    var vpnWanted: Boolean
    /**
     * Debug diagnostics: failure banners carry a raw telemetry snapshot
     * (state, transport, health age, counters). Off by default; user-facing
     * messages stay short either way. Never gates routing or lockdown.
     */
    var debugMode: Boolean

    fun saveProfilesJson(json: String?): Boolean {
        savedProfilesJson = json
        return true
    }

    fun saveActiveProfileId(id: String?): Boolean {
        activeProfileId = id
        return true
    }
}

/** Only upper-case ISO 3166-1 alpha-2 shaped values are ever stored or read back as countries. */
private fun isCountryCode(value: String): Boolean =
    value.length == 2 && value.all { it in 'A'..'Z' }

/**
 * Preferences sealed with an AES-256-GCM key in the Android Keystore. If the
 * key is lost, values read as missing (the user pairs again) rather than
 * crashing the app at start, which the deprecated EncryptedSharedPreferences
 * did. Data from that store (1.2–1.4) and from the plaintext store before it
 * is migrated once.
 */
class PreferencesStore internal constructor(
    context: Context,
    storeName: String,
    keyAlias: String,
    private val encryptedName: String,
    private val legacyName: String
) : PreferencesStorage {

    constructor(context: Context) : this(
        context, STORE_NAME, KEY_ALIAS, ENCRYPTED_PREFERENCES_NAME, LEGACY_PREFERENCES_NAME
    )

    private val store = SealedPreferences(
        SharedPreferencesBacking(context.getSharedPreferences(storeName, Context.MODE_PRIVATE)),
        KeystoreValueCipher(keyAlias)
    )

    init {
        migrate(context)
    }

    // Profile writes commit synchronously: with apply() a process killed right
    // after a delete could bring the deleted profile and its token back.
    override var activeProfileId: String?
        get() = store.getString(KEY_ACTIVE_PROFILE_ID)
        set(value) { store.putString(KEY_ACTIVE_PROFILE_ID, value, commit = true) }

    override fun saveActiveProfileId(id: String?): Boolean =
        store.putString(KEY_ACTIVE_PROFILE_ID, id, commit = true)

    override var defaultMtu: Int
        get() = store.getString(KEY_DEFAULT_MTU)?.toIntOrNull() ?: 1280
        set(value) { store.putString(KEY_DEFAULT_MTU, value.coerceIn(MIN_MTU, MAX_MTU).toString()) }

    override var defaultDns: String
        get() = store.getString(KEY_DEFAULT_DNS) ?: "1.1.1.1"
        set(value) { store.putString(KEY_DEFAULT_DNS, value) }

    override var splitTunnelExcludedApps: Set<String>
        get() = store.getString(KEY_SPLIT_TUNNEL_EXCLUDED)?.let { json ->
            runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { array.getString(it) }.toSet()
            }.getOrNull()
        } ?: emptySet()
        set(value) { store.putString(KEY_SPLIT_TUNNEL_EXCLUDED, SealedPreferences.encode(value)) }

    override var blockedCountries: Set<String>
        get() = store.getString(KEY_BLOCKED_COUNTRIES)?.let { json ->
            runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { array.getString(it) }.filter(::isCountryCode).toSet()
            }.getOrNull()
        } ?: emptySet()
        set(value) {
            store.putString(KEY_BLOCKED_COUNTRIES, SealedPreferences.encode(value.filter(::isCountryCode).toSet()))
        }

    override var savedProfilesJson: String?
        get() = store.getString(KEY_SAVED_PROFILES)
        set(value) { store.putString(KEY_SAVED_PROFILES, value, commit = true) }

    override fun saveProfilesJson(json: String?): Boolean =
        store.putString(KEY_SAVED_PROFILES, json, commit = true)

    override var vpnWanted: Boolean
        get() = store.getString(KEY_VPN_WANTED).toBoolean()
        set(value) { store.putString(KEY_VPN_WANTED, value.toString(), commit = true) }

    override var debugMode: Boolean
        get() = store.getString(KEY_DEBUG_MODE).toBoolean()
        set(value) { store.putString(KEY_DEBUG_MODE, value.toString()) }

    /**
     * Copies values from the plaintext store (before 1.2) and from
     * EncryptedSharedPreferences (1.2–1.4) for keys this store lacks, then
     * deletes the old store. An EncryptedSharedPreferences file that cannot be
     * opened is kept and retried on the next start. Nothing here is logged
     * beyond the exception type: the values hold tokens.
     */
    private fun migrate(context: Context) {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty() && store.importMissing(legacy.all)) {
            context.deleteSharedPreferences(legacyName)
        }

        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!File(sharedPrefsDir, "$encryptedName.xml").exists()) return
        if (store.getString(KEY_SAVED_PROFILES) != null ||
            store.getString(KEY_ENCRYPTED_MIGRATION_DONE) == "true"
        ) {
            return
        }
        val old = try {
            EncryptedSharedPreferences.create(
                context,
                encryptedName,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).all
        } catch (e: Exception) {
            Log.w(TAG, "Previous encrypted preferences are unreadable (${e.javaClass.simpleName})")
            store.putString(KEY_ENCRYPTED_MIGRATION_DONE, "true", commit = true)
            return
        }
        if (store.importMissing(old)) {
            context.deleteSharedPreferences(encryptedName)
            store.putString(KEY_ENCRYPTED_MIGRATION_DONE, "true", commit = true)
        }
    }

    companion object {
        private const val TAG = "PreferencesStore"
        private const val STORE_NAME = "tailcat_keystore_preferences"
        private const val KEY_ALIAS = "tailcat_preferences_v2"
        private const val LEGACY_PREFERENCES_NAME = "tailcat_preferences"
        private const val ENCRYPTED_PREFERENCES_NAME = "tailcat_secure_preferences"

        private const val KEY_ACTIVE_PROFILE_ID = "key_active_profile_id"
        private const val KEY_DEFAULT_MTU = "key_default_mtu"
        private const val KEY_DEFAULT_DNS = "key_default_dns"
        private const val KEY_SPLIT_TUNNEL_EXCLUDED = "key_split_tunnel_excluded"
        private const val KEY_BLOCKED_COUNTRIES = "key_blocked_countries"
        private const val KEY_SAVED_PROFILES = "key_saved_profiles"
        private const val KEY_VPN_WANTED = "key_vpn_wanted"
        private const val KEY_DEBUG_MODE = "key_debug_mode"
        private const val KEY_ENCRYPTED_MIGRATION_DONE = "key_encrypted_migration_done"

        private const val MIN_MTU = 1_280
        private const val MAX_MTU = 1_500
    }
}

private class SharedPreferencesBacking(private val prefs: SharedPreferences) : SealedPreferences.Backing {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(values: Map<String, String?>, commit: Boolean): Boolean {
        val editor = prefs.edit()
        values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        if (commit) return editor.commit()
        editor.apply()
        return true
    }
}

/** AES-256-GCM with a non-exportable Android Keystore key; the IV is stored with each value. */
internal class KeystoreValueCipher(private val alias: String) : ValueCipher {
    @Volatile private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
        cachedKey = key
        return key
    }

    @Synchronized
    private fun invalidateCachedKey() {
        cachedKey = null
    }

    override fun seal(key: String, plain: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(key.toByteArray())
            val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
            Base64.encodeToString(sealed, Base64.NO_WRAP)
        } catch (e: InvalidKeyException) {
            invalidateCachedKey()
            throw e
        } catch (e: KeyStoreException) {
            invalidateCachedKey()
            throw e
        }
    }

    override fun open(key: String, sealed: String): String {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        require(raw.size > IV_BYTES) { "sealed value is too short" }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
            cipher.updateAAD(key.toByteArray())
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
        } catch (e: InvalidKeyException) {
            invalidateCachedKey()
            throw e
        } catch (e: KeyStoreException) {
            invalidateCachedKey()
            throw e
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
