package eu.kanade.tachiyomi.extension

import android.content.Context
import android.util.Log
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.extension.util.ExtensionSigner
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages installed, available and untrusted extensions. Extensions are private
 * (stored in `filesDir/exts`), so no PackageInstaller is involved: installing an
 * available extension means downloading its APK and copying it into the private
 * extension dir.
 */
class ExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences,
    private val trustExtension: TrustExtension,
    private val loader: ExtensionLoader,
    private val api: ExtensionApi,
) {

    private val defaultRepoBaseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json"

    private companion object {
        const val TAG = "ExtensionInstall"
    }

    /** Repo base URLs currently configured. The canonical repo is always included. */
    var repoBaseUrls: List<String>
        get() {
            val custom = preferences.extensionRepos.get().filter { it.isNotBlank() }
            return listOf(defaultRepoBaseUrl) + custom
        }
        set(value) {
            preferences.extensionRepos.set(value.filterNot { it == defaultRepoBaseUrl }.toSet())
        }

    var installedExtensions: List<Extension.Installed>
        private set

    var untrustedExtensions: List<Extension.Untrusted>
        private set

    var availableExtensions: List<Extension.Available>
        private set

    /** Last repo fetch error(s), shown when no extensions are available. */
    @Volatile
    var repoFetchError: String? = null
        private set

    init {
        installedExtensions = emptyList()
        untrustedExtensions = emptyList()
        availableExtensions = emptyList()
        reloadInstalled()
    }

    /** Loads (and re-loads) the installed private extensions from disk. */
    fun reloadInstalled() {
        val results = loader.loadExtensions(context)
        installedExtensions = results.filterIsInstance<LoadResult.Success>().map { it.extension }
        untrustedExtensions = results.filterIsInstance<LoadResult.Untrusted>().map { it.extension }
    }

    /** Returns the extension that owns the given source id, if any. */
    fun getExtensionPackage(sourceId: Long): String? {
        return installedExtensions.find { ext -> ext.sources.any { it.id == sourceId } }?.pkgName
    }

    /** Refreshes the list of available extensions from all configured repos. */
    suspend fun findAvailableExtensions(): List<Extension.Available> {
        val result = api.findExtensions(repoBaseUrls)
        availableExtensions = result.extensions
        repoFetchError = if (result.extensions.isEmpty() && result.errors.isNotEmpty()) {
            result.errors.joinToString("\n\n")
        } else {
            null
        }
        return result.extensions
    }

    /** Downloads and privately installs the given available extension. */
    suspend fun installExtension(extension: Extension.Available): Boolean {
        return try {
            val file = downloadApk(extension.apkUrl, extension.pkgName)
            val signed = File(context.cacheDir, "${extension.pkgName}.signed.apk")
            ExtensionSigner.sign(context, file, signed)
            loader.installPrivateExtensionFile(context, signed)
        } catch (e: Throwable) {
            // Throwable (no solo Exception): errores como NoSuchMethodError por APIs
            // ausentes en Android 6 no deben tumbar la app nunca.
            Log.e(TAG, "Fallo instalando ${extension.pkgName}: ${e.message}", e)
            false
        }
    }

    /** Marks an untrusted extension as trusted and loads it. */
    fun trust(extension: Extension.Untrusted) {
        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)
        untrustedExtensions = untrustedExtensions.filterNot { it.pkgName == extension.pkgName }
        reloadInstalled()
    }

    /** Removes a private extension. */
    fun uninstallExtension(extension: Extension) {
        loader.uninstallPrivateExtension(context, extension.pkgName)
        reloadInstalled()
    }

    private suspend fun downloadApk(url: String, pkgName: String): File {
        val target = File(context.cacheDir, "$pkgName.apk")
        val response = withContext(Dispatchers.IO) {
            api.networkClient().newCall(GET(url)).execute()
        }
        response.use {
            if (!it.isSuccessful) {
                Log.e(TAG, "Descarga fallida HTTP ${it.code}: $url")
                throw RuntimeException("HTTP ${it.code}")
            }
            target.outputStream().use { out -> it.body.byteStream().copyTo(out) }
        }
        Log.i(TAG, "APK descargado (${target.length()} bytes): $url")
        return target
    }
}