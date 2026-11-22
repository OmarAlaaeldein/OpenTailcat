package com.tailcat.vpn.data

import org.json.JSONArray

/** Encrypts one preference value; [key] is bound to the ciphertext. */
internal interface ValueCipher {
    fun seal(key: String, plain: String): String
    fun open(key: String, sealed: String): String
}

/**
 * String preferences encrypted value by value. A value that cannot be
 * decrypted (the Keystore key was lost or reset) reads as missing instead of
 * crashing the app, and a value that cannot be encrypted is not written:
 * tokens never fall back to plaintext.
 */
internal class SealedPreferences(
    private val backing: Backing,
    private val cipher: ValueCipher
) {
    /** Where sealed values are stored (a SharedPreferences file on Android). */
    interface Backing {
        fun get(key: String): String?
        /** Applies every entry (null removes it); returns false if [commit] failed. */
        fun put(values: Map<String, String?>, commit: Boolean): Boolean
    }

    // Decrypted values, so a read does not reach the Keystore every time.
    private val lock = Any()
    private val cache = HashMap<String, Any>()

    fun getString(key: String): String? = synchronized(lock) {
        cache[key]?.let { return it as? String }
        val value = backing.get(key)?.let { sealed ->
            runCatching { cipher.open(key, sealed) }.getOrNull()
        }
        cache[key] = value ?: MISSING
        return value
    }

    /** Returns false when the value could not be encrypted or committed. */
    fun putString(key: String, value: String?, commit: Boolean = false): Boolean =
        putAll(mapOf(key to value), commit)

    /**
     * Copies [values] (typed values from an older preferences file) for keys
     * that hold nothing yet, in one commit.
     */
    fun importMissing(values: Map<String, Any?>): Boolean = synchronized(lock) {
        val missing = values.filterKeys { backing.get(it) == null }
            .mapValues { (_, value) -> encode(value) }
            .filterValues { it != null }
        missing.isEmpty() || putAllLocked(missing, commit = true)
    }

    private fun putAll(values: Map<String, String?>, commit: Boolean): Boolean =
        synchronized(lock) { putAllLocked(values, commit) }

    private fun putAllLocked(values: Map<String, String?>, commit: Boolean): Boolean {
        val sealed = values.mapValues { (key, value) ->
            value?.let { runCatching { cipher.seal(key, it) }.getOrNull() ?: return false }
        }
        if (!backing.put(sealed, commit)) return false
        values.forEach { (key, value) -> cache[key] = value ?: MISSING }
        return true
    }

    companion object {
        private val MISSING = Any()

        /** How typed values from an older store are kept as strings. */
        fun encode(value: Any?): String? = when (value) {
            null -> null
            is Set<*> -> JSONArray(value.filterIsInstance<String>().sorted()).toString()
            else -> value.toString()
        }
    }
}
