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

    /**
     * Tamaño máximo de decodificación por defecto. Las portadas se muestran
     * a ~100-200px en la rejilla, asi que decodificar a 512px sobra de sobra
     * y evita cargar bitmaps de 6MB+ por portada (RAM de 1GB + Snapdragon 210).
     */
    private const val DEFAULT_MAX_EDGE = 512

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

    /** La cache se keyea por URL+tamaño: rejilla (384) y detalle (512) no se mezclan. */
    private fun cacheKey(url: String, maxEdge: Int) = "$url@$maxEdge"

    private fun request(url: String): Request {
        val builder = Request.Builder().url(url)
            .header("User-Agent", KotatsuLoaderContext.DEFAULT_USER_AGENT)
        runCatching {
            val host = java.net.URI(url).host
            if (!host.isNullOrBlank()) builder.header("Referer", "https://$host/")
        }
        return builder.build()
    }

    private fun fetch(url: String, maxEdge: Int): Bitmap? = try {
        network?.let { net ->
            val response = net.client.newCall(request(url)).execute()
            response.use { resp ->
                if (!resp.isSuccessful) null
                else resp.body.byteStream().use { stream ->
                    decodeSampled(stream.readBytes(), maxEdge)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Decodifica con [inSampleSize] para que el bitmap resultante no supere
     * [maxEdge] px por lado: las portadas de las fuentes llegan a 800-2000px
     * y se muestran a ~185px en la rejilla; decodificarlas enteras ocupaba
     * MBs de RAM cada una y hacía el scroll lento (Snapdragon 210 + 1GB RAM).
     */
    private fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdge &&
            bounds.outHeight / (sample * 2) >= maxEdge
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun load(url: String?, imageView: ImageView, placeholder: Int = 0, maxEdgePx: Int = DEFAULT_MAX_EDGE) {
        // Marcar qué URL espera esta vista AHORA: si el RecyclerView la recicla
        // para otro manga mientras descargamos, el tag cambia y al completar se
        // descarta el bitmap viejo (sin esto las portadas "saltaban" de arriba
        // a abajo al scrollear).
        imageView.tag = url

        if (url.isNullOrBlank()) {
            if (placeholder != 0) imageView.setImageResource(placeholder) else imageView.setImageDrawable(null)
            return
        }

        val key = cacheKey(url, maxEdgePx)
        cache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }

        // Limpiar de inmediato: nunca mostrar la portada del ítem anterior
        // mientras llega la nueva (el fondo gris del layout hace de placeholder).
        if (placeholder != 0) imageView.setImageResource(placeholder) else imageView.setImageDrawable(null)

        val waiters = inFlight.getOrPut(key) { mutableListOf() }
        waiters.add(imageView)

        // Only the first caller enqueues the fetch
        if (waiters.size > 1) return

        executor.execute {
            val bitmap = fetch(url, maxEdgePx)

            if (bitmap != null) cache.put(key, bitmap)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                inFlight.remove(key)?.forEach { target ->
                    // Solo aplicar el bitmap si esta vista sigue esperando ESTA url.
                    if (bitmap != null && target.tag == url) {
                        target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    fun getCached(url: String?, maxEdgePx: Int = DEFAULT_MAX_EDGE): Bitmap? =
        url?.let { cache.get(cacheKey(it, maxEdgePx)) }

    /**
     * Descarga (o sirve desde cache) una imagen y entrega el resultado en
     * [onResult] desde el hilo principal. Util para callers que necesitan
     * saber cuándo termina (p. ej. para ocultar un ProgressBar).
     */
    // maxEdgePx va ANTES del lambda: Kotlin enlaza el lambda final al ultimo
    // parametro, y si el ultimo fuera Int fallaria la llamada loadBitmap(url) {}.
    fun loadBitmap(url: String?, maxEdgePx: Int = DEFAULT_MAX_EDGE, onResult: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) {
            onResult(null)
            return
        }

        val key = cacheKey(url, maxEdgePx)
        cache.get(key)?.let {
            onResult(it)
            return
        }

        executor.execute {
            val bitmap = fetch(url, maxEdgePx)

            if (bitmap != null) cache.put(key, bitmap)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onResult(bitmap)
            }
        }
    }
}