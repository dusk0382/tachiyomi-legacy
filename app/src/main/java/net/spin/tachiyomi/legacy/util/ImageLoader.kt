package net.spin.tachiyomi.legacy.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import eu.kanade.tachiyomi.network.NetworkHelper
import net.spin.tachiyomi.legacy.kotatsu.KotatsuLoaderContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal image loader with an in-memory cache. Downloads happen on a
 * small fixed thread pool; callers must tolerate null results.
 *
 * Envia siempre el User-Agent de Kotatsu y un Referer del propio dominio de
 * la imagen: muchos CDNs/Cloudflare rechazan peticiones sin esos headers
 * (el sintoma es "la portada no carga" aunque el manga si).
 */
object ImageLoader {

    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    /**
     * Cache LRU limitado a ~1/8 de la memoria del proceso (en la tablet son
     * ~1GB de RAM): con el scroll infinito del catálogo, un ConcurrentHashMap
     * sin límite llenaba la memoria con cientos de portadas y la app moría.
     */
    private val cache = object : android.util.LruCache<String, Bitmap>(cacheBudgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val inFlight = ConcurrentHashMap<String, MutableList<ImageView>>()

    private var network: NetworkHelper? = null

    fun init(network: NetworkHelper) {
        this.network = network
    }

    private fun cacheBudgetBytes(): Int {
        val maxMem = Runtime.getRuntime().maxMemory()
        return (maxMem / 8).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt()
    }

    private fun request(url: String): Request {
        val builder = Request.Builder().url(url)
            .header("User-Agent", KotatsuLoaderContext.DEFAULT_USER_AGENT)
        runCatching {
            val host = java.net.URI(url).host
            if (!host.isNullOrBlank()) builder.header("Referer", "https://$host/")
        }
        return builder.build()
    }

    private fun fetch(url: String): Bitmap? = try {
        network?.let { net ->
            val response = net.client.newCall(request(url)).execute()
            response.use { resp ->
                if (!resp.isSuccessful) null
                else BitmapFactory.decodeStream(resp.body.byteStream())
            }
        }
    } catch (_: Exception) {
        null
    }

    fun load(url: String?, imageView: ImageView, placeholder: Int = 0) {
        if (url.isNullOrBlank()) {
            if (placeholder != 0) imageView.setImageResource(placeholder)
            return
        }

        if (placeholder != 0) imageView.setImageResource(placeholder)

        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }

        val waiters = inFlight.getOrPut(url) { mutableListOf() }
        waiters.add(imageView)

        // Only the first caller enqueues the fetch
        if (waiters.size > 1) return

        executor.execute {
            val bitmap = fetch(url)

            if (bitmap != null) cache.put(url, bitmap)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                inFlight.remove(url)?.forEach { target ->
                    if (bitmap != null) target.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun getCached(url: String?): Bitmap? = url?.let { cache.get(it) }

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

        cache.get(url)?.let {
            onResult(it)
            return
        }

        executor.execute {
            val bitmap = fetch(url)

            if (bitmap != null) cache.put(url, bitmap)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onResult(bitmap)
            }
        }
    }
}