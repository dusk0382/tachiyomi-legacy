package net.spin.tachiyomi.legacy

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LibraryCache {

    private const val PREFS_NAME = "library_cache"
    private const val KEY_MANGA_LIST = "manga_list"
    private const val KEY_LAST_SCAN = "last_scan_time"
    private const val SCAN_INTERVAL_MS = 300000L

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, 0)
        }
    }

    fun saveLibrary(mangas: List<MangaFile>) {
        val jsonArray = JSONArray()

        for (manga in mangas) {
            val obj = JSONObject()
            obj.put("path", manga.file.absolutePath)
            obj.put("title", manga.title)
            obj.put("lastModified", manga.file.lastModified())
            obj.put("size", manga.file.length())
            jsonArray.put(obj)
        }

        prefs?.edit()
            ?.putString(KEY_MANGA_LIST, jsonArray.toString())
            ?.putLong(KEY_LAST_SCAN, System.currentTimeMillis())
            ?.apply()
    }

    fun loadLibrary(): List<MangaFile>? {
        val json = prefs?.getString(KEY_MANGA_LIST, null) ?: return null

        return try {
            val jsonArray = JSONArray(json)
            val mangas = mutableListOf<MangaFile>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val path = obj.getString("path")
                val file = File(path)

                if (file.exists() && file.isFile && file.extension.equals("cbz", ignoreCase = true)) {
                    val title = obj.optString("title", file.nameWithoutExtension)
                    mangas.add(MangaFile(file, title))
                }
            }

            mangas
        } catch (e: Exception) {
            null
        }
    }

    fun needsRescan(): Boolean {
        val lastScan = prefs?.getLong(KEY_LAST_SCAN, 0L) ?: 0L
        return System.currentTimeMillis() - lastScan > SCAN_INTERVAL_MS
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
