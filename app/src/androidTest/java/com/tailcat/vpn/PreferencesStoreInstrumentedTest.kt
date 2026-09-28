package com.tailcat.vpn

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailcat.vpn.data.PreferencesStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Uses its own file names and Keystore alias; the app's preferences are untouched. */
@RunWith(AndroidJUnit4::class)
class PreferencesStoreInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun open() = PreferencesStore(context, STORE, KEY_ALIAS, ENCRYPTED, LEGACY)

    private fun prefsFile(name: String) =
        File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    @After
    fun cleanUp() {
        listOf(STORE, ENCRYPTED, LEGACY).forEach { context.deleteSharedPreferences(it) }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    @Test
    fun migratesEncryptedSharedPreferencesIntoKeystoreSealedValues() {
        val old = EncryptedSharedPreferences.create(
            context,
            ENCRYPTED,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        assertTrue(
            old.edit()
                .putString("key_saved_profiles", PROFILES)
                .putString("key_active_profile_id", "p1")
                .putBoolean("key_vpn_wanted", true)
                .putInt("key_default_mtu", 1400)
                .putStringSet("key_split_tunnel_excluded", setOf("com.example.b", "com.example.a"))
                .commit()
        )

        val store = open()
        assertEquals(PROFILES, store.savedProfilesJson)
        assertEquals("p1", store.activeProfileId)
        assertTrue(store.vpnWanted)
        assertEquals(1400, store.defaultMtu)
        assertEquals(setOf("com.example.a", "com.example.b"), store.splitTunnelExcludedApps)
        assertFalse("the old store must be deleted after migration", prefsFile(ENCRYPTED).exists())

        val raw = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString("key_saved_profiles", null)
        assertFalse("the token must not be stored in plaintext", raw.orEmpty().contains("tcTEST"))

        val reopened = open()
        assertEquals(PROFILES, reopened.savedProfilesJson)
        assertEquals(1400, reopened.defaultMtu)
    }

    @Test
    fun migratesThePlaintextStoreFromBeforeEncryption() {
        context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit()
            .putString("key_saved_profiles", PROFILES)
            .putBoolean("key_debug_mode", true)
            .commit()

        val store = open()
        assertEquals(PROFILES, store.savedProfilesJson)
        assertTrue(store.debugMode)
        assertTrue(context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun aLostKeystoreKeyReadsAsEmptyInsteadOfCrashing() {
        open().savedProfilesJson = PROFILES
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)

        val store = open()
        assertNull(store.savedProfilesJson)
        store.savedProfilesJson = "[]"
        assertEquals("[]", open().savedProfilesJson)
    }

    private companion object {
        const val STORE = "test_keystore_preferences"
        const val KEY_ALIAS = "test_preferences_key"
        const val ENCRYPTED = "test_secure_preferences"
        const val LEGACY = "test_plain_preferences"
        const val PROFILES = """[{"id":"p1","token":"tcTEST"}]"""
    }
}
