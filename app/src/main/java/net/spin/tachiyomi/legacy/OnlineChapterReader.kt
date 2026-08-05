package net.spin.tachiyomi.legacy

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads an online chapter: resolves each page's image URL through the source
 * (respecting its headers/cookies) and downloads the image to the disk cache
 * on first access. Subsequent reads of the same chapter hit the cache, so
 * re-opening is fast even offline.
 */
class OnlineChapterReader(
    context: Context,
    private val source: Source,
    private val chapter: SChapter,
    private val pages: List<Page>,
) : FileBackedPageReader() {

    override val diskCacheDir: File = File(context.cacheDir, "manga_cache").apply { mkdirs() }

    private val httpSource: HttpSource? = source as? HttpSource
    private val cachePrefix = "${source.id}_${chapter.url.hashCode()}_"

    /** Per-page lock to avoid downloading the same page twice concurrently. */
    private val pageLocks = ConcurrentHashMap<Int, Mutex>()

    override val pageCount: Int get() = pages.size

    override suspend fun getCachedPageFile(pageIndex: Int): File? {
        if (pageIndex !in 0 until pages.size) return null

        val page = pages[pageIndex]
        val cacheFile = File(diskCacheDir, "$cachePrefix$pageIndex$CACHE_SUFFIX")

        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        val mutex = pageLocks.getOrPut(pageIndex) { Mutex() }
        return mutex.withLock {
            // Re-check inside the lock: another coroutine may have finished.
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return@withLock cacheFile
            }
            downloadPage(page, pageIndex, cacheFile)
        }
    }

    private suspend fun downloadPage(page: Page, pageIndex: Int, cacheFile: File): File? {
        return try {
            val url = page.imageUrl?.takeIf { it.isNotBlank() } ?: httpSource?.getImageUrl(page)
            if (url.isNullOrBlank()) {
                Log.w(TAG, "Página $pageIndex sin URL de imagen")
                return null
            }
            if (page.imageUrl.isNullOrBlank()) {
                page.imageUrl = url
            }

            val response = httpSource?.getImage(page, existingSize = 0L) ?: return null
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Descarga página $pageIndex falló: HTTP ${resp.code}")
                    return@use null
                }
                resp.body.byteStream().use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (cacheFile.exists() && cacheFile.length() > 0) {
                trimDiskCache()
                cacheFile
            } else {
                cacheFile.delete()
                null
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM descargando página $pageIndex", e)
            System.gc()
            cacheFile.delete()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error descargando página $pageIndex: ${e.message}")
            cacheFile.delete()
            null
        }
    }

    override fun close() {
        // No resources held between calls; pages stay in the disk cache.
    }

    private companion object {
        const val TAG = "MangaLite"
    }
}
