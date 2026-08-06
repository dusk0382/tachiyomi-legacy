package net.spin.tachiyomi.legacy.kotatsu

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * [MangaLoaderContext] para los parsers de Kotatsu: usa el OkHttpClient y el
 * cookie jar de la app (NetworkHelper), QuickJS para la evaluacion de JS y
 * Bitmaps de Android para el descifrado de imagenes.
 */
class KotatsuLoaderContext(
    override val httpClient: OkHttpClient,
    override val cookieJar: CookieJar,
) : MangaLoaderContext() {

    private val configs = ConcurrentHashMap<String, MangaSourceConfig>()

    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
        evaluateWithQuickJs(script)

    @Suppress("DEPRECATION")
    override suspend fun evaluateJs(script: String): String? = evaluateWithQuickJs(script)

    private fun evaluateWithQuickJs(script: String): String? {
        val js = try {
            app.cash.quickjs.QuickJs.create()
        } catch (e: Throwable) {
            return null
        }
        return try {
            js.evaluate(script)?.toString()
        } catch (e: Throwable) {
            null
        } finally {
            try {
                js.close()
            } catch (e: Throwable) {
                // ignorar
            }
        }
    }

    override fun getConfig(source: MangaSource): MangaSourceConfig =
        configs.getOrPut(source.name) { DefaultSourceConfig() }

    override fun getDefaultUserAgent(): String = DEFAULT_USER_AGENT

    override fun createBitmap(width: Int, height: Int): Bitmap =
        AndroidBitmap(android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888))

    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
        val bytes = response.body?.bytes() ?: return response
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return response
        val redrawn = (redraw(AndroidBitmap(decoded)) as? AndroidBitmap)?.bmp ?: return response
        val out = ByteArrayOutputStream()
        redrawn.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return response.newBuilder()
            .body(out.toByteArray().toResponseBody(response.body?.contentType()))
            .build()
    }

    /** [Bitmap] de los parsers respaldado por un android.graphics.Bitmap. */
    private class AndroidBitmap(val bmp: android.graphics.Bitmap) : Bitmap {
        override val width: Int get() = bmp.width
        override val height: Int get() = bmp.height

        override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
            val srcBmp = (sourceBitmap as? AndroidBitmap)?.bmp ?: return
            val canvas = Canvas(bmp)
            canvas.drawBitmap(
                srcBmp,
                android.graphics.Rect(src.left, src.top, src.right, src.bottom),
                android.graphics.RectF(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    /** Devuelve siempre los valores por defecto de cada clave (dominio, UA, etc.). */
    private class DefaultSourceConfig : MangaSourceConfig {
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 6.0; ALCATEL ONE TOUCH POP 7 LTE) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.88 Mobile Safari/537.36"
    }
}
