package com.tailcat.vpn.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object ApkInstaller {

    fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS.toList()
        return abis.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: "arm64-v8a"
    }

    fun canRequestInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Compares the downloaded APK signing cert digests with the installed
     * package. Returns null when signatures match (or cannot be compared);
     * returns an error message when they clearly differ.
     */
    fun signatureMismatchReason(context: Context, apkFile: File): String? {
        return runCatching {
            val archive = context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return "Downloaded file is not a valid APK"
            val installed = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val archiveDigests = signerDigests(archive)
            val installedDigests = signerDigests(installed)
            if (archiveDigests.isEmpty() || installedDigests.isEmpty()) return null
            if (archiveDigests.intersect(installedDigests).isEmpty()) {
                "APK is not signed with this app’s key (install would fail)"
            } else {
                null
            }
        }.getOrNull()
    }

    fun installIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signers.mapNotNull { sig ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
            }.getOrNull()
        }.toSet()
    }
}
