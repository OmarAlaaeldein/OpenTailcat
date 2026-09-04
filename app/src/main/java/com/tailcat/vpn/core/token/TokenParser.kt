package com.tailcat.vpn.core.token

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

enum class TokenClassification {
    VALID_OFFICIAL_SHORT,
    VALID_OFFICIAL_RESOLVED,
    LEGACY_REISSUE_REQUIRED,
    EXPIRED,
    INVALID
}

enum class TokenErrorCode {
    ERR_NONE,
    ERR_TOKEN_LENGTH,
    ERR_WHITESPACE,
    ERR_INVALID_PREFIX,
    ERR_BASE64_CHAR,
    ERR_BASE64_PADDED,
    ERR_BASE64_DECODE,
    ERR_CBOR_TOO_LARGE,
    ERR_CBOR_MALFORMED,
    ERR_NOT_MAP,
    ERR_DUPLICATE_KEY,
    ERR_TRAILING_DATA,
    ERR_MISSING_NODE_KEY,
    ERR_INVALID_NODE_KEY,
    ERR_MISSING_DISCO_KEY,
    ERR_INVALID_DISCO_KEY,
    ERR_SYNTHETIC_DISCO_KEY,
    ERR_MISSING_REGION,
    ERR_INVALID_REGION_ID,
    ERR_INVALID_REGION_TYPE,
    ERR_INVALID_STRUCTURED_REGION,
    ERR_INVALID_EXPIRATION,
    ERR_INVALID_ISSUED_AT,
    ERR_EXP_BEFORE_IAT,
    ERR_UNKNOWN_FIELD
}

data class ParsedToken(
    val rawToken: String,
    val classification: TokenClassification,
    val errorCode: TokenErrorCode = TokenErrorCode.ERR_NONE,
    val errorMessage: String? = null,
    val serverPublicKeyHex: String = "",
    val serverDiscoKeyHex: String? = null,
    val derpRegionId: Int? = null,
    val hasEmbeddedRegion: Boolean = false,
    val expiresAtUnixSec: Long? = null
) {
    val isExpired: Boolean
        get() = expiresAtUnixSec != null && (System.currentTimeMillis() / 1000L >= expiresAtUnixSec)

    val isConnectable: Boolean
        get() = (classification == TokenClassification.VALID_OFFICIAL_SHORT ||
                classification == TokenClassification.VALID_OFFICIAL_RESOLVED) && !isExpired

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
        return rawToken == other.rawToken &&
                classification == other.classification &&
                serverPublicKeyHex == other.serverPublicKeyHex
    }

    override fun hashCode(): Int {
        return rawToken.hashCode()
    }
}

sealed class TokenValidationState {
    data class Valid(val parsed: ParsedToken) : TokenValidationState()
    data class Expired(val parsed: ParsedToken, val expiredDate: String) : TokenValidationState()
    data class LegacyReissueRequired(val parsed: ParsedToken) : TokenValidationState()
    data class Invalid(val reason: String, val errorCode: TokenErrorCode = TokenErrorCode.ERR_NONE) : TokenValidationState()
    object Empty : TokenValidationState()
}

object TokenParser {

    private const val PREFIX = "tc"
    private const val MAX_TOKEN_STRING_LENGTH = 65536
    private const val MAX_CBOR_PAYLOAD_BYTES = 32768
    private const val MAX_UNIX_TIMESTAMP_SEC = 253_402_300_799L
    private const val PUBLIC_KEY_SIZE_BYTES = 32

    private val ALLOWED_CANONICAL_FIELDS = setOf("p", "k", "i", "r", "exp", "iat")

    /**
     * Validates a token string and returns structured validation state for UI presentation.
     */
    fun validate(input: String): TokenValidationState {
        if (input.isEmpty()) return TokenValidationState.Empty

        val parsed = parse(input)
        return when (parsed.classification) {
            TokenClassification.VALID_OFFICIAL_SHORT, TokenClassification.VALID_OFFICIAL_RESOLVED -> {
                if (parsed.isExpired) {
                    TokenValidationState.Expired(
                        parsed = parsed,
                        expiredDate = parsed.expirationFormatted ?: "in the past"
                    )
                } else {
                    TokenValidationState.Valid(parsed)
                }
            }
            TokenClassification.EXPIRED -> {
                TokenValidationState.Expired(
                    parsed = parsed,
                    expiredDate = parsed.expirationFormatted ?: "in the past"
                )
            }
            TokenClassification.LEGACY_REISSUE_REQUIRED -> {
                TokenValidationState.LegacyReissueRequired(parsed)
            }
            TokenClassification.INVALID -> {
                TokenValidationState.Invalid(
                    reason = parsed.errorMessage ?: "Invalid token format",
                    errorCode = parsed.errorCode
                )
            }
        }
    }

