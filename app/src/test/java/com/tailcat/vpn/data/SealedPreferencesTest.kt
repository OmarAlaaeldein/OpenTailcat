package com.tailcat.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedPreferencesTest {

    private class MapBacking : SealedPreferences.Backing {
        val values = mutableMapOf<String, String>()
        var commitResult = true

        override fun get(key: String): String? = values[key]

        override fun put(values: Map<String, String?>, commit: Boolean): Boolean {
            if (commit && !commitResult) return false
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            return true
        }
    }

    /** Reversible stand-in for the Keystore; [broken] makes every call fail. */
    private class FakeCipher : ValueCipher {
        var broken = false

        override fun seal(key: String, plain: String): String {
            check(!broken) { "keystore unavailable" }
            return "$key|${plain.reversed()}"
        }

        override fun open(key: String, sealed: String): String {
            check(!broken) { "keystore unavailable" }
            val (boundKey, body) = sealed.split("|", limit = 2)
            check(boundKey == key) { "value belongs to another key" }
            return body.reversed()
        }
    }

    @Test
    fun valuesAreStoredSealedAndReadBack() {
        val backing = MapBacking()
        val prefs = SealedPreferences(backing, FakeCipher())

        assertTrue(prefs.putString("token", "tcSECRET", commit = true))
        assertEquals("token|TERCESct", backing.values["token"])
        assertEquals("tcSECRET", SealedPreferences(backing, FakeCipher()).getString("token"))

        assertTrue(prefs.putString("token", null))
        assertNull(backing.values["token"])
        assertNull(prefs.getString("token"))
    }

    @Test
    fun anUndecryptableValueReadsAsMissing() {
        val backing = MapBacking()
        SealedPreferences(backing, FakeCipher()).putString("profiles", "[...]")
        val cipher = FakeCipher().apply { broken = true }

        assertNull(SealedPreferences(backing, cipher).getString("profiles"))
        // A value moved under another key does not decrypt either.
        backing.values["other"] = backing.values.getValue("profiles")
        assertNull(SealedPreferences(backing, FakeCipher()).getString("other"))
    }

    @Test
    fun aValueThatCannotBeEncryptedIsNeverWrittenInPlaintext() {
        val backing = MapBacking()
        val cipher = FakeCipher()
        val prefs = SealedPreferences(backing, cipher)
        prefs.putString("token", "old")
        cipher.broken = true

        assertFalse(prefs.putString("token", "tcSECRET"))
        assertEquals("token|dlo", backing.values["token"])
        assertEquals("old", prefs.getString("token"))
    }

    @Test
    fun aFailedCommitDoesNotChangeTheCachedValue() {
        val backing = MapBacking()
        val prefs = SealedPreferences(backing, FakeCipher())
        prefs.putString("active", "a", commit = true)
        backing.commitResult = false

        assertFalse(prefs.putString("active", "b", commit = true))
        assertEquals("a", prefs.getString("active"))
    }

    @Test
    fun importMissingKeepsNewerValuesAndEncodesTypes() {
        val backing = MapBacking()
        val prefs = SealedPreferences(backing, FakeCipher())
        prefs.putString("key_default_dns", "9.9.9.9")

        assertTrue(
            prefs.importMissing(
                mapOf(
                    "key_default_dns" to "1.1.1.1",
                    "key_vpn_wanted" to true,
                    "key_default_mtu" to 1400,
                    "key_split_tunnel_excluded" to setOf("b.app", "a.app")
                )
            )
        )
        assertEquals("9.9.9.9", prefs.getString("key_default_dns"))
        assertEquals("true", prefs.getString("key_vpn_wanted"))
        assertEquals("1400", prefs.getString("key_default_mtu"))
        assertEquals("[\"a.app\",\"b.app\"]", prefs.getString("key_split_tunnel_excluded"))
    }
}
