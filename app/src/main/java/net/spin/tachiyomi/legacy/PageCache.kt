package net.spin.tachiyomi.legacy

import android.graphics.Bitmap
import android.util.LruCache

class PageCache(maxBytes: Int) {

    private val safeMax = if (maxBytes <= 0) 8 * 1024 * 1024 else maxBytes

    private val lru = object : LruCache<Int, Bitmap>(safeMax) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return if (value.isRecycled) 0 else value.byteCount
        }
    }

    fun get(key: Int): Bitmap? = synchronized(lru) {
        val bmp = lru.get(key)
        if (bmp != null && bmp.isRecycled) {
            lru.remove(key)
            null
        } else {
            bmp
        }
    }

    fun contains(key: Int): Boolean = synchronized(lru) {
        val bmp = lru.get(key)
        if (bmp != null && bmp.isRecycled) {
            lru.remove(key)
            false
        } else {
            bmp != null
        }
    }

    fun put(key: Int, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(lru) {
            lru.put(key, bitmap)
        }
    }

    fun clear() {
        synchronized(lru) {
            lru.evictAll()
        }
    }
}