    /**
     * Strictly parses and classifies a Tailcat token using upstream Tailcat v0.4.0 as authority.
     * No silent trimming or field rewriting is performed.
     */
    fun parse(input: String): ParsedToken {
        if (input.isEmpty()) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_TOKEN_LENGTH,
                errorMessage = "token cannot be empty"
            )
        }

        if (input.length > MAX_TOKEN_STRING_LENGTH) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_TOKEN_LENGTH,
                errorMessage = "token exceeds maximum length"
            )
        }

        // Reject leading or trailing whitespace (no silent trimming)
        if (input.first() <= ' ' || input.last() <= ' ') {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_WHITESPACE,
                errorMessage = "token must not contain leading or trailing whitespace"
            )
        }

        // Exact lowercase "tc" prefix required
        if (!input.startsWith(PREFIX)) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_INVALID_PREFIX,
                errorMessage = "token must start with exact lowercase \"tc\" prefix"
            )
        }

        val b64Payload = input.substring(PREFIX.length)
        if (b64Payload.isEmpty()) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_TOKEN_LENGTH,
                errorMessage = "token payload cannot be empty"
            )
        }

        for (i in b64Payload.indices) {
            val c = b64Payload[i]
            if (c == '=') {
                return ParsedToken(
                    rawToken = input,
                    classification = TokenClassification.INVALID,
                    errorCode = TokenErrorCode.ERR_BASE64_PADDED,
                    errorMessage = "Base64URL padding '=' is forbidden; upstream requires unpadded base64.RawURLEncoding"
                )
            }
            val valid = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_'
            if (!valid) {
                return ParsedToken(
                    rawToken = input,
                    classification = TokenClassification.INVALID,
                    errorCode = TokenErrorCode.ERR_BASE64_CHAR,
                    errorMessage = "invalid character '$c' in Base64URL token payload"
                )
            }
        }

        if (b64Payload.length % 4 == 1) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_BASE64_DECODE,
                errorMessage = "invalid unpadded Base64URL length (mod 4 is 1)"
            )
        }

        val cborBytes = try {
            val padded = when (b64Payload.length % 4) {
                2 -> "$b64Payload=="
                3 -> "$b64Payload="
                else -> b64Payload
            }
            Base64.getUrlDecoder().decode(padded)
        } catch (e: Exception) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_BASE64_DECODE,
                errorMessage = "base64 decode: ${e.message}"
            )
        }

        if (cborBytes.size > MAX_CBOR_PAYLOAD_BYTES) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_CBOR_TOO_LARGE,
                errorMessage = "CBOR payload exceeds maximum size"
            )
        }

        val reader = StrictCborReader(cborBytes)
        val rawMap = try {
            reader.readTopLevelTokenMap()
        } catch (e: StrictCborException) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = e.code,
                errorMessage = e.message
            )
        } catch (e: Exception) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_CBOR_MALFORMED,
                errorMessage = e.message ?: "CBOR malformed"
            )
        }

        var pubKeyBytes: ByteArray? = null
        var discoKeyBytes: ByteArray? = null
        var regionIdNum: Long = 0
        var hasExplicitRegionID = false
        var hasNumericR = false
        var hasStructuredR = false
        var structuredRegionFirstId = 0
        var expSec: Long? = null
        var iatSec: Long? = null

        for ((k, v) in rawMap) {
            if (!ALLOWED_CANONICAL_FIELDS.contains(k)) {
                return ParsedToken(
                    rawToken = input,
                    classification = TokenClassification.INVALID,
                    errorCode = TokenErrorCode.ERR_UNKNOWN_FIELD,
                    errorMessage = "unknown token field \"$k\""
                )
            }

            when (k) {
                "p" -> {
                    if (v !is ByteArray || v.size != PUBLIC_KEY_SIZE_BYTES) {
                        return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_NODE_KEY,
                            errorMessage = "node public key 'p' must be exactly $PUBLIC_KEY_SIZE_BYTES bytes"
                        )
                    }
                    if (v.all { it == 0.toByte() }) {
                        return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_NODE_KEY,
                            errorMessage = "node public key cannot be all zero bytes"
                        )
                    }
                    pubKeyBytes = v
                }
                "k" -> {
                    if (v !is ByteArray || v.size != PUBLIC_KEY_SIZE_BYTES) {
                        return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_DISCO_KEY,
                            errorMessage = "disco public key 'k' must be exactly $PUBLIC_KEY_SIZE_BYTES bytes"
                        )
                    }
                    if (v.all { it == 0.toByte() }) {
                        return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_DISCO_KEY,
                            errorMessage = "disco public key cannot be all zero bytes"
                        )
                    }
                    discoKeyBytes = v
                }
                "i" -> {
                    val num = when (v) {
                        is Long -> v
                        is Int -> v.toLong()
                        else -> return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_REGION_ID,
                            errorMessage = "region ID 'i' must be a positive integer"
                        )
                    }
                    if (num !in 1..65535) {
                        return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_REGION_ID,
                            errorMessage = "region ID 'i' must be in range 1..65535"
                        )
                    }
                    regionIdNum = num
                    hasExplicitRegionID = true
                }
                "r" -> {
                    when (v) {
                        is Long, is Int -> {
                            val num = if (v is Long) v else (v as Int).toLong()
                            if (num !in 1..65535) {
                                return ParsedToken(
                                    rawToken = input,
                                    classification = TokenClassification.INVALID,
                                    errorCode = TokenErrorCode.ERR_INVALID_REGION_ID,
                                    errorMessage = "numeric legacy region 'r' out of range"
                                )
                            }
                            regionIdNum = num
                            hasNumericR = true
                        }
                        is List<*> -> {
                            if (v.isEmpty()) {
                                return ParsedToken(
                                    rawToken = input,
                                    classification = TokenClassification.INVALID,
                                    errorCode = TokenErrorCode.ERR_INVALID_STRUCTURED_REGION,
                                    errorMessage = "embedded region array 'r' cannot be empty"
                                )
                            }
                            try {
                                structuredRegionFirstId = parseStructuredRegions(v)
                                hasStructuredR = true
                            } catch (e: Exception) {
                                return ParsedToken(
                                    rawToken = input,
                                    classification = TokenClassification.INVALID,
                                    errorCode = TokenErrorCode.ERR_INVALID_STRUCTURED_REGION,
                                    errorMessage = e.message ?: "Invalid structured region"
                                )
                            }
                        }
                        else -> {
                            return ParsedToken(
                                rawToken = input,
                                classification = TokenClassification.INVALID,
                                errorCode = TokenErrorCode.ERR_INVALID_REGION_TYPE,
                                errorMessage = "region 'r' must be an integer (legacy) or array of DERP regions"
                            )
                        }
                    }
                }
                "exp" -> {
                    val num = when (v) {
                        is Long -> v
                        is Int -> v.toLong()
                        else -> return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_EXPIRATION,
                            errorMessage = "expiration timestamp must be an integer"
                        )
                    }
                    if (num !in 1..MAX_UNIX_TIMESTAMP_SEC) {
                        return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_EXPIRATION,
                            errorMessage = "expiration timestamp out of range"
                        )
                    }
                    expSec = num
                }
                "iat" -> {
                    val num = when (v) {
                        is Long -> v
                        is Int -> v.toLong()
                        else -> return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_ISSUED_AT,
                            errorMessage = "issued-at timestamp must be an integer"
                        )
                    }
                    if (num !in 1..MAX_UNIX_TIMESTAMP_SEC) {
                        return ParsedToken(
                            rawToken = input,
                            classification = TokenClassification.INVALID,
                            errorCode = TokenErrorCode.ERR_INVALID_ISSUED_AT,
                            errorMessage = "issued-at timestamp out of range"
                        )
                    }
                    iatSec = num
                }
            }
        }

        if (pubKeyBytes == null) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_MISSING_NODE_KEY,
                errorMessage = "missing required node public key 'p'"
            )
        }

        if (expSec != null && iatSec != null && expSec < iatSec) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_EXP_BEFORE_IAT,
                errorMessage = "expiration timestamp cannot be earlier than issued-at timestamp"
            )
        }

        // Reject synthetic disco key where k == p
        if (discoKeyBytes != null && discoKeyBytes.contentEquals(pubKeyBytes)) {
            return ParsedToken(
                rawToken = input,
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_SYNTHETIC_DISCO_KEY,
                errorMessage = "synthetic disco key equal to node public key is forbidden"
            )
        }

        val effectiveRegionId = if (regionIdNum != 0L) {
            regionIdNum.toInt()
        } else if (hasStructuredR) {
            structuredRegionFirstId
        } else {
            null
        }

        val hexPubKey = pubKeyBytes.joinToString("") { "%02x".format(it) }
        val hexDiscoKey = discoKeyBytes?.joinToString("") { "%02x".format(it) }

        val candidate = ParsedToken(
            rawToken = input,
            classification = TokenClassification.VALID_OFFICIAL_SHORT,
            errorCode = TokenErrorCode.ERR_NONE,
            errorMessage = null,
            serverPublicKeyHex = hexPubKey,
            serverDiscoKeyHex = hexDiscoKey,
            derpRegionId = effectiveRegionId,
            hasEmbeddedRegion = hasStructuredR,
            expiresAtUnixSec = expSec
        )

        // 1. Check expiration
        if (candidate.isExpired) {
            return candidate.copy(
                classification = TokenClassification.EXPIRED,
                errorMessage = "connection token has expired"
            )
        }

        // 2. Check legacy numeric-r token schema
        if (hasNumericR) {
            if (discoKeyBytes != null) {
                return candidate.copy(
                    classification = TokenClassification.INVALID,
                    errorCode = TokenErrorCode.ERR_INVALID_REGION_TYPE,
                    errorMessage = "official tokens with disco key must use 'i' or structured 'r', not numeric 'r'"
                )
            }
            return candidate.copy(
                classification = TokenClassification.LEGACY_REISSUE_REQUIRED,
                errorMessage = "legacy numeric-r token schema lacks separate disco key; reissue required"
            )
        }

        // 3. Official token requirements: must have separate disco key 'k'
        if (discoKeyBytes == null) {
            return candidate.copy(
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_MISSING_DISCO_KEY,
                errorMessage = "official token missing required disco key 'k'"
            )
        }

        // 4. Region presence check
        if (!hasExplicitRegionID && !hasStructuredR) {
            return candidate.copy(
                classification = TokenClassification.INVALID,
                errorCode = TokenErrorCode.ERR_MISSING_REGION,
                errorMessage = "missing region specification (neither 'i' nor valid 'r' present)"
            )
        }

        // 5. Official short vs resolved classification
        val finalClassification = if (hasStructuredR) {
            TokenClassification.VALID_OFFICIAL_RESOLVED
        } else {
            TokenClassification.VALID_OFFICIAL_SHORT
        }

        return candidate.copy(classification = finalClassification)
    }

    private fun parseStructuredRegions(regionsList: List<*>): Int {
        var firstId = 1
        for (ri in regionsList.indices) {
            val item = regionsList[ri]
            if (item !is Map<*, *>) {
                throw IllegalArgumentException("embedded region entry must be a map")
            }
            val idVal = item["i"]
            val rId = when (idVal) {
                is Long -> idVal.toInt()
                is Int -> idVal
                null -> ri + 1
                else -> throw IllegalArgumentException("invalid region ID in embedded region")
            }
            if (rId !in 1..65535) {
                throw IllegalArgumentException("embedded region has invalid region ID: $rId")
            }
            if (ri == 0) {
                firstId = rId
            }
            val nodesVal = item["N"]
            if (nodesVal is List<*>) {
                if (nodesVal.isEmpty()) {
                    throw IllegalArgumentException("embedded region has empty nodes array")
                }
                for (nItem in nodesVal) {
                    if (nItem !is Map<*, *>) {
                        throw IllegalArgumentException("embedded DERP node must be a map")
                    }
                    val host = nItem["h"] as? String
                    if (host.isNullOrEmpty()) {
                        throw IllegalArgumentException("embedded DERP node missing required HostName 'h'")
                    }
                }
            }
        }
        return firstId
    }
}

