package net.spin.tachiyomi.legacy

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Descarga mangas de las fuentes como CBZ locales en
 * `MangaLite/<titulo>/<capitulo>.cbz` (la base puede ser Descargas publica o
 * la carpeta propia de la app en la tarjeta SD, segun Prefs.getDownloadStorage).
 * Los CBZ los entiende el lector local y se abren offline desde el detalle.
 */
object MangaDownloader {

    private const val TAG = "MangaLite"
    const val ROOT_DIR = "MangaLite"

    private var appContext: Context? = null

    /** Inicializa el contexto (necesario para detectar la tarjeta SD). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Base interna publica: Descargas (ruta clasica de siempre). */
    fun internalRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** Base en tarjeta SD (directorio propio de la app, sin permisos extra en
     *  Android 6), o null si no hay SD usable. */
    fun sdRoot(): File? {
        val ctx = appContext ?: return null
        val dirs = ContextCompat.getExternalFilesDirs(ctx, null)
        val sd = dirs.getOrNull(1) ?: return null
        return if (sd.exists() || sd.canWrite()) sd else null
    }

    /** Base configurada para las descargas NUEVAS. */
    fun preferredRoot(): File = when (Prefs.getDownloadStorage()) {
        Prefs.STORAGE_SD -> sdRoot() ?: internalRoot()
        else -> internalRoot()
    }

    fun rootDir(): File = File(preferredRoot(), ROOT_DIR).apply { mkdirs() }

    /**
     * Todas las ubicaciones donde puede haber descargas (la actual + la
     * anterior): al cambiar de almacenamiento no se pierde lo ya descargado.
     * Incluye la raiz publica sin subcarpeta: un build anterior descargaba a
     * `Descargas/<titulo>` (sin MangaLite) y esas no deben quedar huerfanas.
     */
    private fun allRootDirs(): List<File> {
        val roots = mutableListOf(File(internalRoot(), ROOT_DIR))
        val legacy = internalRoot()
        if (!roots.contains(legacy)) roots.add(legacy)
        sdRoot()?.let { sd ->
            val dir = File(sd, ROOT_DIR)
            if (!roots.contains(dir)) roots.add(dir)
        }
        return roots
    }

    fun mangaDir(mangaTitle: String): File {
        // SIEMPRE dentro de la subcarpeta MangaLite (antes faltaba y las
        // descargas caían en Descargas/<título> sin la subcarpeta).
        return File(rootDir(), sanitize(mangaTitle)).apply { mkdirs() }
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.ifBlank { "manga" }
    }

    /** Nombre del CBZ: nombre sanitizado + sufijo estable del url (evita colisiones). */
    internal fun cbzName(chapterName: String, chapterUrl: String): String {
        val suffix = chapterUrl.hashCode().toUInt().toString(16).take(6)
        return "${sanitize(chapterName)}_$suffix.cbz"
    }

    /**
     * Nombres de CBZ ya descargados del manga (una sola llamada a listFiles,
     * sin mkdirs): para saber si un capítulo está descargado sin tocar disco
     * por fila al renderizar listas largas.
     */
    fun downloadedCbzNames(mangaTitle: String): Set<String> {
        val name = sanitize(mangaTitle)
        val names = HashSet<String>()
        for (root in allRootDirs()) {
            val base = File(root, name)
            base.listFiles()?.forEach { f ->
                if (f.isFile && f.extension.equals("cbz", ignoreCase = true)) names.add(f.name)
            }
        }
        return names
    }

    /** ¿Este capítulo concreto está descargado? (según un set precomputado) */
    fun isDownloaded(chapterName: String, chapterUrl: String, downloadedNames: Set<String>): Boolean =
        cbzName(chapterName, chapterUrl) in downloadedNames

    /**
     * Archivo CBZ del capítulo, o null si no está descargado.
     * Sin mkdirs: se puede llamar desde el hilo principal en el render.
     */
    fun chapterFile(mangaTitle: String, chapterName: String, chapterUrl: String): File? {
        val fname = cbzName(chapterName, chapterUrl)
        val name = sanitize(mangaTitle)
        for (root in allRootDirs()) {
            val f = File(File(root, name), fname)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    /** ¿Hay al menos un capítulo descargado de este manga? (sin mkdirs) */
    fun isMangaDownloaded(mangaTitle: String): Boolean {
        val name = sanitize(mangaTitle)
        for (root in allRootDirs()) {
            val base = File(root, name)
            if (base.listFiles()?.any { it.isFile && it.extension.equals("cbz", ignoreCase = true) } == true) {
                return true
            }
        }
        return false
    }

    /** Elimina toda la descarga del manga (en cualquier ubicación). */
    fun deleteManga(mangaTitle: String): Boolean {
        val name = sanitize(mangaTitle)
        var any = false
        for (root in allRootDirs()) {
            val dir = File(root, name)
            if (dir.exists() && dir.deleteRecursively()) any = true
        }
        return any
    }

    /**
     * Descarga todos los capítulos (los que ya están se saltan). Devuelve el
     * número de capítulos que fallaron (0 = todo correcto), para que la UI no
     * diga "Descarga completa" cuando en realidad nada se bajó.
     */
    suspend fun downloadManga(
        source: Source,
        chapters: List<SChapter>,
        mangaTitle: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int {
        val dir = mangaDir(mangaTitle)
        var done = 0
        var failed = 0
        val total = chapters.size

        for (chapter in chapters) {
            val existing = File(dir, cbzName(chapter.name, chapter.url))
            if (existing.exists() && existing.length() > 0) {
                done++
            } else if (downloadChapter(source, chapter, dir)) {
                done++
            } else {
                failed++
            }
            onProgress(done + failed, total)
        }
        return failed
    }

    private suspend fun downloadChapter(source: Source, chapter: SChapter, dir: File): Boolean {
        val http = source as? HttpSource ?: return false
        val tmpDir = File(dir, ".tmp_${System.currentTimeMillis()}")

        try {
            val pages = source.getPageList(chapter)
            if (pages.isEmpty()) return false

            tmpDir.mkdirs()
            val digits = pages.size.toString().length.coerceAtLeast(3)

            var downloaded = 0
            for ((index, page) in pages.withIndex()) {
                try {
                    val url = page.imageUrl?.takeIf { it.isNotBlank() } ?: http.getImageUrl(page)
                    if (url.isNullOrBlank()) continue
                    // Igual que el lector online: dejar resuelta la URL en la página
                    // para que getImage() no la pida de nuevo ni falle.
                    if (page.imageUrl.isNullOrBlank()) {
                        page.imageUrl = url
                    }

                    val response = http.getImage(page, existingSize = 0L)
                    response.use { resp ->
                        if (!resp.isSuccessful) return@use
                        val out = File(tmpDir, String.format("%0${digits}d", index + 1) + ".jpg")
                        resp.body.byteStream().use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (out.length() > 0) downloaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Página ${index + 1} de '${chapter.name}' falló: ${e.message}")
                }
            }

            if (downloaded == 0) return false

            val cbz = File(dir, cbzName(chapter.name, chapter.url))
            if (cbz.exists()) cbz.delete()

            return zipFolder(tmpDir, cbz)
        } catch (e: Exception) {
            Log.e(TAG, "Error descargando capítulo '${chapter.name}'", e)
            return false
        } finally {
            try {
                tmpDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    private fun zipFolder(folder: File, dest: File): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(dest)).use { zos ->
                folder.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    if (f.isFile) {
                        zos.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error creando CBZ", e)
            dest.delete()
            false
        }
    }
}
