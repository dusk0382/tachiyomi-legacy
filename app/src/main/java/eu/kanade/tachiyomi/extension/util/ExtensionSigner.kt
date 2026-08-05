package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.util.Base64
import net.spin.tachiyomi.legacy.R
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Re-signs extension APKs so they are readable on Android 6 (API 23).
 *
 * Keiyoushi (and other modern repos) sign their extensions using only the v2
 * signature scheme, which Android 6 cannot read at all. Re-signing with a v1
 * (JAR) signature using our own embedded key restores compatibility, mimicking
 * what Tachiyomi classic did.
 *
 * The signing identity is embedded as PEM raw resources (PKCS#8 private key +
 * X.509 certificate) instead of a KeyStore file: Android 6 ships no JKS provider
 * (`KeyStore.getInstance("JKS")` throws NoSuchAlgorithmException) and the bundled
 * BouncyCastle cannot reliably read modern PKCS12 files either. Parsing the PEM
 * directly with KeyFactory/CertificateFactory works on every API level.
 *
 * The v1 signature itself is produced by [V1JarSigner], a hand-rolled PKCS#7
 * SignedData generator that uses only plain JDK APIs (MessageDigest, Signature,
 * CertificateFactory). Every signing library fails on API 23:
 *  - apksig 3.x+ calls Class.getDeclaredAnnotation (added in API 26)
 *  - apksig 2.x relies on sun.security.* classes absent from the device runtime
 * V1JarSigner's output was validated against the real Android 6 PackageParser
 * (`adb install` accepted the signature; see the project history).
 */
object ExtensionSigner {

    @Volatile
    private var cachedIdentity: Pair<PrivateKey, List<X509Certificate>>? = null

    @Synchronized
    private fun loadIdentity(context: Context): Pair<PrivateKey, List<X509Certificate>> {
        cachedIdentity?.let { return it }

        val keyPem = context.resources.openRawResource(R.raw.extension_signing_key)
            .bufferedReader(Charsets.US_ASCII).use { it.readText() }
        val certPem = context.resources.openRawResource(R.raw.extension_signing_cert)
            .bufferedReader(Charsets.US_ASCII).use { it.readText() }

        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(decodeBase64(keyPem)))

        @Suppress("UNCHECKED_CAST")
        val certs = CertificateFactory.getInstance("X.509")
            .generateCertificates(certPem.byteInputStream(Charsets.US_ASCII))
            .map { it as X509Certificate }

        return (privateKey to certs).also { cachedIdentity = it }
    }

    private fun decodeBase64(pem: String): ByteArray {
        val body = pem.lines()
            .filterNot { it.trimStart().startsWith("-----") || it.isBlank() }
            .joinToString("")
        return Base64.decode(body, Base64.DEFAULT)
    }

    /**
     * Re-signs [input] APK with the embedded key using the v1 (JAR) signature
     * scheme — the only scheme Android 6 can read — writing the result to [output].
     */
    fun sign(context: Context, input: File, output: File) {
        val (privateKey, certChain) = loadIdentity(context)
        V1JarSigner.sign(input, output, privateKey, certChain)
    }
}
