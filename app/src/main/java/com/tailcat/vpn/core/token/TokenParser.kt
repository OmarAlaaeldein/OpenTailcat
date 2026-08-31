package com.tailcat.vpn.core.token

import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.Map
import co.nstant.`in`.cbor.model.Number
import co.nstant.`in`.cbor.model.UnicodeString
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

data class ParsedToken(
    val rawToken: String,
    val serverPublicKeyHex: String,
    val serverPublicKeyBytes: ByteArray,
    val serverDiscoKeyHex: String? = null,
    val serverDiscoKeyBytes: ByteArray? = null,
    val derpRegionId: Int?,
    val hasEmbeddedRegion: Boolean = false,
    val expiresAtUnixSec: Long? = null,
    val issuedAtUnixSec: Long? = null
) {
    val isExpired: Boolean
        get() = expiresAtUnixSec != null && (System.currentTimeMillis() / 1000L >= expiresAtUnixSec)

    val expirationFormatted: String?
        get() = expiresAtUnixSec?.let {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(it * 1000L))
        }

    val serverKeyShort: String
        get() = if (serverPublicKeyHex.length >= 12) {
            "${serverPublicKeyHex.take(6)}...${serverPublicKeyHex.takeLast(4)}"
        } else {
            serverPublicKeyHex
        }

    val regionDisplayName: String
        get() = when (derpRegionId) {
            1 -> "NYC (Region 1)"
            2 -> "SFO (Region 2)"
            3 -> "Singapore (Region 3)"
            4 -> "Frankfurt (Region 4)"
            5 -> "Sydney (Region 5)"
            6 -> "London (Region 6)"
            7 -> "Tokyo (Region 7)"
            8 -> "Toronto (Region 8)"
            9 -> "Dallas (Region 9)"
            10 -> "Seattle (Region 10)"
            302 -> "San Francisco (Region 302)"
            null -> if (hasEmbeddedRegion) "Embedded DERP Map" else "Default DERP"
            else -> "DERP Region $derpRegionId"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ParsedToken
        return rawToken == other.rawToken && serverPublicKeyHex == other.serverPublicKeyHex
    }

    override fun hashCode(): Int {
        return rawToken.hashCode()
    }
}

sealed class TokenValidationState {
    data class Valid(val parsed: ParsedToken) : TokenValidationState()
    data class Expired(val parsed: ParsedToken, val expiredDate: String) : TokenValidationState()
    data class Invalid(val reason: String) : TokenValidationState()
    object Empty : TokenValidationState()
}

object TokenParser {

    private const val PREFIX = "tc"

    /**
     * Validates a token string and returns structured validation state for real-time UI feedback.
     */
    fun validate(input: String): TokenValidationState {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return TokenValidationState.Empty

        return parse(trimmed).fold(
            onSuccess = { parsed ->
                if (parsed.isExpired) {
                    TokenValidationState.Expired(
                        parsed = parsed,
                        expiredDate = parsed.expirationFormatted ?: "in the past"
                    )
                } else {
                    TokenValidationState.Valid(parsed)
                }
            },
            onFailure = { TokenValidationState.Invalid(it.message ?: "Invalid token format") }
        )
    }

    /**
     * Parses a Tailcat token (tc-prefixed Base64URL-encoded CBOR map).
     * Supports official Tailcat v0.4.0 format (p: nodeKey, k: discoKey, i: regionId, r: embedded regions)
     * as well as legacy token schema (p: nodeKey, r: regionId, exp/iat timestamps).
     * @param input Raw token string, e.g., "tcABC..."
     */
    fun parse(input: String): Result<ParsedToken> {
        val trimmed = input.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Token must start with '$PREFIX' prefix"))
        }

        val b64Payload = trimmed.substring(PREFIX.length)
        if (b64Payload.isEmpty()) {
            return Result.failure(IllegalArgumentException("Token payload cannot be empty"))
        }

        val cborBytes = try {
            decodeBase64Url(b64Payload)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid Base64URL encoding in token payload: ${e.message}", e))
        }

