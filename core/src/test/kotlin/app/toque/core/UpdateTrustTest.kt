package app.toque.core

import app.toque.core.update.UpdateTrust
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class UpdateTrustTest {
    private fun cert(path: String) = File(path).inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private val rejectSystem = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Not in old Android store") }
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException() }
    }

    @Test fun shippedPublicRootIsAcceptedButAnUnlistedRootIsRejected() {
        val isrg = cert("../app/src/main/assets/update-cas/isrg-root-x1.pem")
        val sectigo = cert("../app/src/main/assets/update-cas/sectigo-root-e46.pem")
        val trust = UpdateTrust(listOf(isrg), rejectSystem)
        trust.checkServerTrusted(arrayOf(isrg), "RSA")
        try { trust.checkServerTrusted(arrayOf(sectigo), "EC"); fail("Unknown root accepted") } catch (_: CertificateException) {}
        try { trust.checkServerTrusted(emptyArray(), "RSA"); fail("Empty chain accepted") } catch (_: CertificateException) {}
        assertNotNull(trust.socketFactory())
    }

    @Test fun expiredCertificatesAreRejectedEvenIfSystemClaimsToTrustThem() {
        val expired = cert("src/test/resources/fixtures/expired-dst-root-x3.pem")
        val trustingSystem = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        val trust = UpdateTrust(emptyList(), trustingSystem)
        try { trust.checkServerTrusted(arrayOf(expired), "RSA"); fail("Expired certificate accepted") }
        catch (_: CertificateExpiredException) {}
    }
}
