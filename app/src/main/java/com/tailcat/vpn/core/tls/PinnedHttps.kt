package com.tailcat.vpn.core.tls

import android.util.Base64
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object PinnedHttps {
    private val pins = setOf(
        "ltQ6aXy3tqpNZKJdnevMD7oR+IsI5rNWbOssFDrl+Ew=",
        "+b007mFjejRgBPvNGi8dBoql9OZGiCe4woYnC0Lt61I="
    )

    private val socketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val pinned = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                system.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                system.checkServerTrusted(chain, authType)
                val matched = chain.any { cert ->
                    val spki = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
                    Base64.encodeToString(spki, Base64.NO_WRAP) in pins
                }
                check(matched) { "TLS pin mismatch" }
            }
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(pinned), null)
        ctx.socketFactory
    }

    fun open(endpoint: String): HttpsURLConnection {
        val connection = URL(endpoint).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = socketFactory
        return connection
    }
}