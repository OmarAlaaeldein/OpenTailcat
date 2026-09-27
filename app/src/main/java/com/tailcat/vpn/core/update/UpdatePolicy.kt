package com.tailcat.vpn.core.update

import java.net.URI

/**
 * Pure checks the in-app updater applies before it downloads or offers to
 * install a release APK. Kept free of Android types so they run as JVM tests.
 */
object UpdatePolicy {

    /** Upper bound for a release APK; current APKs are about 21–23 MB. */
    const val MAX_APK_BYTES: Long = 100L * 1024 * 1024

    const val RELEASES_PAGE = "https://github.com/OmarAlaaeldein/OpenTailcat/releases"

    /**
     * SHA-256 digests of the signing certificates allowed to publish updates.
     *
     * Releases up to 1.4.0 are signed with the developer's Android debug
     * certificate (listed below). Before rotating to an offline release key,
     * ship one build that adds the release certificate digest here, then sign
     * the next release with a v3 lineage (debug -> release). Remove the debug
     * digest only after every supported install has rotated.
     */
    val TRUSTED_SIGNER_SHA256: Set<String> = setOf(
        "dd767011a3d1223c6145cec024dccb02730e73607fc39544b2fac93adeafa8d6"
    )

    private val DOWNLOAD_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val APK_NAME = Regex("^[A-Za-z0-9._-]{1,128}\\.apk$")

    /** Returns the lowercase hex digest from GitHub's `sha256:<hex>` form, or null. */
    fun parseSha256(raw: String?): String? {
        val hex = raw?.trim()?.removePrefix("sha256:")?.lowercase() ?: return null
        return hex.takeIf { SHA256_HEX.matches(it) }
    }

    /** HTTPS on a GitHub release-asset host only. */
    fun isAllowedDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.host?.lowercase() in DOWNLOAD_HOSTS
    }

    /** A plain file name, so it cannot escape the updates cache directory. */
    fun isSafeApkName(name: String): Boolean = APK_NAME.matches(name) && !name.startsWith(".")

    /** Returns null when [asset] may be downloaded, otherwise the reason to refuse. */
    fun assetRejection(asset: ReleaseAsset): String? = when {
        !isSafeApkName(asset.name) -> "Release asset has an unexpected name"
        asset.size <= 0 || asset.size > MAX_APK_BYTES -> "Release asset has an unexpected size"
        asset.sha256 == null -> "Release has no SHA-256 digest; refusing to download"
        !isAllowedDownloadUrl(asset.browserDownloadUrl) -> "Release asset is not hosted on GitHub"
        else -> null
    }

    /** Release pages opened in the browser must stay on this repository. */
    fun safeReleasePage(url: String): String =
        if (url.startsWith("$RELEASES_PAGE/")) url else "$RELEASES_PAGE/latest"

    /**
     * Decides whether a downloaded APK can update the installed app.
     *
     * [archiveHistory] is the archive's v3 signing lineage (oldest first,
     * current signer included) or null when the archive has several signers
     * or the platform cannot report a lineage. Mirrors the platform rule
     * (same signer, or a lineage that contains the installed signer) and
     * additionally requires the archive's signer to be pinned in [trusted].
     *
     * Returns null when the update is acceptable, otherwise the reason to refuse.
     */
    fun signerRejection(
        installedCurrent: Set<String>,
        archiveCurrent: Set<String>,
        archiveHistory: List<String>?,
        trusted: Set<String> = TRUSTED_SIGNER_SHA256
    ): String? {
        if (archiveCurrent.isEmpty()) return "Downloaded APK has no readable signature"
        if (installedCurrent.isEmpty()) return "Could not read this app's signing certificate"
        if (!trusted.containsAll(archiveCurrent)) {
            return "APK is not signed with a trusted OpenTailcat release key"
        }
        if (archiveCurrent == installedCurrent) return null
        val rotatesFromInstalled = installedCurrent.size == 1 &&
            archiveCurrent.size == 1 &&
            archiveHistory != null &&
            installedCurrent.single() in archiveHistory
        return if (rotatesFromInstalled) {
            null
        } else {
            "APK is not signed with this app's key (install would fail)"
        }
    }
}
