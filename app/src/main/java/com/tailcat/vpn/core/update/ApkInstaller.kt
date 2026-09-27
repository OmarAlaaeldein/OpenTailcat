package com.tailcat.vpn.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
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
     * Checks the downloaded APK's signer against the installed app and the
     * pinned release keys ([UpdatePolicy.signerRejection]). Accepts a v3 key
     * rotation whose lineage contains the installed signer. Returns null when
     * the update is acceptable; otherwise the reason to refuse, including when
     * signatures cannot be read.
     */
    fun signatureMismatchReason(context: Context, apkFile: File): String? {
        return runCatching {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return "Downloaded file is not a valid APK"
            if (archive.packageName != context.packageName) {
                return "Downloaded APK is a different app"
            }
            val installed = pm.getPackageInfo(context.packageName, flags)
            UpdatePolicy.signerRejection(
                installedCurrent = currentSignerDigests(installed),
                archiveCurrent = currentSignerDigests(archive),
                archiveHistory = signerHistoryDigests(archive)
            )
        }.getOrElse { e -> "Could not verify the APK signature (${e.message})" }
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
    private fun currentSignerDigests(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            info.signatures ?: emptyArray()
        }
        return signers.map { sha256(it) }.toSet()
    }

    /** v3 lineage including the current signer; null with several signers or before API 28. */
    private fun signerHistoryDigests(info: PackageInfo): List<String>? {
        if (Build.VERSION.SDK_INT < 28) return null
        val signingInfo = info.signingInfo ?: return null
        if (signingInfo.hasMultipleSigners()) return null
        return signingInfo.signingCertificateHistory?.map { sha256(it) }
    }

    private fun sha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { b -> "%02x".format(b) }
}
