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
        db.execSQL(CREATE_HISTORY)
        db.execSQL(CREATE_PRIVATE_MANGA)
        db.execSQL(CREATE_MANGA_DOWNLOADS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: chapters.upload_date (fecha de subida del capitulo)
            try {
                db.execSQL("ALTER TABLE $TBL_CHAPTERS ADD COLUMN upload_date INTEGER DEFAULT 0")
            } catch (_: Exception) {
                // la columna ya existe o la tabla no esta; no romper nada
            }
        }
        if (oldVersion < 3) {
            // v3: tabla history (mangas online recientes)
            try {
                db.execSQL(CREATE_HISTORY)
            } catch (_: Exception) {
                // la tabla ya existe; no romper nada
            }
        }
        if (oldVersion < 4) {
            // v4: tabla private_manga (mangos online en carpeta privada)
            try {
                db.execSQL(CREATE_PRIVATE_MANGA)
            } catch (_: Exception) {
                // la tabla ya existe; no romper nada
            }
        }
        if (oldVersion < 5) {
            // v5: tabla manga_downloads (mangas con capitulos descargados)
            try {
                db.execSQL(CREATE_MANGA_DOWNLOADS)
            } catch (_: Exception) {
                // la tabla ya existe; no romper nada
            }
        }
    }

    companion object {
        const val DB_NAME = "tachiyomi_legacy.db"
        const val DB_VERSION = 5

        const val TBL_FAVORITES = "favorites"
        const val TBL_CHAPTERS = "chapters"
        const val TBL_PROGRESS = "progress"
        const val TBL_SOURCES = "sources"
        const val TBL_DOWNLOADS = "downloads"
        const val TBL_HISTORY = "history"
        const val TBL_PRIVATE_MANGA = "private_manga"
        const val TBL_MANGA_DOWNLOADS = "manga_downloads"

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
                upload_date INTEGER DEFAULT 0,
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

        // history: mangas online vistos recientemente
        private const val CREATE_HISTORY = """
            CREATE TABLE $TBL_HISTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                thumbnail_url TEXT,
                last_read_at INTEGER NOT NULL,
                last_chapter_url TEXT,
                last_chapter_name TEXT,
                last_page_index INTEGER DEFAULT 0,
                last_total_pages INTEGER DEFAULT 0,
                UNIQUE(source_id, url)
            )
        """

        // private_manga: mangos online ocultos en la carpeta privada
        private const val CREATE_PRIVATE_MANGA = """
            CREATE TABLE $TBL_PRIVATE_MANGA (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                thumbnail_url TEXT,
                date_added INTEGER NOT NULL,
                UNIQUE(source_id, url)
            )
        """

        // manga_downloads: mangos online con capitulos descargados (pestaña Descargas)
        // description/author se persisten para que la ficha offline tenga texto.
        private const val CREATE_MANGA_DOWNLOADS = """
            CREATE TABLE $TBL_MANGA_DOWNLOADS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id INTEGER NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                thumbnail_url TEXT,
                description TEXT,
                author TEXT,
                downloaded_at INTEGER NOT NULL,
                UNIQUE(source_id, url)
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
    const val KEY_UPLOAD_DATE = "upload_date"

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

object DownloadMangaTable {
    const val KEY_ID = "id"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_URL = "url"
    const val KEY_TITLE = "title"
    const val KEY_THUMBNAIL_URL = "thumbnail_url"
    const val KEY_DESCRIPTION = "description"
    const val KEY_AUTHOR = "author"
    const val KEY_DOWNLOADED_AT = "downloaded_at"

    fun toContentValues(sourceId: Long, url: String, title: String): ContentValues {
        return ContentValues().apply {
            put(KEY_SOURCE_ID, sourceId)
            put(KEY_URL, url)
            put(KEY_TITLE, title)
            put(KEY_DOWNLOADED_AT, System.currentTimeMillis())
        }
    }
}

object PrivateMangaTable {
    const val KEY_ID = "id"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_URL = "url"
    const val KEY_TITLE = "title"
    const val KEY_THUMBNAIL_URL = "thumbnail_url"
    const val KEY_DATE_ADDED = "date_added"

    fun toContentValues(sourceId: Long, url: String, title: String): ContentValues {
        return ContentValues().apply {
            put(KEY_SOURCE_ID, sourceId)
            put(KEY_URL, url)
            put(KEY_TITLE, title)
            put(KEY_DATE_ADDED, System.currentTimeMillis())
        }
    }
}

object HistoryTable {
    const val KEY_ID = "id"
    const val KEY_SOURCE_ID = "source_id"
    const val KEY_URL = "url"
    const val KEY_TITLE = "title"
    const val KEY_THUMBNAIL_URL = "thumbnail_url"
    const val KEY_LAST_READ_AT = "last_read_at"
    const val KEY_LAST_CHAPTER_URL = "last_chapter_url"
    const val KEY_LAST_CHAPTER_NAME = "last_chapter_name"
    const val KEY_LAST_PAGE_INDEX = "last_page_index"
    const val KEY_LAST_TOTAL_PAGES = "last_total_pages"

    fun toContentValues(
        sourceId: Long,
        url: String,
        title: String,
        thumbnailUrl: String?,
        lastChapterUrl: String?,
        lastChapterName: String?,
        lastPageIndex: Int,
        lastTotalPages: Int,
    ): ContentValues {
        return ContentValues().apply {
            put(KEY_SOURCE_ID, sourceId)
            put(KEY_URL, url)
            put(KEY_TITLE, title)
            thumbnailUrl?.let { put(KEY_THUMBNAIL_URL, it) }
            put(KEY_LAST_READ_AT, System.currentTimeMillis())
            lastChapterUrl?.let { put(KEY_LAST_CHAPTER_URL, it) }
            lastChapterName?.let { put(KEY_LAST_CHAPTER_NAME, it) }
            put(KEY_LAST_PAGE_INDEX, lastPageIndex)
            put(KEY_LAST_TOTAL_PAGES, lastTotalPages)
        }
    }
}