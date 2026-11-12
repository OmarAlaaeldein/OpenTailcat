package com.tailcat.vpn.core.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * The only way app code opens an HTTP(S) connection. Connections are never
 * reused.
 *
 * Why: before installing default routes, the VPN service protects every open
 * socket in the process ([com.tailcat.vpn.service.TransportSocketProtect]),
 * because Magicsock/DERP transport sockets opened during prepare cannot be
 * told apart from other sockets. A pooled keep-alive socket from an earlier
 * app request (public-IP card, speed test, updater) would be protected too,
 * and reusing it while CONNECTED would send that request outside the tunnel.
 * With no pool, an app socket lives only as long as its own request, so after
 * the sweep every new app request opens a new, unprotected socket that
 * follows the VPN routes.
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
