package com.tailcat.vpn.core.update

import org.json.JSONArray
import org.json.JSONObject

data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long
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
                            size = a.optLong("size")
                        )
                    )
                }
            }
            return LatestRelease(
                tagName = root.optString("tag_name"),
                name = root.optString("name"),
                notes = root.optString("body"),
                htmlUrl = root.optString("html_url"),
                assets = assets
            )
        }

        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V")

        /** True when [candidate] is a strictly newer semver-ish version than [current]. */
        fun isNewerVersion(candidate: String, current: String): Boolean {
            val a = parseVersionParts(candidate)
            val b = parseVersionParts(current)
            if (a.isEmpty() || b.isEmpty()) return false
            val len = maxOf(a.size, b.size)
            for (i in 0 until len) {
                val av = a.getOrElse(i) { 0 }
                val bv = b.getOrElse(i) { 0 }
                if (av != bv) return av > bv
            }
            return false
        }

        private fun parseVersionParts(version: String): List<Int> {
            val parts = normalizeVersion(version)
                .split('.', '-', '+')
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
                .take(8)
            val lastNonZero = parts.indexOfLast { it != 0 }
            return if (lastNonZero < 0) emptyList() else parts.subList(0, lastNonZero + 1)
        }
    }
}
