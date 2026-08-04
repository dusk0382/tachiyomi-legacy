package net.spin.tachiyomi.legacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

object ThumbnailManager {

    private const val THUMB_WIDTH = 180
    private const val THUMB_HEIGHT = 270
    private const val CACHE_DIR = "thumbs"
    private const val MAX_THUMB_CACHE_BYTES = 20L * 1024L * 1024L

    private var cacheDir: File? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "thumb-gen").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        }
    }

    fun getCachedThumb(file: File): File? {
        val dir = cacheDir ?: return null
        val thumb = File(dir, thumbName(file))
        return if (thumb.exists() && thumb.length() > 0) thumb else null
    }

    fun generateThumbAsync(
        file: File,
        onPageReady: (File?) -> Unit
    ) {
        val cached = getCachedThumb(file)
        if (cached != null) {
            onPageReady(cached)
            return
        }

        executor.execute {
            val result = try {
                generateThumbSync(file)
            } catch (e: Exception) {
                Log.e("MangaLite", "Error generando thumb para ${file.name}", e)
                null
            }
            onPageReady(result)
        }
    }

    private fun generateThumbSync(file: File): File? {
        if (!file.exists() || !file.canRead()) return null

        val dir = cacheDir ?: return null
        val thumbFile = File(dir, thumbName(file))

        if (thumbFile.exists() && thumbFile.length() > 0) {
            return thumbFile
        }

        val zipFile = try {
            ZipFile(file)
        } catch (e: Exception) {
            return null
        }

        return zipFile.use { zip ->
            val firstImage = zip.entries().toList()
                .filter { !it.isDirectory && it.name.isImageFile() }
                .sortedWith(Comparator { a, b -> NaturalSort.compare(a.name, b.name) })
                .firstOrNull() ?: return@use null

            val tmpFile = File(
                dir,
                "tmp_" + Thread.currentThread().id + "_" + System.currentTimeMillis() + ".img"
            )

            try {
                zip.getInputStream(firstImage).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val boundsOpts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                BitmapFactory.decodeFile(tmpFile.absolutePath, boundsOpts)

                if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                    tmpFile.delete()
                    return@use null
                }

                val sample = calculateInSampleSize(
                    boundsOpts.outWidth,
                    boundsOpts.outHeight,
                    THUMB_WIDTH,
                    THUMB_HEIGHT
                )

                val decodeOpts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inMutable = false
                }

                val bitmap = BitmapFactory.decodeFile(tmpFile.absolutePath, decodeOpts)
                tmpFile.delete()

                if (bitmap == null) {
                    return@use null
                }

                val cropped = centerCrop(bitmap, THUMB_WIDTH, THUMB_HEIGHT)

                try {
                    FileOutputStream(thumbFile).use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                    cropped.recycle()
                    trimCache()
                    thumbFile
                } catch (e: Exception) {
                    cropped.recycle()
                    thumbFile.delete()
                    null
                }
            } catch (e: Exception) {
                Log.e("MangaLite", "Error generando thumb", e)
                tmpFile.delete()
                null
            }
        }
    }

    private fun centerCrop(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = source.width
        val srcH = source.height

        if (srcW <= 0 || srcH <= 0) return source
        if (srcW == targetW && srcH == targetH) return source

        val scale = max(targetW.toFloat() / srcW, targetH.toFloat() / srcH)

        var scaledW = (srcW * scale).toInt()
        var scaledH = (srcH * scale).toInt()

        scaledW = scaledW.coerceAtLeast(targetW)
        scaledH = scaledH.coerceAtLeast(targetH)

        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        val x = ((scaled.width - targetW) / 2).coerceAtLeast(0)
        val y = ((scaled.height - targetH) / 2).coerceAtLeast(0)

        val w = min(targetW, scaled.width)
        val h = min(targetH, scaled.height)

        val cropped = Bitmap.createBitmap(scaled, x, y, w, h)

        if (cropped !== scaled) scaled.recycle()
        if (cropped !== source) source.recycle()

        return cropped
    }

    private fun trimCache() {
        val dir = cacheDir ?: return

        try {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".img") }
                ?.forEach { it.delete() }

            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".jpg") }
                ?: return

            var total = files.sumOf { it.length() }
            if (total <= MAX_THUMB_CACHE_BYTES) return

            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_THUMB_CACHE_BYTES) break

                val len = f.length()
                if (f.delete()) {
                    total -= len
                }
            }
        } catch (_: Exception) {
        }
    }

    fun clearThumb(file: File) {
        val dir = cacheDir ?: return
        try {
            File(dir, thumbName(file)).delete()
        } catch (_: Exception) {
        }
    }

    fun clearCache() {
        cacheDir?.listFiles()?.forEach { it.delete() }
    }

    private fun thumbName(file: File): String {
        val hash = file.absolutePath.hashCode().toUInt().toString(16) +
                "_" + file.lastModified().toString(16)
        return "$hash.jpg"
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

    private fun String.isImageFile(): Boolean {
        val l = this.lowercase()
        return l.endsWith(".jpg") || l.endsWith(".jpeg") ||
                l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".bmp")
    }
}
