package net.spin.tachiyomi.legacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class CBZReader(private val file: File, context: Context? = null) : AutoCloseable {

    private val zipFile: ZipFile = ZipFile(file)
    private val zipLock = Any()
    private val pageEntries: List<PageEntry> by lazy { buildPageEntries() }
    private val diskCacheDir: File

    init {
        diskCacheDir = if (context != null) {
            File(context.cacheDir, "manga_cache").apply { mkdirs() }
        } else {
            File(System.getProperty("java.io.tmpdir"), "mangalite_cache").apply { mkdirs() }
        }
    }

    private fun buildPageEntries(): List<PageEntry> {
        return zipFile.entries().toList()
            .filter { entry -> !entry.isDirectory && entry.name.isImageFile() }
            .sortedWith(Comparator { a, b -> NaturalSort.compare(a.name, b.name) })
            .map { PageEntry(it.name, it.size) }
    }

    val pageCount: Int get() = pageEntries.size

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

    fun decodePage(pageIndex: Int, screenWidth: Int, screenHeight: Int): Bitmap? {
        if (pageIndex !in 0 until pageCount) {
            Log.w("MangaLite", "Índice fuera de rango: $pageIndex")
            return null
        }

        val entry = pageEntries[pageIndex]
        val zipEntry = zipFile.getEntry(entry.name) ?: return null

        val cacheFile = cacheFileFor(entry.name)

        return try {
            if (cacheFile.exists() && cacheFile.length() > 0) {
                decodeFromFile(cacheFile, screenWidth, screenHeight)
            } else {
                decodeFromZipAndCache(zipEntry, cacheFile, screenWidth, screenHeight)
            }
        } catch (e: OutOfMemoryError) {
            Log.e("MangaLite", "OOM decodificando página $pageIndex", e)
            System.gc()
            null
        } catch (e: Exception) {
            Log.e("MangaLite", "Error decodificando página $pageIndex", e)
            cacheFile.delete()
            null
        }
    }

    private fun decodeFromZipAndCache(
        zipEntry: java.util.zip.ZipEntry,
        cacheFile: File,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap? {
        val cachedFile = extractToCache(zipEntry, cacheFile) ?: return null
        return decodeFromFile(cachedFile, screenWidth, screenHeight)
    }

    private fun cacheFileFor(entryName: String): File {
        val cacheKey = "${file.absolutePath.hashCode()}_${entryName.hashCode()}"
        return File(diskCacheDir, "$cacheKey.raw")
    }

    /** Asegura que la página esté extraída a disco y devuelve el archivo cached. */
    fun getCachedPageFile(pageIndex: Int): File? {
        if (pageIndex !in 0 until pageCount) return null
        val entry = pageEntries[pageIndex]
        val cacheFile = cacheFileFor(entry.name)

        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        val zipEntry = zipFile.getEntry(entry.name) ?: return null
        return extractToCache(zipEntry, cacheFile)
    }

    /** Dimensiones originales de la página (sin decodificarla completa). */
    fun getPageBounds(pageIndex: Int): Pair<Int, Int>? {
        val cachedFile = getCachedPageFile(pageIndex) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cachedFile.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return Pair(opts.outWidth, opts.outHeight)
    }

    /**
     * Decodifica solo la región visible (en coordenadas de píxel originales).
     * Usa BitmapRegionDecoder sobre el archivo cacheado. Si falla, hace fallback
     * a la página completa de alta resolución.
     */
    fun decodeRegion(
        pageIndex: Int,
        region: Rect,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val cachedFile = getCachedPageFile(pageIndex) ?: return decodeFullPageHighRes(pageIndex)

        return try {
            val decoder = BitmapRegionDecoder.newInstance(cachedFile.absolutePath, false)
            try {
                val sample = calculateInSampleSize(
                    region.width(),
                    region.height(),
                    targetWidth.coerceAtLeast(1),
                    targetHeight.coerceAtLeast(1)
                )
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inMutable = false
                }
                decoder.decodeRegion(region, opts)
            } finally {
                decoder.recycle()
            }
        } catch (e: OutOfMemoryError) {
            Log.e("MangaLite", "OOM en decodeRegion $pageIndex", e)
            System.gc()
            decodeRegionFallback(cachedFile, region, targetWidth, targetHeight)
        } catch (e: Exception) {
            Log.w("MangaLite", "decodeRegion falló, usando fallback: ${e.message}")
            decodeRegionFallback(cachedFile, region, targetWidth, targetHeight)
        }
    }

    /**
     * Fallback: decodifica la página completa a alta resolución y recorta la región.
     * Devuelve un bitmap alineado a la región pedida.
     */
    private fun decodeRegionFallback(
        cachedFile: File,
        region: Rect,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cachedFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) {
            sample *= 2
        }

        var full: Bitmap? = null
        try {
            full = decodeFileWithSample(cachedFile, sample)
        } catch (e: OutOfMemoryError) {
            Log.e("MangaLite", "OOM full fallback, subiendo sample", e)
            System.gc()
            try {
                full = decodeFileWithSample(cachedFile, sample * 2)
            } catch (e2: OutOfMemoryError) {
                return null
            }
        }
        val fullBmp = full ?: return null

        val scaleX = fullBmp.width.toFloat() / bounds.outWidth
        val scaleY = fullBmp.height.toFloat() / bounds.outHeight

        var l = (region.left * scaleX).toInt().coerceIn(0, fullBmp.width - 1)
        var t = (region.top * scaleY).toInt().coerceIn(0, fullBmp.height - 1)
        var r = (region.right * scaleX).toInt().coerceIn(l + 1, fullBmp.width)
        var b = (region.bottom * scaleY).toInt().coerceIn(t + 1, fullBmp.height)

        if (r <= l) r = (l + 1).coerceAtMost(fullBmp.width)
        if (b <= t) b = (t + 1).coerceAtMost(fullBmp.height)

        val cropped = Bitmap.createBitmap(fullBmp, l, t, r - l, b - t)
        if (cropped !== fullBmp) fullBmp.recycle()

        val cw = cropped.width
        val ch = cropped.height
        if (cw > targetWidth.coerceAtLeast(1) * 2 || ch > targetHeight.coerceAtLeast(1) * 2) {
            val subSample = calculateInSampleSize(cw, ch, targetWidth, targetHeight)
            if (subSample > 1) {
                val scaled = cropped.copy(Bitmap.Config.RGB_565, false)
                cropped.recycle()
                val out = Bitmap.createScaledBitmap(
                    scaled,
                    (cw / subSample).coerceAtLeast(1),
                    (ch / subSample).coerceAtLeast(1),
                    true
                )
                scaled.recycle()
                return out
            }
        }

        return cropped
    }

    /** Fallback: decodifica la página completa a alta resolución (lado mayor <= maxEdge). */
    fun decodeFullPageHighRes(pageIndex: Int, maxEdge: Int = 2048): Bitmap? {
        val cachedFile = getCachedPageFile(pageIndex) ?: return null
        return decodeFullHighRes(cachedFile, maxEdge)
    }

    private fun decodeFullHighRes(cachedFile: File, maxEdge: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cachedFile.absolutePath, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            return null
        }

        var sample = 1
        while (boundsOpts.outWidth / sample > maxEdge || boundsOpts.outHeight / sample > maxEdge) {
            sample *= 2
        }

        return try {
            decodeFileWithSample(cachedFile, sample)
        } catch (e: OutOfMemoryError) {
            Log.e("MangaLite", "OOM full high-res, subiendo sample", e)
            System.gc()
            try {
                decodeFileWithSample(cachedFile, sample * 2)
            } catch (e2: OutOfMemoryError) {
                System.gc()
                null
            }
        }
    }

    private fun extractToCache(
        zipEntry: java.util.zip.ZipEntry,
        cacheFile: File
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

    private fun decodeFromFile(imageFile: File, screenWidth: Int, screenHeight: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(imageFile.absolutePath, boundsOpts)

        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            Log.w("MangaLite", "Dimensiones inválidas en ${imageFile.name}")
            imageFile.delete()
            return null
        }

        val targetW = screenWidth.coerceAtLeast(1).coerceAtMost(800)
        val targetH = (screenHeight * 2).coerceAtLeast(1).coerceAtMost(1200)

        val sample = calculateInSampleSize(
            boundsOpts.outWidth,
            boundsOpts.outHeight,
            targetW,
            targetH
        )

        return try {
            decodeFileWithSample(imageFile, sample)
        } catch (oom: OutOfMemoryError) {
            Log.e("MangaLite", "OOM decode, reintentando con sample mayor", oom)
            System.gc()
            try {
                decodeFileWithSample(imageFile, sample * 2)
            } catch (oom2: OutOfMemoryError) {
                Log.e("MangaLite", "OOM decode segundo intento", oom2)
                null
            }
        }
    }

    private fun decodeFileWithSample(imageFile: File, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
            inDither = false
            inMutable = false
        }
        return BitmapFactory.decodeFile(imageFile.absolutePath, opts)
    }

    private fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var sample = 1

        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfH = srcHeight / 2
            val halfW = srcWidth / 2

            while ((halfH / sample) >= reqHeight && (halfW / sample) >= reqWidth) {
                sample *= 2
            }
        }

        return sample
    }

    private fun trimDiskCache() {
        try {
            val files = diskCacheDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".raw") }
                ?: return

            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return

            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_CACHE_BYTES) break

                val len = f.length()
                if (f.delete()) {
                    total -= len
                }
            }
        } catch (e: Exception) {
            Log.w("MangaLite", "No se pudo limpiar cache disco: ${e.message}")
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

    companion object {
        private const val MAX_CACHE_BYTES = 50L * 1024L * 1024L
    }
}
