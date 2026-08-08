package net.spin.tachiyomi.legacy

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "mangalite_prefs"
    private var _prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (_prefs == null) {
            _prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    val prefs: SharedPreferences get() = _prefs!!

    fun getLastPage(path: String): Int = prefs.getInt("progress_$path", 0)
    fun setLastPage(path: String, page: Int) {
        prefs.edit().putInt("progress_$path", page).apply()
    }

    fun getTotalPages(path: String): Int = prefs.getInt("total_$path", 0)
    fun setTotalPages(path: String, pages: Int) {
        prefs.edit().putInt("total_$path", pages).apply()
    }

    fun isPageCounted(path: String): Boolean = prefs.getBoolean("counted_$path", false)
    fun setPageCounted(path: String, counted: Boolean) {
        prefs.edit().putBoolean("counted_$path", counted).apply()
    }

    fun getSortMode(): Int = prefs.getInt("sort_mode", 0)
    fun setSortMode(mode: Int) {
        prefs.edit().putInt("sort_mode", mode).apply()
    }

    // Dirección de lectura del pager: 0 = LTR, 1 = RTL
    fun getReadingDirection(): Int = prefs.getInt("reading_direction", DIRECTION_LTR)
    fun setReadingDirection(direction: Int) {
        prefs.edit().putInt("reading_direction", direction).apply()
    }

    const val DIRECTION_LTR = 0
    const val DIRECTION_RTL = 1

    // Almacenamiento de descargas: interno (publico) o tarjeta SD (app-specific).
    const val STORAGE_INTERNAL = "internal"
    const val STORAGE_SD = "sd"

    fun getDownloadStorage(): String =
        prefs.getString("download_storage", STORAGE_INTERNAL) ?: STORAGE_INTERNAL

    fun setDownloadStorage(value: String) {
        prefs.edit().putString("download_storage", value).apply()
    }
}
