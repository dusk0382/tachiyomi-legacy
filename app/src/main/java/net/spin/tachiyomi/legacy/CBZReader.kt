package net.spin.tachiyomi.legacy

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Reads a local CBZ/CBR-like archive. Pages are extracted to the disk cache
 * on first access and decoded with the shared OOM-safe pipeline.
 */
class CBZReader(private val file: File, context: Context? = null) : FileBackedPageReader() {

    private val zipFile: ZipFile = ZipFile(file)
    private val zipLock = Any()
    private val pageEntries: List<PageEntry> by lazy { buildPageEntries() }

    override val diskCacheDir: File = if (context != null) {
        File(context.cacheDir, "manga_cache").apply { mkdirs() }
    } else {
        File(System.getProperty("java.io.tmpdir"), "mangalite_cache").apply { mkdirs() }
    }

    private fun buildPageEntries(): List<PageEntry> {
        return zipFile.entries().toList()
            .filter { entry -> !entry.isDirectory && entry.name.isImageFile() }
            .sortedWith(Comparator { a, b -> NaturalSort.compare(a.name, b.name) })
            .map { PageEntry(it.name, it.size) }
    }

    override val pageCount: Int get() = pageEntries.size

    /**
     * Cuenta las páginas-imagen sin construir la lista ordenada de [pageEntries].
     * Mucho más barato cuando solo se necesita el número (no se ordena ni se asignan).
     */
    fun countPagesFast(): Int {
        var count = 0
        val en = zipFile.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (!e.isDirectory && e.name.isImageFile()) count++
        }
        return count
    }

    override suspend fun getCachedPageFile(pageIndex: Int): File? {
        if (pageIndex !in 0 until pageCount) return null
        val entry = pageEntries[pageIndex]
        val cacheFile = cacheFileFor(entry.name)

        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        val zipEntry = zipFile.getEntry(entry.name) ?: return null
        return extractToCache(zipEntry, cacheFile)
    }

    private fun cacheFileFor(entryName: String): File {
        val cacheKey = "${file.absolutePath.hashCode()}_${entryName.hashCode()}"
        return File(diskCacheDir, "$cacheKey$CACHE_SUFFIX")
    }

    private fun extractToCache(
        zipEntry: java.util.zip.ZipEntry,
        cacheFile: File,
    ): File? {
        val tmpFile = File(cacheFile.absolutePath + "." + Thread.currentThread().id + ".tmp")

        try {
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return cacheFile
            }

            synchronized(zipLock) {
                zipFile.getInputStream(zipEntry).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!tmpFile.exists() || tmpFile.length() <= 0) {
                tmpFile.delete()
                return null
            }

            if (cacheFile.exists()) cacheFile.delete()

            return if (tmpFile.renameTo(cacheFile)) {
                trimDiskCache()
                cacheFile
            } else {
                // No se pudo renombrar: decodificar desde el tmp en su lugar.
                tmpFile
            }
        } catch (e: Exception) {
            Log.e("MangaLite", "Error extrayendo del ZIP", e)
            tmpFile.delete()
            return null
        }
    }

    private fun String.isImageFile(): Boolean {
        val l = this.lowercase()
        return l.endsWith(".jpg") || l.endsWith(".jpeg") ||
            l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".bmp")
    }

    override fun close() {
        try {
            zipFile.close()
        } catch (_: Exception) {
        }
    }

    private data class PageEntry(val name: String, val size: Long)
}
