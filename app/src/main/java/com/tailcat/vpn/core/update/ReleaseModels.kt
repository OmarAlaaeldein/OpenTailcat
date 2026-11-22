package com.tailcat.vpn.core.update

import org.json.JSONArray
import org.json.JSONObject

data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long,
    /** Lowercase hex SHA-256 from GitHub's asset `digest` field, when present. */
    val sha256: String? = null
)

data class LatestRelease(
    val tagName: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>
) {
    fun normalizedVersion(): String = normalizeVersion(tagName)

    fun isNewerThan(currentVersion: String): Boolean =
        isNewerVersion(normalizedVersion(), normalizeVersion(currentVersion))

    fun apkAssetForAbi(abi: String): ReleaseAsset? {
        val version = normalizedVersion()
        val preferred = "OpenTailcat-$version-$abi.apk"
        return assets.firstOrNull { it.name == preferred }
            ?: assets.firstOrNull { it.name.endsWith("-$abi.apk") }
            ?: assets.firstOrNull { it.name.endsWith(".apk") && abi in it.name }
    }

    companion object {
        fun parse(json: String): LatestRelease {
            val root = JSONObject(json)
            val assetsJson = root.optJSONArray("assets") ?: JSONArray()
            val assets = buildList {
                for (i in 0 until assetsJson.length()) {
                    val a = assetsJson.getJSONObject(i)
                    add(
                        ReleaseAsset(
                            name = a.optString("name"),
                            browserDownloadUrl = a.optString("browser_download_url"),
                            size = a.optLong("size"),
                            sha256 = UpdatePolicy.parseSha256(a.optStringOrNull("digest"))
                        )
                    )
                }
            }
            return LatestRelease(
                tagName = root.optString("tag_name"),
                name = root.optStringOrNull("name").orEmpty(),
                notes = root.optStringOrNull("body").orEmpty(),
                htmlUrl = root.optString("html_url"),
                assets = assets
            )
        }

        /** `optString` renders JSON null as the string "null". */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key)

        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V")

        /** True when [candidate] is a strictly newer SemVer 2.0.0 version than [current]. */
        fun isNewerVersion(candidate: String, current: String): Boolean {
            val a = parseSemVer(candidate) ?: return false
            val b = parseSemVer(current) ?: return false
            val coreLen = maxOf(a.core.size, b.core.size)
            for (i in 0 until coreLen) {
                val av = a.core.getOrElse(i) { 0 }
                val bv = b.core.getOrElse(i) { 0 }
                if (av != bv) return av > bv
            }
            // Pre-release has lower precedence than the normal version (1.7.3-rc.1 < 1.7.3).
            if (a.preRelease.isEmpty() && b.preRelease.isEmpty()) return false
            if (a.preRelease.isEmpty()) return true
            if (b.preRelease.isEmpty()) return false
            val preLen = minOf(a.preRelease.size, b.preRelease.size)
            for (i in 0 until preLen) {
                val ap = a.preRelease[i]
                val bp = b.preRelease[i]
                if (ap == bp) continue
                val an = ap.toIntOrNull()
                val bn = bp.toIntOrNull()
                return when {
                    an != null && bn != null -> an > bn
                    an != null -> false // numeric identifiers always have lower precedence than non-numeric
                    bn != null -> true
                    else -> ap > bp
                }
            }
            return a.preRelease.size > b.preRelease.size
        }

        private data class SemVer(val core: List<Int>, val preRelease: List<String>)

        private fun parseSemVer(raw: String): SemVer? {
            val noBuild = normalizeVersion(raw).substringBefore('+')
            if (noBuild.isEmpty()) return null
            val dash = noBuild.indexOf('-')
            val coreStr = if (dash >= 0) noBuild.substring(0, dash) else noBuild
            val preStr = if (dash >= 0) noBuild.substring(dash + 1) else ""
            if (coreStr.isEmpty()) return null
            val coreParts = coreStr.split('.')
            if (coreParts.size > 8) return null
            val core = coreParts.map { part ->
                if (part.isEmpty() || !part.all(Char::isDigit)) return null
                part.toIntOrNull() ?: return null
            }
            if (core.all { it == 0 }) return null
            val pre = if (dash >= 0) {
                if (preStr.isEmpty()) return null
                val parts = preStr.split('.')
                if (parts.any { it.isEmpty() }) return null
                parts
            } else {
                emptyList()
            }
            return SemVer(core, pre)
        }
    }
}
