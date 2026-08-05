package net.spin.tachiyomi.legacy

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Common surface for chapter readers: local CBZ archives and online chapters.
 * All methods are suspend because online readers need network access; the
 * ReaderViewModel always calls them from its IO coroutine scope.
 */
interface PageReader : AutoCloseable {

    val pageCount: Int

    /** Decodes the page fitted to the given screen size, using RGB_565. */
    suspend fun decodePage(pageIndex: Int, screenWidth: Int, screenHeight: Int): Bitmap?

    /** Original dimensions of the page without decoding it fully. */
    suspend fun getPageBounds(pageIndex: Int): Pair<Int, Int>?

    /**
     * Decodes only the visible region (in original pixel coordinates).
     * Falls back to the full high-res page when region decoding fails.
     */
    suspend fun decodeRegion(
        pageIndex: Int,
        region: Rect,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap?

    /** Decodes the full page at high resolution (largest side <= [maxEdge]). */
    suspend fun decodeFullPageHighRes(pageIndex: Int, maxEdge: Int = 2048): Bitmap?
}
