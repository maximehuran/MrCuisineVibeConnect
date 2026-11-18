package app.toque.update

import android.content.Context
import app.toque.core.update.UpdateTrust
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/** Update requests only. Never changes the system trust store or disables hostname verification. */
class UpdateHttps(context: Context) {
    private val factory by lazy {
        val cf = CertificateFactory.getInstance("X.509")
        UpdateTrust(listOf("isrg-root-x1.pem", "sectigo-root-e46.pem").map { file ->
            context.assets.open("update-cas/$file").use { cf.generateCertificate(it) as X509Certificate }
        }).socketFactory()
    }

    fun open(address: String, accept: String, version: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            if (url.protocol != "https" || url.userInfo != null) throw IOException("HTTPS required")
            val c = url.openConnection() as HttpsURLConnection
            c.sslSocketFactory = factory
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Toque/$version (Android)")
            c.setRequestProperty("Accept", accept)
            try {
                if (c.responseCode !in listOf(301, 302, 303, 307, 308)) return c
                val location = c.getHeaderField("Location") ?: throw IOException("Missing HTTPS redirect")
                url = URL(url, location)
            } catch (e: Exception) { c.disconnect(); throw e }
            c.disconnect()
        }
        throw IOException("Too many HTTPS redirects")
    }
}
