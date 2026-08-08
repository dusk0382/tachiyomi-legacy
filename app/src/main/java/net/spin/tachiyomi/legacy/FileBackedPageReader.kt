package net.spin.tachiyomi.legacy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import java.io.File

/**
 * Base for readers whose pages are backed by image files on disk.
 * Holds the OOM-safe decode pipeline shared by CBZ archives and online chapters:
 * pages are materialized to [diskCacheDir] and decoded with full color
 * (ARGB_8888) + inSampleSize, with graceful fallbacks for region decoding and
 * OutOfMemoryError.
 */
abstract class FileBackedPageReader : PageReader {

    protected abstract val diskCacheDir: File

    /** Returns (and materializes if needed) the cached image file for a page. */
    protected abstract suspend fun getCachedPageFile(pageIndex: Int): File?

    override suspend fun decodePage(pageIndex: Int, screenWidth: Int, screenHeight: Int): Bitmap? {
        if (pageIndex !in 0 until pageCount) {
            Log.w(TAG, "Índice fuera de rango: $pageIndex")
            return null
        }

        val cachedFile = getCachedPageFile(pageIndex) ?: return null

        return try {
            decodeFromFile(cachedFile, screenWidth, screenHeight)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decodificando página $pageIndex", e)
            System.gc()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando página $pageIndex", e)
            cachedFile.delete()
            null
        }
    }

    override suspend fun getPageBounds(pageIndex: Int): Pair<Int, Int>? {
        val cachedFile = getCachedPageFile(pageIndex) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cachedFile.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return Pair(opts.outWidth, opts.outHeight)
    }

    override suspend fun decodeRegion(
        pageIndex: Int,
        region: Rect,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val cachedFile = getCachedPageFile(pageIndex) ?: return decodeFullPageHighRes(pageIndex)

        return try {
            val decoder = BitmapRegionDecoder.newInstance(cachedFile.absolutePath, false)
            try {
                val sample = calculateInSampleSize(
                    region.width(),
                    region.height(),
                    targetWidth.coerceAtLeast(1),
                    targetHeight.coerceAtLeast(1),
                )
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = sample
                    inMutable = false
                }
                decoder.decodeRegion(region, opts)
            } finally {
                decoder.recycle()
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM en decodeRegion $pageIndex", e)
            System.gc()
            decodeRegionFallback(cachedFile, region, targetWidth, targetHeight)
        } catch (e: Exception) {
            Log.w(TAG, "decodeRegion falló, usando fallback: ${e.message}")
            decodeRegionFallback(cachedFile, region, targetWidth, targetHeight)
        }
    }

    override suspend fun decodeFullPageHighRes(pageIndex: Int, maxEdge: Int): Bitmap? {
        val cachedFile = getCachedPageFile(pageIndex) ?: return null
        return decodeFullHighRes(cachedFile, maxEdge)
    }

    // ------------------------------------------------------------------
    // Shared decode helpers
    // ------------------------------------------------------------------

    private fun decodeFromFile(imageFile: File, screenWidth: Int, screenHeight: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(imageFile.absolutePath, boundsOpts)

        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            Log.w(TAG, "Dimensiones inválidas en ${imageFile.name}")
            imageFile.delete()
            return null
        }

        val targetW = screenWidth.coerceAtLeast(1).coerceAtMost(800)
        val targetH = (screenHeight * 2).coerceAtLeast(1).coerceAtMost(1200)

        val sample = calculateInSampleSize(
            boundsOpts.outWidth,
            boundsOpts.outHeight,
            targetW,
            targetH,
        )

        return try {
            decodeFileWithSample(imageFile, sample)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM decode, reintentando con sample mayor", oom)
            System.gc()
            try {
                decodeFileWithSample(imageFile, sample * 2)
            } catch (oom2: OutOfMemoryError) {
                Log.e(TAG, "OOM decode segundo intento", oom2)
                null
            }
        }
    }

    private fun decodeFileWithSample(imageFile: File, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            inMutable = false
        }
        return BitmapFactory.decodeFile(imageFile.absolutePath, opts)
    }

    /**
     * Fallback: decodes the full page at high resolution and crops the region.
     * Returns a bitmap aligned to the requested region.
     */
    private fun decodeRegionFallback(
        cachedFile: File,
        region: Rect,
        targetWidth: Int,
        targetHeight: Int,
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
            Log.e(TAG, "OOM full fallback, subiendo sample", e)
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
                val scaled = cropped.copy(Bitmap.Config.ARGB_8888, false)
                cropped.recycle()
                val out = Bitmap.createScaledBitmap(
                    scaled,
                    (cw / subSample).coerceAtLeast(1),
                    (ch / subSample).coerceAtLeast(1),
                    true,
                )
                scaled.recycle()
                return out
            }
        }

        return cropped
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
            Log.e(TAG, "OOM full high-res, subiendo sample", e)
            System.gc()
            try {
                decodeFileWithSample(cachedFile, sample * 2)
            } catch (e2: OutOfMemoryError) {
                System.gc()
                null
            }
        }
    }

    protected fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
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

    /** Deletes the oldest cached files until the cache fits in [maxCacheBytes]. */
    protected fun trimDiskCache(maxCacheBytes: Long = MAX_CACHE_BYTES) {
        try {
            val files = diskCacheDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(CACHE_SUFFIX) }
                ?: return

            var total = files.sumOf { it.length() }
            if (total <= maxCacheBytes) return

            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= maxCacheBytes) break

                val len = f.length()
                if (f.delete()) {
                    total -= len
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo limpiar cache disco: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MangaLite"
        protected const val CACHE_SUFFIX = ".raw"
        protected const val MAX_CACHE_BYTES = 50L * 1024L * 1024L
    }
}
