package com.tailcat.vpn.core.token

import co.nstant.`in`.cbor.CborDecoder
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
    val derpRegionId: Int?,
    val expiresAtUnixSec: Long? = null,
    val issuedAtUnixSec: Long? = null
) {
    val isExpired: Boolean
        get() = expiresAtUnixSec != null && (System.currentTimeMillis() / 1000L > expiresAtUnixSec)

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
            null -> "Default DERP"
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
            if (dataItems.isEmpty() || dataItems[0] !is Map) {
                return Result.failure(IllegalArgumentException("Invalid CBOR payload: Expected map"))
            }

            val map = dataItems[0] as Map
            var pubKeyBytes: ByteArray? = null
            var regionId: Int? = null
            var expSec: Long? = null
            var iatSec: Long? = null

            for (key in map.keys) {
                val keyStr = when (key) {
                    is UnicodeString -> key.string
                    is ByteString -> key.bytes.decodeToString()
                    else -> key.toString()
                }

                val value = map.get(key)
                when (keyStr.lowercase()) {
                    "p", "pub", "nodekey" -> {
                        pubKeyBytes = when (value) {
                            is ByteString -> value.bytes
                            is UnicodeString -> value.string.hexToByteArray()
                            else -> null
                        }
                    }
                    "r", "region" -> {
                        regionId = when (value) {
                            is Number -> value.value.toInt()
                            else -> null
                        }
                    }
                    "exp", "expires", "expires_at" -> {
                        expSec = when (value) {
                            is Number -> value.value.toLong()
                            else -> null
                        }
                    }
                    "iat", "issued", "issued_at" -> {
                        iatSec = when (value) {
                            is Number -> value.value.toLong()
                            else -> null
                        }
                    }
                }
            }

            if (pubKeyBytes == null || pubKeyBytes.isEmpty()) {
                if (cborBytes.size >= 32) {
                    pubKeyBytes = cborBytes.copyOfRange(0, 32)
                } else {
                    return Result.failure(IllegalArgumentException("Missing 32-byte public key in token payload"))
                }
            }

            if (pubKeyBytes.size < 16) {
                return Result.failure(IllegalArgumentException("Public key length is too short (${pubKeyBytes.size} bytes)"))
            }

            val hexString = pubKeyBytes.joinToString("") { "%02x".format(it) }

            Result.success(
                ParsedToken(
                    rawToken = trimmed,
                    serverPublicKeyHex = hexString,
                    serverPublicKeyBytes = pubKeyBytes,
                    derpRegionId = regionId,
                    expiresAtUnixSec = expSec,
                    issuedAtUnixSec = iatSec
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Failed to decode CBOR payload: ${e.message}", e))
        }
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val clean = input.replace('-', '+').replace('_', '/')
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return Base64.getDecoder().decode(padded)
    }

    private fun String.hexToByteArray(): ByteArray {
        val clean = this.removePrefix("0x").trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
