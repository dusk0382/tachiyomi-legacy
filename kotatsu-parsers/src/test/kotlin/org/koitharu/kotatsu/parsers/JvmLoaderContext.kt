package org.koitharu.kotatsu.parsers

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * [MangaLoaderContext] JVM puro para el harness de verificacion: mismo
 * contrato que el loader de Android (cookies compartidas entre el parser y la
 * descarga de imagenes), pero sin QuickJS (evaluateJs -> null) ni Bitmaps de
 * Android (se usa BufferedImage).
 */
class JvmLoaderContext : MangaLoaderContext() {

    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override val cookieJar: CookieJar get() = httpClient.cookieJar

    override suspend fun evaluateJs(script: String): String? = null

    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null

    private val configs = ConcurrentHashMap<String, MangaSourceConfig>()

    override fun getConfig(source: MangaSource): MangaSourceConfig =
        configs.getOrPut(source.name) { DefaultSourceConfig() }

    override fun getDefaultUserAgent(): String = DEFAULT_USER_AGENT

    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
        val bytes = response.body?.bytes() ?: return response
        val decoded = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        } ?: return response
        val redrawn = (redraw(JvmBitmap(decoded)) as? JvmBitmap)?.image ?: return response
        val out = ByteArrayOutputStream()
        ImageIO.write(redrawn, "png", out)
        return response.newBuilder()
            .body(out.toByteArray().toResponseBody(response.body?.contentType()))
            .build()
    }

    override fun createBitmap(width: Int, height: Int): Bitmap =
        JvmBitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

    private class JvmBitmap(val image: BufferedImage) : Bitmap {
        override val width: Int get() = image.width
        override val height: Int get() = image.height

        override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
            val srcImage = (sourceBitmap as? JvmBitmap)?.image ?: return
            val g = image.createGraphics()
            g.drawImage(
                srcImage,
                dst.left, dst.top, dst.right, dst.bottom,
                src.left, src.top, src.right, src.bottom,
                null,
            )
            g.dispose()
        }
    }

    private class DefaultSourceConfig : MangaSourceConfig {
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }

    private class MemoryCookieJar : CookieJar {
        private val map = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            map.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = map[url.host] ?: emptyList()
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 6.0; ALCATEL ONE TOUCH POP 7 LTE) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.88 Mobile Safari/537.36"
    }
}
