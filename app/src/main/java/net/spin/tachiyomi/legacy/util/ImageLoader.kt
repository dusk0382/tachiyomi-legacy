package net.spin.tachiyomi.legacy.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import eu.kanade.tachiyomi.network.NetworkHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal image loader with an in-memory cache. Downloads happen on a
 * small fixed thread pool; callers must tolerate null results.
 */
object ImageLoader {

    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val inFlight = ConcurrentHashMap<String, MutableList<ImageView>>()

    private var network: NetworkHelper? = null

    fun init(network: NetworkHelper) {
        this.network = network
    }

    fun load(url: String?, imageView: ImageView, placeholder: Int = 0) {
        if (url.isNullOrBlank()) {
            if (placeholder != 0) imageView.setImageResource(placeholder)
            return
        }

        if (placeholder != 0) imageView.setImageResource(placeholder)

        cache[url]?.let {
            imageView.setImageBitmap(it)
            return
        }

        val waiters = inFlight.getOrPut(url) { mutableListOf() }
        waiters.add(imageView)

        // Only the first caller enqueues the fetch
        if (waiters.size > 1) return

        executor.execute {
            val bitmap = try {
                network?.let { net ->
                    val response = net.client.newCall(eu.kanade.tachiyomi.network.GET(url)).execute()
                    response.use { resp ->
                        if (!resp.isSuccessful) null
                        else BitmapFactory.decodeStream(resp.body.byteStream())
                    }
                }
            } catch (_: Exception) {
                null
            }

            if (bitmap != null) cache[url] = bitmap

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                inFlight.remove(url)?.forEach { target ->
                    if (bitmap != null) target.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun getCached(url: String?): Bitmap? = url?.let { cache[it] }

    /**
     * Descarga (o sirve desde cache) una imagen y entrega el resultado en
     * [onResult] desde el hilo principal. Util para callers que necesitan
     * saber cuándo termina (p. ej. para ocultar un ProgressBar).
     */
    fun load(url: String?, onResult: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) {
            onResult(null)
            return
        }

        cache[url]?.let {
            onResult(it)
            return
        }

        executor.execute {
            val bitmap = try {
                network?.let { net ->
                    val response = net.client.newCall(eu.kanade.tachiyomi.network.GET(url)).execute()
                    response.use { resp ->
                        if (!resp.isSuccessful) null
                        else BitmapFactory.decodeStream(resp.body.byteStream())
                    }
                }
            } catch (_: Exception) {
                null
            }

            if (bitmap != null) cache[url] = bitmap

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onResult(bitmap)
            }
        }
    }
}