        return try {
            val dataItems = CborDecoder(ByteArrayInputStream(cborBytes)).decode()
            if (dataItems.size != 1 || dataItems[0] !is Map) {
                return Result.failure(IllegalArgumentException("Invalid CBOR payload: Expected map"))
            }

            val map = dataItems[0] as Map
            var pubKeyBytes: ByteArray? = null
            var discoKeyBytes: ByteArray? = null
            var regionId: Int? = null
            var hasEmbeddedRegion = false
            var expSec: Long? = null
            var iatSec: Long? = null
            var foundPublicKey = false
            var foundDiscoKey = false
            var foundRegionId = false
            var foundEmbeddedRegion = false
            var foundExpiration = false
            var foundIssuedAt = false

            for (key in map.keys) {
                val keyStr = when (key) {
                    is UnicodeString -> key.string
                    is ByteString -> key.bytes.decodeToString()
                    else -> key.toString()
                }

                val value = map.get(key)
                when (keyStr.lowercase()) {
                    "p", "pub", "nodekey" -> {
                        require(!foundPublicKey) { "Token contains duplicate public key fields" }
                        foundPublicKey = true
                        pubKeyBytes = when (value) {
                            is ByteString -> value.bytes
                            is UnicodeString -> value.string.hexToByteArray()
                            else -> throw IllegalArgumentException("Public key must be bytes or hexadecimal text")
                        }
                    }
                    "k", "disco", "discokey" -> {
                        require(!foundDiscoKey) { "Token contains duplicate disco key fields" }
                        foundDiscoKey = true
                        discoKeyBytes = when (value) {
                            is ByteString -> value.bytes
                            is UnicodeString -> value.string.hexToByteArray()
                            else -> throw IllegalArgumentException("Disco key must be bytes or hexadecimal text")
                        }
                    }
                    "i", "region_id" -> {
                        require(!foundRegionId) { "Token contains duplicate region ID fields" }
                        foundRegionId = true
                        regionId = when (value) {
                            is Number -> value.value.toLong().also {
                                require(it in 1..Int.MAX_VALUE.toLong()) { "DERP region ID must be a positive integer" }
                            }.toInt()
                            else -> throw IllegalArgumentException("DERP region ID must be an integer")
                        }
                    }
                    "r", "region" -> {
                        when (value) {
                            is Number -> {
                                require(!foundRegionId) { "Token contains duplicate region ID fields" }
                                foundRegionId = true
                                regionId = value.value.toLong().also {
                                    require(it in 1..Int.MAX_VALUE.toLong()) { "DERP region must be a positive integer" }
                                }.toInt()
                            }
                            is CborArray -> {
                                require(!foundEmbeddedRegion) { "Token contains duplicate embedded region fields" }
                                foundEmbeddedRegion = true
                                hasEmbeddedRegion = true
                                val items = value.dataItems
                                if (items.isNotEmpty() && items[0] is Map) {
                                    val firstRegionMap = items[0] as Map
                                    for (rk in firstRegionMap.keys) {
                                        val rkStr = when (rk) {
                                            is UnicodeString -> rk.string
                                            is ByteString -> rk.bytes.decodeToString()
                                            else -> rk.toString()
                                        }
                                        if (rkStr == "i") {
                                            val rVal = firstRegionMap.get(rk)
                                            if (rVal is Number) {
                                                val id = rVal.value.toLong()
                                                if (id in 1..Int.MAX_VALUE.toLong()) {
                                                    regionId = id.toInt()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            else -> throw IllegalArgumentException("DERP region must be an integer or array of regions")
                        }
                    }
                    "exp", "expires", "expires_at" -> {
                        require(!foundExpiration) { "Token contains duplicate expiration fields" }
                        foundExpiration = true
                        expSec = when (value) {
                            is Number -> value.value.toLong().also {
                                require(it in 1..MAX_UNIX_TIMESTAMP_SEC) { "Expiration timestamp is out of range" }
                            }
                            else -> throw IllegalArgumentException("Expiration timestamp must be an integer")
                        }
                    }
                    "iat", "issued", "issued_at" -> {
                        require(!foundIssuedAt) { "Token contains duplicate issued-at fields" }
                        foundIssuedAt = true
                        iatSec = when (value) {
                            is Number -> value.value.toLong().also {
                                require(it in 1..MAX_UNIX_TIMESTAMP_SEC) { "Issued-at timestamp is out of range" }
                            }
                            else -> throw IllegalArgumentException("Issued-at timestamp must be an integer")
                        }
                    }
                }
            }

            require(pubKeyBytes != null) { "Missing 32-byte public key in token payload" }
            require(pubKeyBytes.size == PUBLIC_KEY_SIZE_BYTES) {
                "Public key must be exactly $PUBLIC_KEY_SIZE_BYTES bytes (was ${pubKeyBytes.size})"
            }
            if (discoKeyBytes != null) {
                require(discoKeyBytes.size == PUBLIC_KEY_SIZE_BYTES) {
                    "Disco public key must be exactly $PUBLIC_KEY_SIZE_BYTES bytes (was ${discoKeyBytes.size})"
                }
            }
            require(regionId != null || hasEmbeddedRegion) { "Missing DERP region in token payload" }
            require(expSec == null || iatSec == null || expSec >= iatSec) {
                "Expiration timestamp cannot be earlier than issued-at timestamp"
            }

            val hexString = pubKeyBytes.joinToString("") { "%02x".format(it) }
            val discoHexString = discoKeyBytes?.joinToString("") { "%02x".format(it) }

            Result.success(
                ParsedToken(
                    rawToken = trimmed,
                    serverPublicKeyHex = hexString,
                    serverPublicKeyBytes = pubKeyBytes,
                    serverDiscoKeyHex = discoHexString,
                    serverDiscoKeyBytes = discoKeyBytes,
                    derpRegionId = regionId,
                    hasEmbeddedRegion = hasEmbeddedRegion,
                    expiresAtUnixSec = expSec,
                    issuedAtUnixSec = iatSec
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Failed to decode CBOR payload: ${e.message}", e))
        }
    }

    private fun decodeBase64Url(input: String): ByteArray {
        require(BASE64_URL_PATTERN.matches(input)) { "Payload contains non-Base64URL characters" }
        val unpadded = input.trimEnd('=')
        require(unpadded.length % 4 != 1) { "Invalid Base64URL payload length" }
        val padded = when (unpadded.length % 4) {
            2 -> "$unpadded=="
            3 -> "$unpadded="
            else -> unpadded
        }
        return Base64.getUrlDecoder().decode(padded)
    }

    private fun String.hexToByteArray(): ByteArray {
        val clean = this.removePrefix("0x").trim()
        require(clean.length == PUBLIC_KEY_SIZE_BYTES * 2) {
            "Hex public key must contain ${PUBLIC_KEY_SIZE_BYTES * 2} characters"
        }
        require(clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Hex public key contains invalid characters"
        }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private const val PUBLIC_KEY_SIZE_BYTES = 32
    private const val MAX_UNIX_TIMESTAMP_SEC = 253_402_300_799L
    private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+={0,2}$")
}

