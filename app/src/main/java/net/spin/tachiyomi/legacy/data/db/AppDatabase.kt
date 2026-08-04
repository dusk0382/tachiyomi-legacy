package net.spin.tachiyomi.legacy.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Native SQLite storage for online/library features. Uses the platform SQLite
 * (SQLiteOpenHelper) to avoid the bundled driver that caused CANTOPEN issues
 * on the Alcatel tablet.
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_FAVORITES)
        db.execSQL(CREATE_CHAPTERS)
        db.execSQL(CREATE_PROGRESS)
        db.execSQL(CREATE_SOURCES)
        db.execSQL(CREATE_DOWNLOADS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No migrations needed yet; wipe on version bump for now.
        db.execSQL("DROP TABLE IF EXISTS favorites")
        db.execSQL("DROP TABLE IF EXISTS chapters")
        db.execSQL("DROP TABLE IF EXISTS progress")
        db.execSQL("DROP TABLE IF EXISTS sources")
        db.execSQL("DROP TABLE IF EXISTS downloads")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "tachiyomi_legacy.db"
        const val DB_VERSION = 1

        const val TBL_FAVORITES = "favorites"
        const val TBL_CHAPTERS = "chapters"
        const val TBL_PROGRESS = "progress"
        const val TBL_SOURCES = "sources"
        const val TBL_DOWNLOADS = "downloads"

        // favorites: one row per favorited online manga
        private const val CREATE_FAVORITES = """
            CREATE TABLE $TBL_FAVORITES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT,
                artist TEXT,
                thumbnail_url TEXT,
                description TEXT,
                genre TEXT,
                status INTEGER DEFAULT 0,
                date_added INTEGER NOT NULL,
                last_read_at INTEGER,
                UNIQUE(source_id, url)
            )
        """

        // chapters: one row per known chapter of a favorited manga
        private const val CREATE_CHAPTERS = """
            CREATE TABLE $TBL_CHAPTERS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                manga_url TEXT NOT NULL,
                url TEXT NOT NULL,
                name TEXT NOT NULL,
                scanlator TEXT,
                chapter_number REAL,
                read INTEGER DEFAULT 0,
                UNIQUE(source_id, manga_url, url)
            )
        """

        // progress: reader progress per chapter
        private const val CREATE_PROGRESS = """
            CREATE TABLE $TBL_PROGRESS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                manga_url TEXT NOT NULL,
                chapter_url TEXT NOT NULL,
                page_index INTEGER DEFAULT 0,
                total_pages INTEGER DEFAULT 0,
                updated_at INTEGER NOT NULL,
                UNIQUE(source_id, manga_url, chapter_url)
            )
        """

        // sources: metadata about installed extension sources (for quick lookup)
        private const val CREATE_SOURCES = """
            CREATE TABLE $TBL_SOURCES (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                lang TEXT NOT NULL,
                base_url TEXT
            )
        """

        // downloads: which chapter pages are stored offline
        private const val CREATE_DOWNLOADS = """
            CREATE TABLE $TBL_DOWNLOADS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                manga_url TEXT NOT NULL,
                chapter_url TEXT NOT NULL,
                page_index INTEGER NOT NULL,
                file_path TEXT,
                status INTEGER DEFAULT 0,
                UNIQUE(source_id, manga_url, chapter_url, page_index)
            )
        """
    }
}

object FavoritesTable {
    const val KEY_ID = "id"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_URL = "url"
    const val KEY_TITLE = "title"
    const val KEY_AUTHOR = "author"
    const val KEY_ARTIST = "artist"
    const val KEY_THUMBNAIL = "thumbnail_url"
    const val KEY_DESCRIPTION = "description"
    const val KEY_GENRE = "genre"
    const val KEY_STATUS = "status"
    const val KEY_DATE_ADDED = "date_added"
    const val KEY_LAST_READ_AT = "last_read_at"

    fun toContentValues(sourceId: Long, url: String, title: String): ContentValues {
        return ContentValues().apply {
            put(KEY_SOURCE_ID, sourceId)
            put(KEY_URL, url)
            put(KEY_TITLE, title)
            put(KEY_DATE_ADDED, System.currentTimeMillis())
        }
    }
}

object ChaptersTable {
    const val KEY_ID = "id"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_MANGA_URL = "manga_url"
    const val KEY_URL = "url"
    const val KEY_NAME = "name"
    const val KEY_SCANLATOR = "scanlator"
    const val KEY_CHAPTER_NUMBER = "chapter_number"
    const val KEY_READ = "read"

    fun toContentValues(sourceId: Long, mangaUrl: String, url: String, name: String): ContentValues {
        return ContentValues().apply {
            put(KEY_SOURCE_ID, sourceId)
            put(KEY_MANGA_URL, mangaUrl)
            put(KEY_URL, url)
            put(KEY_NAME, name)
        }
    }
}

object ProgressTable {
    const val KEY_ID = "id"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_MANGA_URL = "manga_url"
    const val KEY_CHAPTER_URL = "chapter_url"
    const val KEY_PAGE_INDEX = "page_index"
    const val KEY_TOTAL_PAGES = "total_pages"
    const val KEY_UPDATED_AT = "updated_at"
}

object SourcesTable {
    const val KEY_ID = "id"
    const val KEY_NAME = "name"
    const val KEY_LANG = "lang"
    const val KEY_BASE_URL = "base_url"
}

object DownloadsTable {
    const val KEY_ID = "id"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_MANGA_URL = "manga_url"
    const val KEY_CHAPTER_URL = "chapter_url"
    const val KEY_PAGE_INDEX = "page_index"
    const val KEY_FILE_PATH = "file_path"
    const val KEY_STATUS = "status"
}