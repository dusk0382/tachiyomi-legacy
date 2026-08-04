package net.spin.tachiyomi.legacy

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val semaphore = Semaphore(2)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeRequests = ConcurrentHashMap<Int, Long>()

    @Volatile
    private var highResPage: Int = -1

    @Volatile
    private var highResBitmap: Bitmap? = null

    private val highResSeq = AtomicLong(0)

    @Volatile
    private var prefetchJob: Job? = null

    @Volatile
    private var initializing = false

    private val _currentPage = MutableLiveData(0)
    val currentPage: LiveData<Int> = _currentPage

    private val _pageCount = MutableLiveData(0)
    val pageCount: LiveData<Int> = _pageCount

    @Volatile
    private var reader: CBZReader? = null

    private var mangaFilePath: String? = null

    private val cache = PageCache(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
            .coerceIn(8 * 1024 * 1024, 20 * 1024 * 1024)
    )

    private var screenWidth: Int = 1024
    private var screenHeight: Int = 600

    fun init(filePath: String, screenWidth: Int, screenHeight: Int) {
        this.mangaFilePath = filePath
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight

        if (reader != null || initializing) return

        initializing = true

        scope.launch {
            try {
                val file = java.io.File(filePath)
                val r = CBZReader(file, getApplication())
                val count = r.pageCount

                reader = r

                val lastPage = Prefs.getLastPage(filePath)
                Prefs.setTotalPages(filePath, count)

                val startPage = lastPage.coerceIn(0, (count - 1).coerceAtLeast(0))

                _pageCount.postValue(count)
                _currentPage.postValue(startPage)
            } catch (e: Exception) {
                Log.e("MangaLite", "Error init", e)
                _pageCount.postValue(0)
            } finally {
                initializing = false
            }
        }
    }

    fun loadPage(index: Int, requestId: Long, onResult: (Int, Bitmap?, Long) -> Unit) {
        val existingId = activeRequests[index]
        if (existingId != null && existingId > requestId) {
            Log.d("MangaLite", "Request $requestId cancelado")
            return
        }

        activeRequests[index] = requestId

        val cached = cache.get(index)
        if (cached != null) {
            Log.d("MangaLite", "Página $index desde CACHE")
            activeRequests.remove(index)
            onResult(index, cached, requestId)
            return
        }

        Log.d("MangaLite", "Página $index encolada")

        scope.launch {
            doLoadPage(index, requestId, onResult)
        }
    }

    private suspend fun doLoadPage(
        index: Int,
        requestId: Long,
        onResult: (Int, Bitmap?, Long) -> Unit
    ) {
        semaphore.withPermit {
            doLoadPageInternal(index, requestId, onResult)
        }
    }

    private suspend fun doLoadPageInternal(
        index: Int,
        requestId: Long,
        onResult: (Int, Bitmap?, Long) -> Unit
    ) {
        try {
            val currentId = activeRequests[index]
            if (currentId != requestId) {
                Log.d("MangaLite", "Request $requestId cancelado antes")
                return
            }

            val startTime = System.currentTimeMillis()
            val bmp = reader?.decodePage(index, screenWidth, screenHeight)
            val elapsed = System.currentTimeMillis() - startTime

            val currentId2 = activeRequests[index]
            if (currentId2 != requestId) {
                Log.d("MangaLite", "Request $requestId cancelado durante decode")
                return
            }

            if (bmp != null && !bmp.isRecycled) {
                cache.put(index, bmp)
                Log.d("MangaLite", "Página $index lista en ${elapsed}ms")
            } else {
                Log.w("MangaLite", "Página $index: null (${elapsed}ms)")
            }

            activeRequests.remove(index)

            withContext(Dispatchers.Main) {
                onResult(index, bmp, requestId)
            }
        } catch (e: OutOfMemoryError) {
            Log.e("MangaLite", "OOM en página $index", e)
            System.gc()
            cache.clear()
            activeRequests.remove(index)

            withContext(Dispatchers.Main) {
                onResult(index, null, requestId)
            }
        } catch (e: Exception) {
            Log.e("MangaLite", "Error página $index", e)
            activeRequests.remove(index)

            withContext(Dispatchers.Main) {
                onResult(index, null, requestId)
            }
        }
    }

    fun prefetchAround(center: Int) {
        val total = reader?.pageCount ?: return
        val toPrefetch = buildPrefetchList(center, total)

        if (toPrefetch.isEmpty()) return

        Log.d("MangaLite", "Prefetch: $toPrefetch")

        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            delay(120)
            doPrefetch(toPrefetch)
        }
    }

    private fun buildPrefetchList(center: Int, total: Int): List<Int> {
        val result = mutableListOf<Int>()

        val candidates = listOf(
            center + 1,
            center + 2
        )

        for (idx in candidates) {
            if (idx >= 0 && idx < total && !cache.contains(idx)) {
                result.add(idx)
            }
        }

        return result
    }

    private suspend fun doPrefetch(toPrefetch: List<Int>) {
        for (idx in toPrefetch) {
            if (!currentCoroutineContext().isActive) return

            if (cache.contains(idx)) {
                continue
            }

            semaphore.withPermit {
                if (!currentCoroutineContext().isActive) return@withPermit

                try {
                    val bmp = reader?.decodePage(idx, screenWidth, screenHeight)

                    if (bmp != null && !bmp.isRecycled) {
                        cache.put(idx, bmp)
                        Log.d("MangaLite", "Prefetch $idx OK")
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e("MangaLite", "OOM en prefetch $idx", e)
                    System.gc()
                    cache.clear()
                } catch (e: Exception) {
                    Log.e("MangaLite", "Error prefetch $idx", e)
                }
            }
        }
    }

    fun goTo(index: Int) {
        val max = (reader?.pageCount ?: 1) - 1
        val clamped = index.coerceIn(0, max)

        if (clamped == _currentPage.value) {
            return
        }

        _currentPage.value = clamped

        val path = mangaFilePath
        if (path != null) {
            Prefs.setLastPage(path, clamped)
        }

        prefetchAround(clamped)
    }

    fun clearCache() {
        cache.clear()
    }

    /**
     * Petición de alta resolución bajo demanda (solo cuando hay zoom).
     * [normalized] es el rectángulo visible en coordenadas 0..1 de la imagen.
     * La región se expande ligeramente para cubrir pequeños paneos.
     */
    fun requestHighResRegion(
        pageIndex: Int,
        normalized: RectF,
        viewportWidth: Int,
        viewportHeight: Int,
        onResult: (Bitmap?, RectF) -> Unit
    ) {
        val r = reader ?: return
        val seq = highResSeq.incrementAndGet()

        scope.launch {
            try {
                val bounds = r.getPageBounds(pageIndex)

                var bmp: Bitmap?
                var usedNorm: RectF

                if (bounds != null) {
                    val ow = bounds.first.coerceAtLeast(1)
                    val oh = bounds.second.coerceAtLeast(1)

                    usedNorm = expandNormalized(normalized)

                    val left = (usedNorm.left * ow).toInt().coerceIn(0, ow - 1)
                    val top = (usedNorm.top * oh).toInt().coerceIn(0, oh - 1)
                    val right = (usedNorm.right * ow).toInt().coerceIn(left + 1, ow)
                    val bottom = (usedNorm.bottom * oh).toInt().coerceIn(top + 1, oh)

                    bmp = r.decodeRegion(
                        pageIndex,
                        Rect(left, top, right, bottom),
                        viewportWidth,
                        viewportHeight
                    )
                } else {
                    bmp = r.decodeFullPageHighRes(pageIndex)
                    usedNorm = RectF(0f, 0f, 1f, 1f)
                }

                if (highResSeq.get() != seq) {
                    bmp?.takeIf { !it.isRecycled }?.recycle()
                    return@launch
                }

                if (bmp != null && !bmp.isRecycled) {
                    val old = highResBitmap
                    highResBitmap = bmp
                    highResPage = pageIndex
                    old?.takeIf { !it.isRecycled }?.recycle()
                } else {
                    bmp?.recycle()
                }

                val finalBmp = bmp?.takeIf { !it.isRecycled }
                withContext(Dispatchers.Main) {
                    onResult(finalBmp, usedNorm)
                }
            } catch (e: OutOfMemoryError) {
                Log.e("MangaLite", "OOM alta res página $pageIndex", e)
                System.gc()
                releaseHighRes()
                withContext(Dispatchers.Main) {
                    onResult(null, RectF(0f, 0f, 1f, 1f))
                }
            } catch (e: Exception) {
                Log.w("MangaLite", "Error alta res página $pageIndex", e)
                withContext(Dispatchers.Main) {
                    onResult(null, RectF(0f, 0f, 1f, 1f))
                }
            }
        }
    }

    /** Libera y recicla el bitmap de alta resolución actual. */
    fun releaseHighRes() {
        highResSeq.incrementAndGet()
        val old = highResBitmap
        highResBitmap = null
        highResPage = -1
        old?.takeIf { !it.isRecycled }?.recycle()
    }

    private fun expandNormalized(rect: RectF): RectF {
        val growX = rect.width() * 0.15f
        val growY = rect.height() * 0.15f

        val left = (rect.left - growX).coerceIn(0f, 1f)
        val top = (rect.top - growY).coerceIn(0f, 1f)
        val right = (rect.right + growX).coerceIn(0f, 1f)
        val bottom = (rect.bottom + growY).coerceIn(0f, 1f)

        return RectF(
            left,
            top,
            right.coerceAtLeast(left + 0.01f),
            bottom.coerceAtLeast(top + 0.01f)
        )
    }

    override fun onCleared() {
        super.onCleared()
        prefetchJob?.cancel()
        scope.cancel()
        cache.clear()
        releaseHighRes()

        try {
            reader?.close()
        } catch (e: Exception) {
            Log.w("MangaLite", "Error cerrando reader", e)
        }

        reader = null
    }
}
