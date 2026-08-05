package eu.kanade.tachiyomi.extension.util

import android.content.Context
import com.android.apksig.ApkSigner
import net.spin.tachiyomi.legacy.R
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Re-signs extension APKs so they are readable on old Android versions.
 *
 * Keiyoushi (and other modern repos) sign their extensions using the v2 signature
 * scheme only. On Android 6 (API 23) the package manager cannot read a v2-only
 * signature, which makes [android.content.pm.PackageManager.getPackageArchiveInfo]
 * return null. Re-signing with v1+v2 (JAR + APK signature block) using our own
 * embedded keystore restores compatibility, mimicking what Tachiyomi classic did.
 */
object ExtensionSigner {

    private const val STORE_TYPE = "JKS"
    private const val STORE_PASSWORD = "tachiyomi_legacy"
    private const val KEY_ALIAS = "extension"
    private const val KEY_PASSWORD = "tachiyomi_legacy"

    @Volatile
    private var cachedPrivateKey: java.security.PrivateKey? = null

    @Volatile
    private var cachedCertChain: Array<X509Certificate>? = null

    @Synchronized
    private fun getKeys(context: Context): Pair<java.security.PrivateKey, Array<X509Certificate>> {
        cachedPrivateKey?.let { pk ->
            cachedCertChain?.let { cc -> return pk to cc }
        }

        val store = KeyStore.getInstance(STORE_TYPE)
        context.resources.openRawResource(R.raw.extension_signing).use { input ->
            store.load(input, STORE_PASSWORD.toCharArray())
        }
        val privateKey = store.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as java.security.PrivateKey
        @Suppress("UNCHECKED_CAST")
        val chain = store.getCertificateChain(KEY_ALIAS).mapNotNull { it as? X509Certificate }.toTypedArray()

        cachedPrivateKey = privateKey
        cachedCertChain = chain
        return privateKey to chain
    }

    /**
     * Re-signs [input] APK with the embedded key using version 1 (JAR) and version 2
     * signature schemes, writing the result to [output].
     */
    fun sign(context: Context, input: File, output: File) {
        val (privateKey, certChain) = getKeys(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "extension",
            privateKey,
            certChain.toList(),
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign()
    }
}