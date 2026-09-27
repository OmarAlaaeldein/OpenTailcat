package com.tailcat.vpn.core.tls

import android.net.http.X509TrustManagerExtensions
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS to Cloudflare endpoints with [SpkiPins]. The pin is checked against
 * the chain the platform verified, not the certificates the server sent: a
 * server with any trusted certificate could otherwise append the public
 * pinned certificate and pass.
 */
object PinnedHttps {

    private val factories = ConcurrentHashMap<String, SSLSocketFactory>()

    private fun socketFactoryFor(host: String): SSLSocketFactory =
        factories.getOrPut(host) {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val extensions = X509TrustManagerExtensions(system)
            val pinned = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    system.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val verified = extensions.checkServerTrusted(chain, authType, host)
                    if (!SpkiPins.matches(verified.map { it.publicKey.encoded })) {
                        throw CertificateException("TLS pin mismatch for $host")
                    }
                }
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(pinned), null)
            ctx.socketFactory
        }

    fun open(endpoint: String): HttpsURLConnection {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpsURLConnection
        connection.sslSocketFactory = socketFactoryFor(url.host)
        return connection
    }
}
