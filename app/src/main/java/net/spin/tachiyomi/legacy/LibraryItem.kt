package net.spin.tachiyomi.legacy

/**
 * Entry unificado de la biblioteca: un CBZ local o un manga online
 * (favorito o del historial).
 */
sealed class LibraryItem {
    abstract val title: String

    /** CBZ local en el almacenamiento. */
    data class Local(val manga: MangaFile) : LibraryItem() {
        override val title: String get() = manga.title
    }

    /** Manga online (favorito, del historial, de la carpeta privada o descargado). */
    data class Online(
        val sourceId: Long,
        val url: String,
        override val title: String,
        val thumbnailUrl: String? = null,
        val subtitle: String? = null,
        val readPercent: Float = 0f,
        val isHistory: Boolean = false,
        val isPrivate: Boolean = false,
        val isDownload: Boolean = false,
        val lastChapterUrl: String? = null,
        val lastChapterName: String? = null,
        val lastPageIndex: Int = 0,
    ) : LibraryItem()
}
