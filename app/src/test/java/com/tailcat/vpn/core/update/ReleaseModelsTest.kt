package com.tailcat.vpn.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseModelsTest {

    private fun releaseJson(
        tag: String = "v1.4.0",
        assets: String = """
            [
              {"name":"OpenTailcat-1.4.0-arm64-v8a.apk","browser_download_url":"https://example.com/arm.apk","size":100},
              {"name":"OpenTailcat-1.4.0-x86_64.apk","browser_download_url":"https://example.com/x64.apk","size":110},
              {"name":"OpenTailcat-1.4.0.aab","browser_download_url":"https://example.com/app.aab","size":120}
            ]
        """.trimIndent()
    ) = """
        {
          "tag_name": "$tag",
          "name": "Release ${tag.removePrefix("v")}",
          "body": "## What's changed\n- fix tunnel",
          "html_url": "https://github.com/OmarAlaaeldein/OpenTailcat/releases/tag/$tag",
          "assets": $assets
        }
    """.trimIndent()

    @Test
    fun parsesLatestReleaseAndSelectsAbiApk() {
        val r = LatestRelease.parse(releaseJson())
        assertEquals("v1.4.0", r.tagName)
        assertEquals("1.4.0", r.normalizedVersion())
        assertEquals(3, r.assets.size)
        assertEquals(
            "OpenTailcat-1.4.0-arm64-v8a.apk",
            r.apkAssetForAbi("arm64-v8a")?.name
        )
        assertEquals(
            "OpenTailcat-1.4.0-x86_64.apk",
            r.apkAssetForAbi("x86_64")?.name
        )
        assertTrue(r.notes.contains("fix tunnel"))
    }

    @Test
    fun selectsApkByVersionNameFallback() {
        val r = LatestRelease.parse(releaseJson(tag = "v1.5.0"))
        // Preferred OpenTailcat-1.50... may not match version rewrite; endsWith abi still works
        val asset = r.apkAssetForAbi("arm64-v8a")
        assertTrue(asset != null && asset.name.endsWith("arm64-v8a.apk"))
    }

    @Test
    fun apkAssetForAbiReturnsNullWhenNoApk() {
        val r = LatestRelease.parse(
            releaseJson(
                assets = """[{"name":"checksums.txt","browser_download_url":"https://example.com/s.txt","size":10}]"""
            )
        )
        assertNull(r.apkAssetForAbi("arm64-v8a"))
    }

    @Test
    fun comparesSemverishVersions() {
        assertTrue(LatestRelease.isNewerVersion("1.3.8", "1.3.7"))
        assertTrue(LatestRelease.isNewerVersion("1.4.0", "1.3.7"))
        assertTrue(LatestRelease.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(LatestRelease.isNewerVersion("1.3.7.1", "1.3.7"))
        assertFalse(LatestRelease.isNewerVersion("1.3.7", "1.3.7"))
        assertFalse(LatestRelease.isNewerVersion("1.3.6", "1.3.7"))
        assertFalse(LatestRelease.isNewerVersion("abc", "1.3.7"))
        assertFalse(LatestRelease.isNewerVersion("", "1.3.7"))
    }

    @Test
    fun normalizeStripsVPrefix() {
        assertEquals("1.3.7", LatestRelease.normalizeVersion("v1.3.7"))
        assertEquals("1.3.7", LatestRelease.normalizeVersion("V1.3.7"))
        assertEquals("1.3.7", LatestRelease.normalizeVersion(" 1.3.7 "))
    }

    @Test
    fun isNewerThanUsesNormalizedTags() {
        val r = LatestRelease.parse(releaseJson(tag = "v1.3.8"))
        assertTrue(r.isNewerThan("1.3.7"))
        assertFalse(r.isNewerThan("1.3.8"))
        assertFalse(r.isNewerThan("1.4.0"))
    }
}
