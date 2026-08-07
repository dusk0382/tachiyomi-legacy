package net.spin.tachiyomi.legacy.data.online

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.spin.tachiyomi.legacy.kotatsu.KotatsuSourceBridge
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Thin wrapper over the source-api's suspend API, executing on IO.
 * Each method targets a specific [Source] by id.
 */
object OnlineRepository {

    suspend fun getPopular(sourceId: Long, page: Int): Result<Pair<List<SManga>, Boolean>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = SourceManager.getOrThrow(sourceId).getPopularManga(page)
            result.mangas to result.hasNextPage
        }
    }

    suspend fun getSearch(sourceId: Long, query: String, page: Int): Result<Pair<List<SManga>, Boolean>> = withContext(Dispatchers.IO) {
        runCatching {
            val source = SourceManager.getOrThrow(sourceId)
            val result = source.getSearchManga(page, query, source.getFilterList())
            result.mangas to result.hasNextPage
        }
    }

    /**
     * Catálogo estilo Kotatsu: orden (Populares/Recientes/Nuevos...) + etiquetas.
     * Si la fuente no es un bridge Kotatsu, cae a getPopularManga.
     */
    suspend fun getCatalog(
        sourceId: Long,
        page: Int,
        order: SortOrder,
        query: String,
        tags: Set<MangaTag>,
    ): Result<Pair<List<SManga>, Boolean>> = withContext(Dispatchers.IO) {
        runCatching {
            val source = SourceManager.getOrThrow(sourceId)
            val bridge = source as? KotatsuSourceBridge
            if (bridge != null) {
                val result = bridge.getCatalogPage(page, order, query, tags)
                result.mangas to result.hasNextPage
            } else {
                val result = if (query.isBlank()) {
                    source.getPopularManga(page)
                } else {
                    source.getSearchManga(page, query, source.getFilterList())
                }
                result.mangas to result.hasNextPage
            }
        }
    }

    /** Órdenes de catálogo soportadas por la fuente (vacío si no es bridge Kotatsu). */
    suspend fun getSortOrders(sourceId: Long): List<SortOrder> = withContext(Dispatchers.IO) {
        (SourceManager.getOrThrow(sourceId) as? KotatsuSourceBridge)
            ?.availableSortOrders
            .orEmpty()
    }

    /** Etiquetas disponibles para filtrar el catálogo de la fuente. */
    suspend fun getCatalogTags(sourceId: Long): List<MangaTag> = withContext(Dispatchers.IO) {
        val source = SourceManager.getOrThrow(sourceId) as? KotatsuSourceBridge ?: return@withContext emptyList()
        runCatching { source.getFilterTags() }.getOrDefault(emptyList())
    }

    /** Fetches full manga details (initializes the given [manga]). */
    suspend fun fetchMangaDetails(sourceId: Long, manga: SManga): Result<SManga> = withContext(Dispatchers.IO) {
        runCatching {
            val source = SourceManager.getOrThrow(sourceId)
            val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            update.manga
        }
    }

    /** Fetches the chapter list for the given [manga]. */
    suspend fun fetchChapterList(sourceId: Long, manga: SManga): Result<List<SChapter>> = withContext(Dispatchers.IO) {
        runCatching {
            val source = SourceManager.getOrThrow(sourceId)
            val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
            update.chapters
        }
    }

    /** Fetches the page list for a chapter. */
    suspend fun fetchPageList(sourceId: Long, chapter: SChapter): Result<List<Page>> = withContext(Dispatchers.IO) {
        runCatching {
            SourceManager.getOrThrow(sourceId).getPageList(chapter)
        }
    }
}