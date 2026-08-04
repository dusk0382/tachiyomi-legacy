package eu.kanade.tachiyomi.network

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Merges the platform system trust store with certificates bundled in the app assets.
 *
 * Android 6/7 do not ship recent root CAs (e.g. Let's Encrypt ISRG Root X1), so requests
 * to many modern HTTPS sites fail with "Trust anchor for certification path not found".
 * Bundling current roots and trusting them alongside the system ones fixes that without
 * requiring a user-installed CA.
 */
object BundledTrustManager {

    private const val ASSETS_DIR = "certs"

    /** Trust manager that trusts system CAs plus the bundled ones. */
    fun systemPlusBundled(context: Context): X509TrustManager {
        val system = systemTrustManager()

        return object : X509TrustManager {
            private val bundled = bundledTrustManager(context)

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                system.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    // If the system store rejected the chain, try the bundled roots.
                    bundled.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                val systemIssuers = system.acceptedIssuers
                val bundledIssuers = bundled.acceptedIssuers
                return (systemIssuers + bundledIssuers).distinct().toTypedArray()
            }
        }
    }

    /** Builds an SSLSocketFactory backed by the merged trust manager. */
    fun mergedSslSocketFactory(context: Context): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManager = systemPlusBundled(context)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        return sslContext.socketFactory to trustManager
    }

    private fun systemTrustManager(): X509TrustManager {
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        )
        trustManagerFactory.init(null as KeyStore?)
        return trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    private fun bundledTrustManager(context: Context): X509TrustManager {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)

        var index = 0
        context.assets.list(ASSETS_DIR).orEmpty()
            .filter { it.endsWith(".pem", ignoreCase = true) }
            .forEach { fileName ->
                context.assets.open("$ASSETS_DIR/$fileName").use { input ->
                    val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
                    input.bufferedReader().use { reader ->
                        val pem = StringBuilder()
                        var inBlock = false
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val current = line!!
                            if (current == "-----BEGIN CERTIFICATE-----") {
                                pem.setLength(0)
                                inBlock = true
                            }
                            if (inBlock) {
                                pem.append(current).append('\n')
                            }
                            if (current == "-----END CERTIFICATE-----") {
                                inBlock = false
                                val decoded = pem.toString().toByteArray()
                                val certificate = certFactory.generateCertificate(decoded.inputStream())
                                keyStore.setCertificateEntry("bundled${index++}", certificate)
                            }
                        }
                    }
                }
            }

        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        )
        trustManagerFactory.init(keyStore)
        return trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }
}
