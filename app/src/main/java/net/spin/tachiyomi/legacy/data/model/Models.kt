package net.spin.tachiyomi.legacy.data.model

/**
 * Lightweight models for the online library, decoupled from the source-api
 * transient types so they can be persisted in SQLite.
 */

data class MangaRef(
    val sourceId: Long,
    val url: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastReadAt: Long? = null,
) {
    val key: String get() = "$sourceId:$url"
}

data class ChapterRef(
    val sourceId: Long,
    val mangaUrl: String,
    val url: String,
    val name: String,
    val scanlator: String? = null,
    val chapterNumber: Double = 0.0,
    val read: Boolean = false,
    val uploadDate: Long = 0L,
) {
    val key: String get() = "$sourceId:$mangaUrl:$url"
}

data class ProgressRef(
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val pageIndex: Int = 0,
    val totalPages: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class HistoryRef(
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val lastReadAt: Long = System.currentTimeMillis(),
    val lastChapterUrl: String? = null,
    val lastChapterName: String? = null,
    val lastPageIndex: Int = 0,
    val lastTotalPages: Int = 0,
) {
    val key: String get() = "$sourceId:$url"
}

data class SourceRef(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String? = null,
)