class StrictCborException(val code: TokenErrorCode, override val message: String) : Exception(message)

class StrictCborReader(private val data: ByteArray) {
    private var off = 0

    private fun remaining(): Int = data.size - off

    private fun readByte(): Int {
        if (off >= data.size) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "unexpected EOF")
        return data[off++].toInt() and 0xff
    }

    private fun readBytes(n: Int): ByteArray {
        if (n < 0 || remaining() < n) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "unexpected EOF")
        val res = data.copyOfRange(off, off + n)
        off += n
        return res
    }

    private fun readLength(info: Int): Long {
        return when {
            info < 24 -> info.toLong()
            info == 24 -> readByte().toLong()
            info == 25 -> {
                val b = readBytes(2)
                ((b[0].toInt() and 0xff shl 8) or (b[1].toInt() and 0xff)).toLong()
            }
            info == 26 -> {
                val b = readBytes(4)
                ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            }
            info == 27 -> {
                val b = readBytes(8)
                val v = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).long
                if (v < 0) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "length overflow")
                v
            }
            info == 31 -> throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "indefinite-length CBOR is forbidden")
            else -> throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "reserved length info: $info")
        }
    }

    fun readTopLevelTokenMap(): Map<String, Any?> {
        val first = readByte()
        val major = first shr 5
        val info = first and 0x1f

        if (major != 5) {
            throw StrictCborException(TokenErrorCode.ERR_NOT_MAP, "expected top-level CBOR map (major 5), got $major")
        }
        if (info == 31) {
            throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "indefinite-length map forbidden")
        }

        val numPairs = readLength(info)
        if (numPairs > 32) {
            throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "too many map entries")
        }

        val result = mutableMapOf<String, Any?>()
        val seenExactKeys = mutableSetOf<String>()

        for (i in 0 until numPairs) {
            val kHdr = readByte()
            val kMajor = kHdr shr 5
            val kInfo = kHdr and 0x1f
            if (kMajor != 3) {
                throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "map key must be text string")
            }
            val kLen = readLength(kInfo)
            if (kLen > 128) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "map key length exceeds limit")
            val kStr = String(readBytes(kLen.toInt()), Charsets.UTF_8)

            if (seenExactKeys.contains(kStr)) {
                throw StrictCborException(TokenErrorCode.ERR_DUPLICATE_KEY, "duplicate map key: \"$kStr\"")
            }
            seenExactKeys.add(kStr)

            val v = readStrictValue(0)
            result[kStr] = v
        }

        if (remaining() > 0) {
            throw StrictCborException(TokenErrorCode.ERR_TRAILING_DATA, "trailing ${remaining()} bytes after CBOR map")
        }

        return result
    }

    private fun readStrictValue(depth: Int): Any? {
        if (depth > 16) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "recursion depth exceeded")

        val hdr = readByte()
        val major = hdr shr 5
        val info = hdr and 0x1f

        if (info == 31) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "indefinite-length items forbidden")

        return when (major) {
            0 -> readLength(info)
            1 -> {
                val v = readLength(info)
                -1 - v
            }
            2 -> {
                val len = readLength(info)
                if (len > 65536) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "byte string too long")
                readBytes(len.toInt())
            }
            3 -> {
                val len = readLength(info)
                if (len > 65536) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "text string too long")
                String(readBytes(len.toInt()), Charsets.UTF_8)
            }
            4 -> {
                val numItems = readLength(info)
                if (numItems > 256) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "array too large")
                val list = ArrayList<Any?>(numItems.toInt())
                for (i in 0 until numItems) {
                    list.add(readStrictValue(depth + 1))
                }
                list
            }
            5 -> {
                val numPairs = readLength(info)
                if (numPairs > 256) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "nested map too large")
                val map = mutableMapOf<String, Any?>()
                val seen = mutableSetOf<String>()
                for (i in 0 until numPairs) {
                    val kHdr = readByte()
                    val kMajor = kHdr shr 5
                    val kInfo = kHdr and 0x1f
                    if (kMajor != 3) throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "nested key must be text string")
                    val kLen = readLength(kInfo)
                    val kStr = String(readBytes(kLen.toInt()), Charsets.UTF_8)
                    if (seen.contains(kStr)) throw StrictCborException(TokenErrorCode.ERR_DUPLICATE_KEY, "duplicate nested key: \"$kStr\"")
                    seen.add(kStr)
                    map[kStr] = readStrictValue(depth + 1)
                }
                map
            }
            7 -> {
                when (info) {
                    20 -> false
                    21 -> true
                    22 -> null
                    25, 26, 27 -> throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "floating-point numbers forbidden")
                    else -> throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "unsupported simple value: $info")
                }
            }
            else -> throw StrictCborException(TokenErrorCode.ERR_CBOR_MALFORMED, "unsupported major type: $major")
        }
    }
}
