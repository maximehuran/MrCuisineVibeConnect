package app.toque.core.update

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** System trust plus explicitly shipped public CAs. Certificate dates and hostname verification remain enforced. */
class UpdateTrust(extraRoots: Collection<X509Certificate>,
                  private val system: X509TrustManager = manager(null)) : X509TrustManager {
    private val extra = manager(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        extraRoots.forEachIndexed { i, cert ->
            require(cert.basicConstraints >= 0 && cert.subjectX500Principal == cert.issuerX500Principal)
            cert.verify(cert.publicKey)
            setCertificateEntry("update-ca-$i", cert)
        }
    })

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
        chain.forEach { it.checkValidity() }
        try { system.checkServerTrusted(chain, authType) }
        catch (untrusted: CertificateException) {
            try { extra.checkServerTrusted(chain, authType) }
            catch (e: CertificateException) { e.addSuppressed(untrusted); throw e }
        }
    }

    fun socketFactory() = SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf(this@UpdateTrust), null) }.socketFactory

    companion object {
        private fun manager(store: KeyStore?): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
