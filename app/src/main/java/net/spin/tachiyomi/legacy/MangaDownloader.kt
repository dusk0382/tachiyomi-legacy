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
        val base = File(rootDir(), sanitize(mangaTitle))
        return base.listFiles()
            ?.filter { it.isFile && it.extension.equals("cbz", ignoreCase = true) }
            ?.mapTo(HashSet()) { it.name }
            ?: emptySet()
    }

    /** ¿Este capítulo concreto está descargado? (según un set precomputado) */
    fun isDownloaded(chapterName: String, chapterUrl: String, downloadedNames: Set<String>): Boolean =
        cbzName(chapterName, chapterUrl) in downloadedNames

    /**
     * Archivo CBZ del capítulo, o null si no está descargado.
     * Sin mkdirs: se puede llamar desde el hilo principal en el render.
     */
    fun chapterFile(mangaTitle: String, chapterName: String, chapterUrl: String): File? {
        val base = File(rootDir(), sanitize(mangaTitle))
        val f = File(base, cbzName(chapterName, chapterUrl))
        return if (f.exists() && f.length() > 0) f else null
    }

    /** ¿Hay al menos un capítulo descargado de este manga? (sin mkdirs) */
    fun isMangaDownloaded(mangaTitle: String): Boolean {
        val base = File(rootDir(), sanitize(mangaTitle))
        return base.listFiles()?.any { it.isFile && it.extension.equals("cbz", ignoreCase = true) } == true
    }

    /** Elimina toda la descarga del manga. */
    fun deleteManga(mangaTitle: String): Boolean {
        return mangaDir(mangaTitle).deleteRecursively()
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
