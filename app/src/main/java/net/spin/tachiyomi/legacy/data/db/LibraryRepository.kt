package net.spin.tachiyomi.legacy.data.db

import android.content.Context
import android.database.Cursor
import net.spin.tachiyomi.legacy.data.model.ChapterRef
import net.spin.tachiyomi.legacy.data.model.HistoryRef
import net.spin.tachiyomi.legacy.data.model.MangaRef
import net.spin.tachiyomi.legacy.data.model.PrivateRef
import net.spin.tachiyomi.legacy.data.model.ProgressRef
import net.spin.tachiyomi.legacy.data.model.SourceRef

/**
 * Repository over [AppDatabase] for favorites, chapters and reader progress.
 * All methods are synchronous (small data sets, called from view models on IO).
 */
class LibraryRepository(context: Context) {

    private val db = AppDatabase(context.applicationContext)

    // --- Favorites ---

    fun addFavorite(manga: MangaRef): Boolean {
        val values = FavoritesTable.toContentValues(manga.sourceId, manga.url, manga.title).apply {
            manga.author?.let { put(FavoritesTable.KEY_AUTHOR, it) }
            manga.artist?.let { put(FavoritesTable.KEY_ARTIST, it) }
            manga.thumbnailUrl?.let { put(FavoritesTable.KEY_THUMBNAIL, it) }
            manga.description?.let { put(FavoritesTable.KEY_DESCRIPTION, it) }
            manga.genre?.let { put(FavoritesTable.KEY_GENRE, it) }
            put(FavoritesTable.KEY_STATUS, manga.status)
        }
        return try {
            db.writableDatabase.insertWithOnConflict(
                AppDatabase.TBL_FAVORITES,
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        } catch (_: Exception) {
            false
        }
    }

    fun isFavorite(sourceId: Long, url: String): Boolean {
        return db.readableDatabase.rawQuery(
            "SELECT 1 FROM ${AppDatabase.TBL_FAVORITES} WHERE ${FavoritesTable.KEY_SOURCE_ID}=? AND ${FavoritesTable.KEY_URL}=? LIMIT 1",
            arrayOf(sourceId.toString(), url),
        ).use { it.moveToFirst() }
    }

    fun removeFavorite(sourceId: Long, url: String): Boolean {
        return db.writableDatabase.delete(
            AppDatabase.TBL_FAVORITES,
            "${FavoritesTable.KEY_SOURCE_ID}=? AND ${FavoritesTable.KEY_URL}=?",
            arrayOf(sourceId.toString(), url),
        ) > 0
    }

    fun getFavorites(): List<MangaRef> {
        return db.readableDatabase.query(
            AppDatabase.TBL_FAVORITES,
            null,
            null,
            null,
            null,
            null,
            "${FavoritesTable.KEY_DATE_ADDED} DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toManga())
                }
            }
        }
    }

    fun getFavorite(sourceId: Long, url: String): MangaRef? {
        return db.readableDatabase.query(
            AppDatabase.TBL_FAVORITES,
            null,
            "${FavoritesTable.KEY_SOURCE_ID}=? AND ${FavoritesTable.KEY_URL}=?",
            arrayOf(sourceId.toString(), url),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toManga() else null
        }
    }

    // --- Chapters ---

    fun upsertChapters(chapters: List<ChapterRef>) {
        val wdb = db.writableDatabase
        wdb.beginTransaction()
        try {
            chapters.forEach { chapter ->
                val values = ChaptersTable.toContentValues(
                    chapter.sourceId,
                    chapter.mangaUrl,
                    chapter.url,
                    chapter.name,
                ).apply {
                    chapter.scanlator?.let { put(ChaptersTable.KEY_SCANLATOR, it) }
                    put(ChaptersTable.KEY_CHAPTER_NUMBER, chapter.chapterNumber)
                    put(ChaptersTable.KEY_READ, if (chapter.read) 1 else 0)
                    put(ChaptersTable.KEY_UPLOAD_DATE, chapter.uploadDate)
                }
                wdb.insertWithOnConflict(
                    AppDatabase.TBL_CHAPTERS,
                    null,
                    values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            wdb.setTransactionSuccessful()
        } finally {
            wdb.endTransaction()
        }
    }

    fun getChapters(sourceId: Long, mangaUrl: String): List<ChapterRef> {
        return db.readableDatabase.query(
            AppDatabase.TBL_CHAPTERS,
            null,
            "${ChaptersTable.KEY_SOURCE_ID}=? AND ${ChaptersTable.KEY_MANGA_URL}=?",
            arrayOf(sourceId.toString(), mangaUrl),
            null,
            null,
            "${ChaptersTable.KEY_CHAPTER_NUMBER} DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val read = cursor.getInt(cursor.getColumnIndexOrThrow(ChaptersTable.KEY_READ)) == 1
                    add(
                        ChapterRef(
                            sourceId = cursor.getLong(cursor.getColumnIndexOrThrow(ChaptersTable.KEY_SOURCE_ID)),
                            mangaUrl = cursor.getString(cursor.getColumnIndexOrThrow(ChaptersTable.KEY_MANGA_URL)),
                            url = cursor.getString(cursor.getColumnIndexOrThrow(ChaptersTable.KEY_URL)),
                            name = cursor.getString(cursor.getColumnIndexOrThrow(ChaptersTable.KEY_NAME)),
                            scanlator = cursor.getStringOrNull(ChaptersTable.KEY_SCANLATOR),
                            chapterNumber = cursor.getDouble(cursor.getColumnIndexOrThrow(ChaptersTable.KEY_CHAPTER_NUMBER)),
                            read = read,
                            uploadDate = cursor.getLongOrNull(ChaptersTable.KEY_UPLOAD_DATE) ?: 0L,
                        ),
                    )
                }
            }
        }
    }

    fun setChapterRead(sourceId: Long, mangaUrl: String, chapterUrl: String, read: Boolean) {
        val values = android.content.ContentValues().apply {
            put(ChaptersTable.KEY_READ, if (read) 1 else 0)
        }
        db.writableDatabase.update(
            AppDatabase.TBL_CHAPTERS,
            values,
            "${ChaptersTable.KEY_SOURCE_ID}=? AND ${ChaptersTable.KEY_MANGA_URL}=? AND ${ChaptersTable.KEY_URL}=?",
            arrayOf(sourceId.toString(), mangaUrl, chapterUrl),
        )
    }

    // --- History ---

    /** Guarda (o actualiza la fecha) de un manga online visto recientemente. */
    fun upsertHistory(history: HistoryRef) {
        val values = HistoryTable.toContentValues(
            history.sourceId,
            history.url,
            history.title,
            history.thumbnailUrl,
            history.lastChapterUrl,
            history.lastChapterName,
            history.lastPageIndex,
            history.lastTotalPages,
        )
        db.writableDatabase.insertWithOnConflict(
            AppDatabase.TBL_HISTORY,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getHistoryEntry(sourceId: Long, url: String): HistoryRef? {
        return db.readableDatabase.query(
            AppDatabase.TBL_HISTORY,
            null,
            "${HistoryTable.KEY_SOURCE_ID}=? AND ${HistoryTable.KEY_URL}=?",
            arrayOf(sourceId.toString(), url),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                HistoryRef(
                    sourceId = cursor.getLong(cursor.getColumnIndexOrThrow(HistoryTable.KEY_SOURCE_ID)),
                    url = cursor.getString(cursor.getColumnIndexOrThrow(HistoryTable.KEY_URL)),
                    title = cursor.getString(cursor.getColumnIndexOrThrow(HistoryTable.KEY_TITLE)),
                    thumbnailUrl = cursor.getStringOrNull(HistoryTable.KEY_THUMBNAIL_URL),
                    lastReadAt = cursor.getLong(cursor.getColumnIndexOrThrow(HistoryTable.KEY_LAST_READ_AT)),
                    lastChapterUrl = cursor.getStringOrNull(HistoryTable.KEY_LAST_CHAPTER_URL),
                    lastChapterName = cursor.getStringOrNull(HistoryTable.KEY_LAST_CHAPTER_NAME),
                    lastPageIndex = cursor.getLongOrNull(HistoryTable.KEY_LAST_PAGE_INDEX)?.toInt() ?: 0,
                    lastTotalPages = cursor.getLongOrNull(HistoryTable.KEY_LAST_TOTAL_PAGES)?.toInt() ?: 0,
                )
            } else {
                null
            }
        }
    }

    /**
     * Actualiza el progreso del ultimo capitulo leido del manga en el historial
     * (sin tocar title/thumbnail de la fila existente). No hace nada si el manga
     * no esta en el historial.
     */
    fun updateHistoryProgress(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
        chapterName: String?,
        pageIndex: Int,
        totalPages: Int,
    ) {
        val values = android.content.ContentValues().apply {
            put(HistoryTable.KEY_LAST_READ_AT, System.currentTimeMillis())
            put(HistoryTable.KEY_LAST_CHAPTER_URL, chapterUrl)
            chapterName?.let { put(HistoryTable.KEY_LAST_CHAPTER_NAME, it) }
            put(HistoryTable.KEY_LAST_PAGE_INDEX, pageIndex)
            put(HistoryTable.KEY_LAST_TOTAL_PAGES, totalPages)
        }
        db.writableDatabase.update(
            AppDatabase.TBL_HISTORY,
            values,
            "${HistoryTable.KEY_SOURCE_ID}=? AND ${HistoryTable.KEY_URL}=?",
            arrayOf(sourceId.toString(), mangaUrl),
        )
    }

    fun getHistory(): List<HistoryRef> {
        return db.readableDatabase.query(
            AppDatabase.TBL_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "${HistoryTable.KEY_LAST_READ_AT} DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HistoryRef(
                            sourceId = cursor.getLong(cursor.getColumnIndexOrThrow(HistoryTable.KEY_SOURCE_ID)),
                            url = cursor.getString(cursor.getColumnIndexOrThrow(HistoryTable.KEY_URL)),
                            title = cursor.getString(cursor.getColumnIndexOrThrow(HistoryTable.KEY_TITLE)),
                            thumbnailUrl = cursor.getStringOrNull(HistoryTable.KEY_THUMBNAIL_URL),
                            lastReadAt = cursor.getLong(cursor.getColumnIndexOrThrow(HistoryTable.KEY_LAST_READ_AT)),
                            lastChapterUrl = cursor.getStringOrNull(HistoryTable.KEY_LAST_CHAPTER_URL),
                            lastChapterName = cursor.getStringOrNull(HistoryTable.KEY_LAST_CHAPTER_NAME),
                            lastPageIndex = cursor.getLongOrNull(HistoryTable.KEY_LAST_PAGE_INDEX)?.toInt() ?: 0,
                            lastTotalPages = cursor.getLongOrNull(HistoryTable.KEY_LAST_TOTAL_PAGES)?.toInt() ?: 0,
                        ),
                    )
                }
            }
        }
    }

    fun removeHistory(sourceId: Long, url: String): Boolean {
        return db.writableDatabase.delete(
            AppDatabase.TBL_HISTORY,
            "${HistoryTable.KEY_SOURCE_ID}=? AND ${HistoryTable.KEY_URL}=?",
            arrayOf(sourceId.toString(), url),
        ) > 0
    }

    fun clearHistory() {
        db.writableDatabase.delete(AppDatabase.TBL_HISTORY, null, null)
    }

    // --- Carpeta privada (mangos online) ---

    fun addPrivateOnline(manga: PrivateRef): Boolean {
        val values = PrivateMangaTable.toContentValues(manga.sourceId, manga.url, manga.title).apply {
            manga.thumbnailUrl?.let { put(PrivateMangaTable.KEY_THUMBNAIL_URL, it) }
        }
        return try {
            db.writableDatabase.insertWithOnConflict(
                AppDatabase.TBL_PRIVATE_MANGA,
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        } catch (_: Exception) {
            false
        }
    }

    fun removePrivateOnline(sourceId: Long, url: String): Boolean {
        return db.writableDatabase.delete(
            AppDatabase.TBL_PRIVATE_MANGA,
            "${PrivateMangaTable.KEY_SOURCE_ID}=? AND ${PrivateMangaTable.KEY_URL}=?",
            arrayOf(sourceId.toString(), url),
        ) > 0
    }

    fun isPrivateOnline(sourceId: Long, url: String): Boolean {
        return db.readableDatabase.rawQuery(
            "SELECT 1 FROM ${AppDatabase.TBL_PRIVATE_MANGA} " +
                "WHERE ${PrivateMangaTable.KEY_SOURCE_ID}=? AND ${PrivateMangaTable.KEY_URL}=? LIMIT 1",
            arrayOf(sourceId.toString(), url),
        ).use { it.moveToFirst() }
    }

    fun getPrivateOnline(): List<PrivateRef> {
        return db.readableDatabase.query(
            AppDatabase.TBL_PRIVATE_MANGA,
            null,
            null,
            null,
            null,
            null,
            "${PrivateMangaTable.KEY_DATE_ADDED} DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PrivateRef(
                            sourceId = cursor.getLong(cursor.getColumnIndexOrThrow(PrivateMangaTable.KEY_SOURCE_ID)),
                            url = cursor.getString(cursor.getColumnIndexOrThrow(PrivateMangaTable.KEY_URL)),
                            title = cursor.getString(cursor.getColumnIndexOrThrow(PrivateMangaTable.KEY_TITLE)),
                            thumbnailUrl = cursor.getStringOrNull(PrivateMangaTable.KEY_THUMBNAIL_URL),
                            dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(PrivateMangaTable.KEY_DATE_ADDED)),
                        ),
                    )
                }
            }
        }
    }

    // --- Progress ---

    fun saveProgress(progress: ProgressRef) {
        val values = android.content.ContentValues().apply {
            put(ProgressTable.KEY_SOURCE_ID, progress.sourceId)
            put(ProgressTable.KEY_MANGA_URL, progress.mangaUrl)
            put(ProgressTable.KEY_CHAPTER_URL, progress.chapterUrl)
            put(ProgressTable.KEY_PAGE_INDEX, progress.pageIndex)
            put(ProgressTable.KEY_TOTAL_PAGES, progress.totalPages)
            put(ProgressTable.KEY_UPDATED_AT, progress.updatedAt)
        }
        db.writableDatabase.insertWithOnConflict(
            AppDatabase.TBL_PROGRESS,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getProgress(sourceId: Long, mangaUrl: String, chapterUrl: String): ProgressRef? {
        return db.readableDatabase.query(
            AppDatabase.TBL_PROGRESS,
            null,
            "${ProgressTable.KEY_SOURCE_ID}=? AND ${ProgressTable.KEY_MANGA_URL}=? AND ${ProgressTable.KEY_CHAPTER_URL}=?",
            arrayOf(sourceId.toString(), mangaUrl, chapterUrl),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                ProgressRef(
                    sourceId = cursor.getLong(cursor.getColumnIndexOrThrow(ProgressTable.KEY_SOURCE_ID)),
                    mangaUrl = cursor.getString(cursor.getColumnIndexOrThrow(ProgressTable.KEY_MANGA_URL)),
                    chapterUrl = cursor.getString(cursor.getColumnIndexOrThrow(ProgressTable.KEY_CHAPTER_URL)),
                    pageIndex = cursor.getInt(cursor.getColumnIndexOrThrow(ProgressTable.KEY_PAGE_INDEX)),
                    totalPages = cursor.getInt(cursor.getColumnIndexOrThrow(ProgressTable.KEY_TOTAL_PAGES)),
                )
            } else {
                null
            }
        }
    }

    // --- Sources ---

    fun upsertSource(source: SourceRef) {
        val values = android.content.ContentValues().apply {
            put(SourcesTable.KEY_ID, source.id)
            put(SourcesTable.KEY_NAME, source.name)
            put(SourcesTable.KEY_LANG, source.lang)
            source.baseUrl?.let { put(SourcesTable.KEY_BASE_URL, it) }
        }
        db.writableDatabase.insertWithOnConflict(
            AppDatabase.TBL_SOURCES,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getSources(): List<SourceRef> {
        return db.readableDatabase.query(
            AppDatabase.TBL_SOURCES,
            null,
            null,
            null,
            null,
            null,
            "${SourcesTable.KEY_NAME} ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SourceRef(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow(SourcesTable.KEY_ID)),
                            name = cursor.getString(cursor.getColumnIndexOrThrow(SourcesTable.KEY_NAME)),
                            lang = cursor.getString(cursor.getColumnIndexOrThrow(SourcesTable.KEY_LANG)),
                            baseUrl = cursor.getStringOrNull(SourcesTable.KEY_BASE_URL),
                        ),
                    )
                }
            }
        }
    }

    // --- Cursor helpers ---

    private fun Cursor.toManga(): MangaRef {
        return MangaRef(
            sourceId = getLong(getColumnIndexOrThrow(FavoritesTable.KEY_SOURCE_ID)),
            url = getString(getColumnIndexOrThrow(FavoritesTable.KEY_URL)),
            title = getString(getColumnIndexOrThrow(FavoritesTable.KEY_TITLE)),
            author = getStringOrNull(FavoritesTable.KEY_AUTHOR),
            artist = getStringOrNull(FavoritesTable.KEY_ARTIST),
            thumbnailUrl = getStringOrNull(FavoritesTable.KEY_THUMBNAIL),
            description = getStringOrNull(FavoritesTable.KEY_DESCRIPTION),
            genre = getStringOrNull(FavoritesTable.KEY_GENRE),
            status = getInt(getColumnIndexOrThrow(FavoritesTable.KEY_STATUS)),
            dateAdded = getLong(getColumnIndexOrThrow(FavoritesTable.KEY_DATE_ADDED)),
            lastReadAt = getLongOrNull(FavoritesTable.KEY_LAST_READ_AT),
        )
    }

    private fun Cursor.getStringOrNull(col: String): String? {
        val idx = getColumnIndex(col)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private fun Cursor.getLongOrNull(col: String): Long? {
        val idx = getColumnIndex(col)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }
}