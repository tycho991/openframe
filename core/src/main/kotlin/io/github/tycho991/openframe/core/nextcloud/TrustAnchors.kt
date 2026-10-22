package io.github.tycho991.openframe.core.nextcloud

import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust for `https` connections on old devices. Android 5 and 6 do not know the root certificate of Let's
 * Encrypt, so the app delivers the roots that its servers need (the PEM files under `resources/trust`) and accepts a chain that
 * either the platform or these roots vouch for. The check of the host name stays the platform's.
 */
object TrustAnchors {
    private val FILES = listOf("isrg-root-x1.pem", "sectigo-root-e46.pem", "usertrust-ecc.pem")

    /** The root certificates delivered with the app. */
    fun delivered(): List<X509Certificate> =
        FILES.map { name ->
            val stream = TrustAnchors::class.java.getResourceAsStream("/trust/$name") ?: error("Trust anchor $name is missing")
            stream.use(::parse)
        }

    /** The first certificate of a PEM stream. */
    fun parse(input: InputStream): X509Certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate

    /** A trust manager that accepts what the platform accepts and, in addition, chains under [extra]. */
    fun trustManager(extra: List<X509Certificate> = delivered()): X509TrustManager = Combined(platform(), fromCertificates(extra))

    /** Socket factory that uses [trustManager]. */
    fun socketFactory(manager: X509TrustManager = trustManager()): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(manager), null)
        return context.socketFactory
    }

    private fun platform(): X509TrustManager = managerFor(null)

    private fun fromCertificates(certificates: List<X509Certificate>): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        certificates.forEachIndexed { i, cert -> store.setCertificateEntry("anchor-$i", cert) }
        return managerFor(store)
    }

    private fun managerFor(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class Combined(
        private val platform: X509TrustManager,
        private val delivered: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = platform.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            try {
                platform.checkServerTrusted(chain, authType)
            } catch (platformFailure: CertificateException) {
                try {
                    delivered.checkServerTrusted(chain, authType)
                } catch (deliveredFailure: CertificateException) {
                    throw platformFailure
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers + delivered.acceptedIssuers
    }
}
