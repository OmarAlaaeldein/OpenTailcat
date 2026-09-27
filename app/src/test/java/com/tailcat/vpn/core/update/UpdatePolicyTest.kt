package com.tailcat.vpn.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val debug = "a".repeat(64)
    private val release = "b".repeat(64)
    private val attacker = "c".repeat(64)
    private val trusted = setOf(debug, release)

    private fun asset(
        name: String = "OpenTailcat-1.5.0-arm64-v8a.apk",
        url: String = "https://github.com/OmarAlaaeldein/OpenTailcat/releases/download/v1.5.0/$name",
        size: Long = 20_000_000,
        sha256: String? = "d".repeat(64)
    ) = ReleaseAsset(name, url, size, sha256)

    @Test
    fun parsesGitHubDigest() {
        val hex = "445b8d551fb6a094c6bfb9f46a741b17aa1359203909a51e07385d80b0ef58be"
        assertEquals(hex, UpdatePolicy.parseSha256("sha256:$hex"))
        assertEquals(hex, UpdatePolicy.parseSha256("sha256:${hex.uppercase()}"))
        assertNull(UpdatePolicy.parseSha256(null))
        assertNull(UpdatePolicy.parseSha256(""))
        assertNull(UpdatePolicy.parseSha256("sha1:0123"))
        assertNull(UpdatePolicy.parseSha256("sha256:${hex.dropLast(1)}"))
    }

    @Test
    fun allowsOnlyGitHubReleaseHostsOverHttps() {
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://github.com/o/r/releases/download/v1/a.apk"))
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://objects.githubusercontent.com/x"))
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/x?sig=1"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("http://github.com/o/r/a.apk"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://github.com.evil.example/a.apk"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://evil.example/github.com/a.apk"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://user@github.com/a.apk"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://github.com:8443/a.apk"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("not a url"))
    }

    @Test
    fun rejectsUnsafeAssets() {
        assertNull(UpdatePolicy.assetRejection(asset()))
        assertNotNull(UpdatePolicy.assetRejection(asset(sha256 = null)))
        assertNotNull(UpdatePolicy.assetRejection(asset(name = "../evil.apk")))
        assertNotNull(UpdatePolicy.assetRejection(asset(name = "a/b.apk")))
        assertNotNull(UpdatePolicy.assetRejection(asset(name = "OpenTailcat.aab")))
        assertNotNull(UpdatePolicy.assetRejection(asset(size = 0)))
        assertNotNull(UpdatePolicy.assetRejection(asset(size = UpdatePolicy.MAX_APK_BYTES + 1)))
        assertNotNull(UpdatePolicy.assetRejection(asset(url = "https://evil.example/a.apk")))
    }

    @Test
    fun keepsReleasePagesOnThisRepository() {
        val tag = "${UpdatePolicy.RELEASES_PAGE}/tag/v1.5.0"
        assertEquals(tag, UpdatePolicy.safeReleasePage(tag))
        assertEquals(
            "${UpdatePolicy.RELEASES_PAGE}/latest",
            UpdatePolicy.safeReleasePage("https://evil.example/")
        )
        assertEquals(
            "${UpdatePolicy.RELEASES_PAGE}/latest",
            UpdatePolicy.safeReleasePage("${UpdatePolicy.RELEASES_PAGE}.evil.example/")
        )
    }

    @Test
    fun acceptsSameTrustedSigner() {
        assertNull(UpdatePolicy.signerRejection(setOf(debug), setOf(debug), listOf(debug), trusted))
    }

    @Test
    fun acceptsRotationWhoseLineageContainsInstalledSigner() {
        assertNull(
            UpdatePolicy.signerRejection(setOf(debug), setOf(release), listOf(debug, release), trusted)
        )
    }

    @Test
    fun rejectsNewKeyWithoutLineage() {
        assertNotNull(UpdatePolicy.signerRejection(setOf(debug), setOf(release), listOf(release), trusted))
        assertNotNull(UpdatePolicy.signerRejection(setOf(debug), setOf(release), null, trusted))
    }

    @Test
    fun rejectsUntrustedSignerEvenWithValidLineage() {
        assertNotNull(
            UpdatePolicy.signerRejection(setOf(debug), setOf(attacker), listOf(debug, attacker), trusted)
        )
        assertNotNull(
            UpdatePolicy.signerRejection(setOf(attacker), setOf(attacker), listOf(attacker), trusted)
        )
    }

    @Test
    fun rejectsUnreadableSignatures() {
        assertNotNull(UpdatePolicy.signerRejection(setOf(debug), emptySet(), null, trusted))
        assertNotNull(UpdatePolicy.signerRejection(emptySet(), setOf(debug), listOf(debug), trusted))
    }

    @Test
    fun multipleSignersMustMatchExactly() {
        assertNull(UpdatePolicy.signerRejection(trusted, trusted, null, trusted))
        assertNotNull(UpdatePolicy.signerRejection(setOf(debug), trusted, null, trusted))
    }

    @Test
    fun pinsTheCurrentReleaseSigner() {
        assertTrue(
            "dd767011a3d1223c6145cec024dccb02730e73607fc39544b2fac93adeafa8d6" in
                UpdatePolicy.TRUSTED_SIGNER_SHA256
        )
    }
}
