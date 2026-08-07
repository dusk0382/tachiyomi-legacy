package net.spin.tachiyomi.legacy.kotatsu

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Espacia las peticiones al mismo host (600 ms por defecto) para no tocar los
 * rate-limits agresivos de APIs como la de MangaFire. Las imagenes de CDN
 * (hosts distintos) no se ven afectadas y pueden descargarse en paralelo.
 */
class KotatsuRateLimitInterceptor(
    private val minIntervalMs: Long = 600L,
) : Interceptor {

    private val lastCallAt = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val now = System.currentTimeMillis()
        val wait = lastCallAt[host]?.let { last ->
            (minIntervalMs - (now - last)).coerceAtLeast(0L)
        } ?: 0L
        if (wait > 0) {
            Thread.sleep(wait)
        }
        lastCallAt[host] = System.currentTimeMillis()
        return chain.proceed(chain.request())
    }
}
