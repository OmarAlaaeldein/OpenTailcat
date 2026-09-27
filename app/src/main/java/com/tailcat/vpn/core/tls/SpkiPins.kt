package com.tailcat.vpn.core.tls

import java.security.MessageDigest
import java.util.Base64

/**
 * SHA-256 SubjectPublicKeyInfo pins for 1.1.1.1 / one.one.one.one and
 * speed.cloudflare.com. Leaf keys rotate with every certificate, so only CA
 * keys are pinned: the intermediates in use (2026-09) and the roots of every
 * CA family Cloudflare issues edge certificates from. Must equal the list in
 * core-engine/tls_pin.go (SpkiPinsTest compares them).
 */
object SpkiPins {
    val PINS: Set<String> = setOf(
        "zGgA4OU4DjJdvpRYUqbi5Vh2g9W5Oc/PgKihy9mkLsE=", // SSL.com SSL Intermediate CA ECC R2
        "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=", // WE1 (Google Trust Services)
        "oyD01TTXvpfBro3QSZc1vIlcMjrdLTiL/M9mLCPX+Zo=", // SSL.com Root Certification Authority ECC
        "0cRTd+vc1hjNFlHcLgLCHXUeWqn80bNDH/bs9qMTSPo=", // SSL.com Root Certification Authority RSA
        "G/ANXI8TwJTdF+AFBM8IiIUPEv0Gf6H5LA/b9guG4yE=", // SSL.com TLS ECC Root CA 2022
        "K89VOmb1cJAN3TK6bf4ezAbJGC1mLcG2Dh97dnwr3VQ=", // SSL.com TLS RSA Root CA 2022
        "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", // GTS Root R1
        "Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78=", // GTS Root R2
        "QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=", // GTS Root R3
        "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=", // GTS Root R4
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1
        "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // ISRG Root X2
        "r/mIkG3eEpVdm+u/ko/cwxzOMo1bk4TyHIlByibiA5E=", // DigiCert Global Root CA
        "i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=" // DigiCert Global Root G2
    )

    /** Base64 SHA-256 of a DER SubjectPublicKeyInfo. */
    fun pinOf(spkiDer: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiDer))

    /**
     * True when any key in [verifiedChainSpki] (the DER SubjectPublicKeyInfo of
     * each certificate in a chain the platform verified) is pinned.
     */
    fun matches(verifiedChainSpki: List<ByteArray>, pins: Set<String> = PINS): Boolean =
        verifiedChainSpki.any { pinOf(it) in pins }
}
