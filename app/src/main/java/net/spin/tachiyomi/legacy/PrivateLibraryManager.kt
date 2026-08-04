package net.spin.tachiyomi.legacy

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object PrivateLibraryManager {

    private const val PREFS_NAME = "private_lib_prefs"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_IS_SETUP = "is_setup"
    private const val KEY_MANIFEST = "manifest_data"
    private const val KEY_GRANTED_URIS = "granted_uris"

    private const val PRIVATE_DIR_NAME = ".config"
    private const val CACHE_SUBDIR = ".cache"
    private const val FILE_EXTENSION = ".dat"
    private const val XOR_KEY: Byte = 0x5A

    private var prefs: SharedPreferences? = null
    private var privateDir: File? = null
    private var context: Context? = null
    private var isOnSdCard: Boolean = false

    fun init(context: Context) {
        val app = context.applicationContext
        this.context = app

        if (prefs == null) {
            prefs = app.getSharedPreferences(PREFS_NAME, 0)
        }

        if (privateDir == null) {
            val externalDirs = ContextCompat.getExternalFilesDirs(app, null)
            val secondary = externalDirs.getOrNull(1)
            val primary = externalDirs.getOrNull(0)

            val targetDir = when {
                secondary != null && secondary.canWrite() -> secondary
                primary != null && primary.canWrite() -> primary
                else -> app.filesDir
            }

            isOnSdCard = secondary != null && targetDir == secondary

            privateDir = File(targetDir, PRIVATE_DIR_NAME).apply {
                mkdirs()
                File(this, CACHE_SUBDIR).mkdirs()
            }

            Log.d("PrivateVault", "Carpeta privada en: ${privateDir?.absolutePath}")
            Log.d("PrivateVault", "Usando SD: $isOnSdCard")
        }
    }

    fun isStorageOnSdCard(): Boolean = isOnSdCard

    fun getPrivateDirPath(): String? = privateDir?.absolutePath

    fun isPinSetup(): Boolean = prefs?.getBoolean(KEY_IS_SETUP, false) ?: false

    fun setupPin(pin: String): Boolean {
        if (pin.length != 4 || !pin.all { it.isDigit() }) return false

        val hash = sha256(pin)

        prefs?.edit()
            ?.putString(KEY_PIN_HASH, hash)
            ?.putBoolean(KEY_IS_SETUP, true)
            ?.apply()

        return true
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs?.getString(KEY_PIN_HASH, null) ?: return false
        return sha256(pin) == storedHash
    }

    data class MoveResult(
        val success: Boolean,
        val originalFileDeleted: Boolean,
        val originalPath: String? = null,
        val needsSafPermission: Boolean = false,
        val key: String? = null
    )

    data class RestoreResult(
        val success: Boolean,
        val restoredFile: File? = null,
        val message: String? = null
    )

    fun saveGrantedUri(uri: Uri) {
        val ctx = context ?: return

        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val uris = getGrantedUris().toMutableSet()
            uris.add(uri.toString())

            prefs?.edit()?.putStringSet(KEY_GRANTED_URIS, uris)?.apply()

            Log.d("PrivateVault", "URI guardado: $uri")
        } catch (e: Exception) {
            Log.e("PrivateVault", "Error guardando URI", e)
        }
    }

    fun getGrantedUris(): Set<String> {
        return prefs?.getStringSet(KEY_GRANTED_URIS, emptySet()) ?: emptySet()
    }

    fun getKeyForFile(file: File): String? {
        val key = entryKey(file.absolutePath)
        return if (loadManifest().containsKey(key)) key else null
    }

    fun isAlreadyPrivate(file: File): Boolean {
        return loadManifest().containsKey(entryKey(file.absolutePath))
    }

    fun deleteOriginal(file: File): Boolean {
        if (!file.exists()) return true

        return try {
            if (file.delete()) {
                true
            } else {
                deleteWithSaf(file)
            }
        } catch (e: Exception) {
            Log.w("PrivateVault", "Error borrando original: ${e.message}")
            deleteWithSaf(file)
        }
    }

    fun retryDeleteOriginal(key: String, originalFile: File): Boolean {
        val manifest = loadManifest()
        if (!manifest.containsKey(key)) return false
        return deleteOriginal(originalFile)
    }

    fun addPrivateManga(sourceFile: File): MoveResult {
        val privateDir = privateDir ?: return MoveResult(false, false)

        if (!sourceFile.exists() || !sourceFile.extension.equals("cbz", ignoreCase = true)) {
            return MoveResult(false, false)
        }

        val originalPath = sourceFile.absolutePath
        val key = entryKey(originalPath)
        val manifest = loadManifest()

        val existing = manifest[key]
        if (existing != null) {
            val privateFile = File(
                File(privateDir, CACHE_SUBDIR),
                "${existing.obfuscatedName}$FILE_EXTENSION"
            )

            if (privateFile.exists()) {
                val deleted = deleteOriginal(sourceFile)
                return MoveResult(
                    success = true,
                    originalFileDeleted = deleted,
                    originalPath = originalPath,
                    needsSafPermission = !deleted,
                    key = key
                )
            } else {
                removeFromManifest(key)
            }
        }

        val obfuscatedName = obfuscateFilename(sourceFile.name + sourceFile.absolutePath)
        val destFile = File(
            File(privateDir, CACHE_SUBDIR),
            "$obfuscatedName$FILE_EXTENSION"
        )

        return try {
            if (destFile.exists()) destFile.delete()

            val sourceLength = sourceFile.length()
            var originalDeleted = false

            val renamed = sourceFile.renameTo(destFile)

            if (renamed) {
                originalDeleted = true

                if (destFile.length() != sourceLength) {
                    Log.w("PrivateVault", "renameTo correcto pero el tamaño no coincide")
                }
            } else {
                Log.d("PrivateVault", "renameTo falló, copiando manualmente...")

                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (!destFile.exists() || destFile.length() != sourceLength) {
                    destFile.delete()
                    return MoveResult(false, false)
                }

                originalDeleted = deleteOriginal(sourceFile)
            }

            addToManifest(
                key = key,
                title = sourceFile.nameWithoutExtension,
                obfuscatedName = obfuscatedName,
                originalPath = originalPath
            )

            migrateProgress(originalPath, destFile.absolutePath)

            MoveResult(
                success = true,
                originalFileDeleted = originalDeleted,
                originalPath = originalPath,
                needsSafPermission = !originalDeleted,
                key = key
            )
        } catch (e: Exception) {
            Log.e("PrivateVault", "Error moviendo archivo", e)
            MoveResult(false, false)
        }
    }

    fun removePrivateManga(key: String): RestoreResult {
        val manifest = loadManifest()
        val entry = manifest[key]
            ?: return RestoreResult(false, message = "Entrada privada no encontrada")

        val dir = privateDir ?: return RestoreResult(false, message = "Carpeta privada no inicializada")

        val cacheDir = File(dir, CACHE_SUBDIR)
        val sourceFile = File(cacheDir, "${entry.obfuscatedName}$FILE_EXTENSION")

        if (!sourceFile.exists()) {
            removeFromManifest(key)
            return RestoreResult(false, message = "El archivo privado no existe")
        }

        // 1. Intentar restaurar directamente en la ruta original.
        if (entry.originalPath.isNotBlank()) {
            val originalFile = File(entry.originalPath)
            val destDir = originalFile.parentFile

            if (destDir != null) {
                try {
                    if (!destDir.exists()) destDir.mkdirs()
                } catch (_: Exception) {
                }

                if (destDir.exists() && destDir.canWrite()) {
                    val name = originalFile.name.ifBlank { entry.title + ".cbz" }
                    val destFile = uniqueFile(destDir, name)

                    if (restoreFile(sourceFile, destFile)) {
                        removeFromManifest(key)
                        migrateProgress(sourceFile.absolutePath, destFile.absolutePath)

                        return RestoreResult(
                            success = true,
                            restoredFile = destFile,
                            message = null
                        )
                    }
                }
            }
        }

        // 2. Fallback: restaurar en Downloads.
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        try {
            if (!downloads.exists()) downloads.mkdirs()
        } catch (_: Exception) {
        }

        if (downloads.exists() && downloads.canWrite()) {
            val name = entry.title.ifBlank { "manga" } + ".cbz"
            val destFile = uniqueFile(downloads, name)

            if (restoreFile(sourceFile, destFile)) {
                removeFromManifest(key)
                migrateProgress(sourceFile.absolutePath, destFile.absolutePath)

                return RestoreResult(
                    success = true,
                    restoredFile = destFile,
                    message = "Restaurado en Descargas"
                )
            }
        }

        // 3. Fallback final: SAF.
        if (restoreWithSaf(sourceFile, entry)) {
            sourceFile.delete()
            removeFromManifest(key)
            migrateProgress(sourceFile.absolutePath, entry.originalPath)

            return RestoreResult(
                success = true,
                restoredFile = null,
                message = "Restaurado mediante SAF"
            )
        }

        return RestoreResult(false, message = "No se pudo restaurar el archivo")
    }

    fun getPrivateMangas(): List<MangaFile> {
        val manifest = loadManifest()
        val mangas = mutableListOf<MangaFile>()

        val dir = privateDir ?: return mangas
        val cacheDir = File(dir, CACHE_SUBDIR)

        if (!cacheDir.exists()) return mangas

        for ((key, entry) in manifest) {
            val file = File(cacheDir, "${entry.obfuscatedName}$FILE_EXTENSION")

            if (file.exists()) {
                mangas.add(
                    MangaFile(
                        file = file,
                        title = entry.title,
                        privateKey = key
                    )
                )
            }
        }

        return mangas
    }

    private fun restoreFile(source: File, dest: File): Boolean {
        val len = source.length()

        return try {
            if (dest.exists()) dest.delete()

            if (source.renameTo(dest)) {
                true
            } else {
                FileInputStream(source).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }

                if (dest.length() != len) {
                    dest.delete()
                    return false
                }

                if (!source.delete()) {
                    dest.delete()
                    return false
                }

                true
            }
        } catch (e: Exception) {
            Log.e("PrivateVault", "Error restaurando archivo", e)
            dest.delete()
            false
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var base = name

        if (!base.endsWith(".cbz", ignoreCase = true)) {
            base += ".cbz"
        }

        var file = File(dir, base)
        var counter = 1

        val nameWithoutExt = base.substringBeforeLast('.')

        while (file.exists()) {
            file = File(dir, "${nameWithoutExt}_$counter.cbz")
            counter++
        }

        return file
    }

    private fun deleteWithSaf(file: File): Boolean {
        val ctx = context ?: return false

        val fileName = file.name
        val parentPath = file.parentFile?.absolutePath ?: return false

        Log.d("PrivateVault", "Buscando archivo con SAF: ${file.absolutePath}")

        for (uriString in getGrantedUris()) {
            try {
                val treeUri = Uri.parse(uriString)
                val rootDoc = DocumentFile.fromTreeUri(ctx, treeUri) ?: continue

                val found = findFileInParentPath(rootDoc, parentPath, fileName)

                if (found != null && found.exists()) {
                    val deleted = found.delete()

                    if (deleted) {
                        Log.d("PrivateVault", "Archivo borrado con SAF: $fileName")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w("PrivateVault", "Error con URI $uriString", e)
            }
        }

        return false
    }

    private fun restoreWithSaf(sourceFile: File, entry: ManifestEntry): Boolean {
        val ctx = context ?: return false

        val fileName = File(entry.originalPath).name.ifBlank { entry.title + ".cbz" }
        val parentPath = File(entry.originalPath).parent ?: return false

        Log.d("PrivateVault", "Intentando restaurar con SAF a: ${entry.originalPath}")

        for (uriString in getGrantedUris()) {
            try {
                val treeUri = Uri.parse(uriString)
                val rootDoc = DocumentFile.fromTreeUri(ctx, treeUri) ?: continue

                val targetDir = getOrCreateTargetDir(rootDoc, parentPath) ?: continue

                val existing = targetDir.findFile(fileName)
                if (existing != null && existing.exists()) {
                    existing.delete()
                }

                val newFile = targetDir.createFile("application/zip", fileName)

                if (newFile != null) {
                    val output = ctx.contentResolver.openOutputStream(newFile.uri)

                    if (output != null) {
                        output.use { out ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(out)
                            }
                        }

                        val expected = sourceFile.length()
                        val actual = newFile.length()

                        if (expected > 0 && actual == expected) {
                            Log.d("PrivateVault", "Archivo restaurado con SAF: $fileName")
                            return true
                        } else {
                            newFile.delete()
                        }
                    } else {
                        newFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w("PrivateVault", "Error restaurando con URI $uriString", e)
            }
        }

        return false
    }

    private fun findFileInParentPath(
        root: DocumentFile,
        parentPath: String,
        fileName: String
    ): DocumentFile? {
        val rootName = root.name ?: return null

        val segments = parentPath.split('/')
            .filter { it.isNotBlank() }

        val rootIndex = segments.indexOfLast { it.equals(rootName, ignoreCase = true) }

        if (rootIndex < 0) return null

        val relative = segments.subList(rootIndex + 1, segments.size)

        var current: DocumentFile? = root

        for (segment in relative) {
            val next = current?.findFile(segment) ?: return null

            if (!next.isDirectory) return null

            current = next
        }

        return current?.findFile(fileName)?.takeIf { it.isFile }
    }

    private fun getOrCreateTargetDir(
        root: DocumentFile,
        parentPath: String
    ): DocumentFile? {
        val rootName = root.name ?: return null

        val segments = parentPath.split('/')
            .filter { it.isNotBlank() }

        val rootIndex = segments.indexOfLast { it.equals(rootName, ignoreCase = true) }

        if (rootIndex < 0) return null

        val relative = segments.subList(rootIndex + 1, segments.size)

        var current: DocumentFile? = root

        for (segment in relative) {
            var next = current?.findFile(segment)

            if (next == null) {
                next = current?.createDirectory(segment) ?: return null
            }

            if (!next.isDirectory) return null

            current = next
        }

        return current
    }

    private fun migrateProgress(fromPath: String, toPath: String) {
        try {
            val last = Prefs.getLastPage(fromPath)
            val total = Prefs.getTotalPages(fromPath)

            if (last > 0) {
                Prefs.setLastPage(toPath, last)
            }

            if (total > 0) {
                Prefs.setTotalPages(toPath, total)
            }
        } catch (_: Exception) {
        }
    }

    private fun entryKey(path: String): String {
        return sha256(path).take(16)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun obfuscateFilename(filename: String): String {
        return sha256(filename).take(16)
    }

    private data class ManifestEntry(
        val key: String,
        val title: String,
        val obfuscatedName: String,
        val originalPath: String
    )

    private fun addToManifest(
        key: String,
        title: String,
        obfuscatedName: String,
        originalPath: String
    ) {
        val manifest = loadManifest().toMutableMap()

        manifest[key] = ManifestEntry(
            key = key,
            title = title,
            obfuscatedName = obfuscatedName,
            originalPath = originalPath
        )

        saveManifest(manifest)
    }

    private fun removeFromManifest(key: String) {
        val manifest = loadManifest().toMutableMap()
        manifest.remove(key)
        saveManifest(manifest)
    }

    private fun loadManifest(): Map<String, ManifestEntry> {
        val encrypted = prefs?.getString(KEY_MANIFEST, null) ?: return emptyMap()

        val json = try {
            xorDecrypt(encrypted)
        } catch (e: Exception) {
            Log.e("PrivateVault", "Error desencriptando manifiesto", e)
            return emptyMap()
        }

        return try {
            val jsonArray = JSONArray(json)
            val map = mutableMapOf<String, ManifestEntry>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)

                val original = obj.optString("original", "")
                val obfuscated = obj.getString("obfuscated")
                val path = obj.optString("path", "")
                val key = obj.optString("key", "")
                val title = obj.optString("title", "")

                val effectivePath = path.ifBlank { original }
                val effectiveKey = key.ifBlank {
                    entryKey(effectivePath.ifBlank { original })
                }

                val effectiveTitle = title.ifBlank {
                    if (effectivePath.isNotBlank()) {
                        File(effectivePath).nameWithoutExtension
                    } else {
                        original
                            .removeSuffix(".cbz")
                            .removeSuffix(".CBZ")
                    }
                }

                map[effectiveKey] = ManifestEntry(
                    key = effectiveKey,
                    title = effectiveTitle,
                    obfuscatedName = obfuscated,
                    originalPath = effectivePath
                )
            }

            map
        } catch (e: Exception) {
            Log.e("PrivateVault", "Error leyendo manifiesto", e)
            emptyMap()
        }
    }

    private fun saveManifest(manifest: Map<String, ManifestEntry>) {
        val jsonArray = JSONArray()

        for ((_, entry) in manifest) {
            val obj = JSONObject()

            obj.put("key", entry.key)
            obj.put("title", entry.title)
            obj.put("original", entry.originalPath)
            obj.put("obfuscated", entry.obfuscatedName)
            obj.put("path", entry.originalPath)

            jsonArray.put(obj)
        }

        val encrypted = xorEncrypt(jsonArray.toString())

        prefs?.edit()?.putString(KEY_MANIFEST, encrypted)?.apply()
    }

    private fun xorEncrypt(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)

        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor XOR_KEY.toInt()).toByte()
        }

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun xorDecrypt(input: String): String {
        val bytes = Base64.decode(input, Base64.NO_WRAP)

        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor XOR_KEY.toInt()).toByte()
        }

        return String(bytes, Charsets.UTF_8)
    }
}
