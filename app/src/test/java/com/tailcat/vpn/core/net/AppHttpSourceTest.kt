package com.tailcat.vpn.core.net

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the invariant behind [AppHttp]: the VPN service protects every open
 * process socket before installing default routes, so app HTTP sockets must
 * never be pooled. All app code must open connections through [AppHttp].
 */
class AppHttpSourceTest {

    @Test
    fun openSetsConnectionClose() {
        // openConnection does not connect, so no network is needed.
        val connection = AppHttp.open("https://example.invalid/")
        assertEquals("close", connection.getRequestProperty("Connection"))
        assertEquals(false, connection.useCaches)
        assertEquals("false", System.getProperty("http.keepAlive"))
    }

    @Test
    fun onlyAppHttpOpensConnections() {
        val roots = listOf("src/main/java", "src/github/java", "src/fdroid/java")
            .map(::File)
            .filter { it.isDirectory }
        assertTrue("no source roots found from ${File(".").absolutePath}", roots.isNotEmpty())
        val forbidden = Regex("""\.openConnection\s*\(|\bOkHttpClient\b|\bjava\.net\.Socket\s*\(|\bSocket\s*\(""")
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { it.name != "AppHttp.kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { i, line ->
                        val code = line.substringBefore("//")
                        if (forbidden.containsMatchIn(code)) "${file.path}:${i + 1}: ${line.trim()}" else null
                    }
                }
                .toList()
        }
        assertTrue(
            "Open HTTP connections through AppHttp so sockets are never pooled:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
