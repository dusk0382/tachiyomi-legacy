package net.spin.tachiyomi.legacy

import android.os.Environment
import android.util.Log
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Descarga mangas de las fuentes como CBZ locales en
 * `Descargas/MangaLite/<titulo>/<capitulo>.cbz` — el mismo formato que
 * entiende el lector local, de modo que lo descargado tambien aparece en la
 * pestaña Local y se puede abrir offline desde el detalle de la fuente.
 */
object MangaDownloader {

    private const val TAG = "MangaLite"
    const val ROOT_DIR = "MangaLite"

    fun rootDir(): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, ROOT_DIR).apply { mkdirs() }
    }

    fun mangaDir(mangaTitle: String): File {
        return File(rootDir(), sanitize(mangaTitle)).apply { mkdirs() }
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.ifBlank { "manga" }
    }

    /** Archivo CBZ del capítulo, o null si no está descargado. */
    fun chapterFile(mangaTitle: String, chapterName: String): File? {
        val f = File(mangaDir(mangaTitle), sanitize(chapterName) + ".cbz")
        return if (f.exists() && f.length() > 0) f else null
    }

    /** ¿Hay al menos un capítulo descargado de este manga? */
    fun isMangaDownloaded(mangaTitle: String): Boolean {
        val dir = mangaDir(mangaTitle)
        return dir.listFiles()?.any { it.isFile && it.extension.equals("cbz", ignoreCase = true) } == true
    }

    /** Elimina toda la descarga del manga. */
    fun deleteManga(mangaTitle: String): Boolean {
        return mangaDir(mangaTitle).deleteRecursively()
    }

    /** Descarga todos los capítulos (los que ya están se saltan). */
    suspend fun downloadManga(
        source: Source,
        chapters: List<SChapter>,
        mangaTitle: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val dir = mangaDir(mangaTitle)
        var done = 0
        val total = chapters.size

        for (chapter in chapters) {
            val existing = File(dir, sanitize(chapter.name) + ".cbz")
            if (existing.exists() && existing.length() > 0) {
                done++
                onProgress(done, total)
                continue
            }
            downloadChapter(source, chapter, dir)
            done++
            onProgress(done, total)
        }
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

            val cbz = File(dir, sanitize(chapter.name) + ".cbz")
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
