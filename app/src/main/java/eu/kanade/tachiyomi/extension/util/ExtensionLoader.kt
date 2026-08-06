package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Loads private extensions stored in the app's private data directory (`filesDir/exts`).
 *
 * Extensions are plain APKs that we load directly with a [ChildFirstPathClassLoader] and
 * never install through the system PackageInstaller, so they can only be used by this app.
 */
class ExtensionLoader(
    private val preferences: SourcePreferences,
    private val trustExtension: TrustExtension,
) {

    private val loadNsfwSource by lazy {
        preferences.showNsfwSource.get()
    }

    companion object {
        private const val TAG = "ExtensionInstall"
        private const val EXTENSION_FEATURE = "tachiyomi.extension"
        private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
        private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
        private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
        private const val METADATA_NAME = "tachiyomix.name"
        private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
        private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

        private val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

        @Suppress("DEPRECATION")
        private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

        private const val PRIVATE_EXTENSION_EXTENSION = "ext"
    }

    private fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "exts")

    fun installPrivateExtensionFile(context: Context, file: File): Boolean {
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
        val extension = packageInfo
            ?.takeIf { isPackageAnExtension(it) }
            ?: run {
                Log.e(TAG, "getPackageArchiveInfo no pudo parsear el APK firmado: ${file.absolutePath} (size=${file.length()}, exists=${file.exists()})")
                if (packageInfo == null) {
                    logRealParseError(file)
                } else {
                    Log.e(TAG, "Sí parseó pero no es extensión: reqFeatures=${packageInfo.reqFeatures?.map { it.name }}")
                }
                return false
            }
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName)

        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                Log.w(TAG, "Version nueva menor que la instalada, se omite")
                return false
            }

            val extensionSignatures = getSignatures(extension)
            if (extensionSignatures.isNullOrEmpty()) {
                Log.e(TAG, "El APK firmado no expone firmas")
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                Log.e(TAG, "Firmas distintas a la version instalada, se omite")
                return false
            }
        }

        val target = File(getPrivateExtensionDir(context), "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION")
        return try {
            target.delete()
            file.copyAndSetReadOnlyTo(target, overwrite = true)
            true
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION").delete()
    }

    /**
     * Return a list of all the available extensions initialized concurrently.
     *
     * @param context The application context.
     */
    fun loadExtensions(context: Context): List<LoadResult> {
        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {
                if (it.canWrite()) {
                    it.setReadOnly()
                }

                val path = it.absolutePath
                context.packageManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.apply { applicationInfo!!.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(it) }
            ?.toList()
            ?: emptyList()

        if (privateExtPkgs.isEmpty()) return emptyList()

        // Load each extension concurrently and wait for completion
        return runBlocking {
            privateExtPkgs.map {
                async { loadExtension(context, it) }
            }.awaitAll()
        }
    }

    /**
     * Attempts to load an extension from the given package name.
     */
    suspend fun loadExtensionFromPkgName(context: Context, pkgName: String): LoadResult {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        if (!privateExtensionFile.isFile) {
            return LoadResult.Error
        }
        val pkgInfo = context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
            ?.takeIf { isPackageAnExtension(it) }
            ?: return LoadResult.Error
        pkgInfo.applicationInfo!!.fixBasePaths(privateExtensionFile.absolutePath)
        return loadExtension(context, pkgInfo)
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        if (!privateExtensionFile.isFile) return null
        return context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
            ?.takeIf { isPackageAnExtension(it) }
            ?.also { it.applicationInfo!!.fixBasePaths(privateExtensionFile.absolutePath) }
    }

    /**
     * Android 6 getPackageArchiveInfo devuelve null para cualquier fallo de parseo
     * sin exponer el motivo. Este método repite el parseo con el PackageParser
     * oculto vía reflexión para obtener la excepción real (solo diagnóstico).
     */
    private fun logRealParseError(file: File) {
        try {
            val parserClass = Class.forName("android.content.pm.PackageParser")
            val parser = parserClass.getConstructor().newInstance()
            val parseMethod = parserClass.getMethod("parsePackage", File::class.java, Int::class.javaPrimitiveType)
            try {
                val pkg = parseMethod.invoke(parser, file, 1 shl 2) // PARSE_MUST_BE_APK
                val reqFeatures = pkg.javaClass.getField("reqFeatures").get(pkg) as? List<*>
                val names = reqFeatures?.map {
                    try {
                        it.javaClass.getField("name").get(it) as? String
                    } catch (_: Throwable) {
                        null
                    }
                }
                Log.e(TAG, "PackageParser directo SÍ parseó. reqFeatures=$names")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                Log.e(TAG, "PackageParser real error: ${e.cause?.message ?: e.message}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Reflexión PackageParser no disponible: ${e.message}")
        }
    }

    /**
     * Loads an extension.
     *
     * @param context The application context.
     * @param pkgInfo The package info of the extension to load.
     */
    private suspend fun loadExtension(context: Context, pkgInfo: PackageInfo): LoadResult {
        val pkgManager = context.packageManager
        val appInfo = pkgInfo.applicationInfo!!
        val pkgName = pkgInfo.packageName

        val extName = appInfo.metaData.getString(METADATA_NAME)
            ?: pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            return LoadResult.Error
        }

        // Validate lib version
        val libVersion = appInfo.metaData.getFloat(METADATA_EXTENSION_LIB)
            .takeUnless { it == 0.0f }
            ?.toString()
            ?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            return LoadResult.Error
        }

        val signatures = getSignatures(pkgInfo)
        if (signatures.isNullOrEmpty()) {
            return LoadResult.Error
        } else if (!trustExtension.isTrusted(pkgInfo, signatures)) {
            val extension = Extension.Untrusted(
                extName,
                pkgName,
                versionName,
                versionCode,
                libVersion,
                signatures.last(),
            )
            return LoadResult.Untrusted(extension)
        }

        val isNsfw = appInfo.metaData.getInt(METADATA_CONTENT_WARNING) > 0 ||
            appInfo.metaData.getInt(METADATA_NSFW) == 1
        if (!loadNsfwSource && isNsfw) {
            return LoadResult.Error
        }

        val classLoader = try {
            ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            return LoadResult.Error
        }

        val sources = appInfo.metaData.getString(METADATA_SOURCE_CLASS)!!
            .split(";")
            .map {
                val sourceClass = it.trim()
                if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
            }
            .flatMap {
                try {
                    when (val obj = Class.forName(it, false, classLoader).getDeclaredConstructor().newInstance()) {
                        is Source -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    return LoadResult.Error
                }
            }

        val langs = sources.map { it.lang }.toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val extension = Extension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            isNsfw = isNsfw,
            sources = sources,
            pkgFactory = appInfo.metaData.getString(METADATA_SOURCE_FACTORY),
            icon = appInfo.loadIcon(pkgManager),
            isShared = false,
        )
        return LoadResult.Success(extension)
    }

    /**
     * Returns true if the given package is an extension.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
    }

    /**
     * Returns the signatures of the package or null if it's not signed.
     *
     * @param pkgInfo The package info of the application.
     * @return List SHA256 digest of the signatures
     */
    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo!!
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
            ?.map { Hash.sha256(it.toByteArray()) }
            ?.toList()
    }

    /**
     * On Android 13+ the ApplicationInfo generated by getPackageArchiveInfo doesn't
     * have sourceDir which breaks assets loading (used for getting icon here).
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }
}