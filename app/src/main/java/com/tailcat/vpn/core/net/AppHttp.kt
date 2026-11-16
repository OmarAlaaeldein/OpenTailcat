package com.tailcat.vpn.core.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * The only way app code opens an HTTP(S) connection. Connections are never
 * reused.
 *
 * Transport protection is restricted to native transport sockets at creation.
 * App HTTP sockets are never enumerated or exempted from VPN routing. Keep
 * this conservative no-reuse policy across connect/reconnect transitions;
 * it is no longer a prerequisite for a process-wide protection sweep.
 *
 * `AppHttpSourceTest` fails the build if anything else calls `openConnection`.
 */
object AppHttp {

    init {
        // Read by Android's HttpURLConnection pool (ConfigAwareConnectionPool)
        // when it is first used; set it before any connection is opened.
        System.setProperty("http.keepAlive", "false")
    }

    /** Applies the no-reuse policy now. Call early in Application.onCreate. */
    fun disableConnectionReuse() {
        System.setProperty("http.keepAlive", "false")
    }

    fun open(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        // Per-request too, in case the pool was created before the property:
        // a request sent with "Connection: close" is never returned to it.
        connection.setRequestProperty("Connection", "close")
        connection.useCaches = false
        return connection
    }

    fun open(url: String): HttpURLConnection = open(URL(url))
}
