package net.spin.tachiyomi.legacy.kotatsu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Expone una fuente de Kotatsu como un [HttpSource] de la app, traduciendo los
 * modelos (Manga/MangaChapter/MangaPage -> SManga/SChapter/Page). La UI de la
 * app (Browse/Catalog/Detail/Reader) funciona sin cambios.
 */
class KotatsuSourceBridge(
    private val parserSource: MangaParserSource,
    private val loader: MangaLoaderContext,
    private val httpClient: OkHttpClient,
) : HttpSource() {

    override val name: String = parserSource.title

    override val lang: String = parserSource.locale.ifEmpty { "all" }

    override val supportsLatest: Boolean = false

    override val baseUrl: String get() = "https://${parser.domain}"

    override val id: Long by lazy { generateId(parserSource.name, lang, 1) }

    override val client: OkHttpClient = httpClient

    override val headers: Headers = Headers.Builder()
        .add("User-Agent", KotatsuLoaderContext.DEFAULT_USER_AGENT)
        .build()

    private val parser: MangaParser by lazy { loader.newParserInstance(parserSource) }

    /** MangaPage por url, para poder resolver getImageUrl despues de getPageList. */
    private val pagesByUrl = ConcurrentHashMap<String, MangaPage>()

    private val pageSize: Int = 24

    // ---------------------------------------------------------------------
    // Mapeo de modelos
    // ---------------------------------------------------------------------

    private fun Manga.toSManga(): SManga = SManga(
        url = url,
        title = title,
        author = authors.joinToString(", ").ifBlank { null },
        description = description,
        genre = tags.joinToString(", ") { it.title }.ifBlank { null },
        status = when (state) {
            MangaState.ONGOING -> SManga.ONGOING
            MangaState.FINISHED -> SManga.COMPLETED
            MangaState.ABANDONED -> SManga.CANCELLED
            MangaState.PAUSED -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        },
        thumbnail_url = coverUrl,
    )

    private fun MangaChapter.toSChapter(): SChapter = SChapter(
        name = title ?: "Capítulo ${number}",
        url = url,
        date_upload = uploadDate,
        chapter_number = number,
        scanlator = scanlator,
    )

    private fun SChapter.toKMangaChapter(): MangaChapter = MangaChapter(
        id = url.hashCode().toLong(),
        title = name,
        number = chapter_number,
        volume = 0,
        url = url,
        scanlator = scanlator,
        uploadDate = date_upload,
        branch = null,
        source = parserSource,
    )

    private fun SManga.toKManga(): Manga = Manga(
        id = url.hashCode().toLong(),
        title = title,
        altTitles = emptySet(),
        url = url,
        publicUrl = "https://${this@KotatsuSourceBridge.parser.domain}$url",
        rating = 0f,
        contentRating = null,
        coverUrl = thumbnail_url,
        tags = emptySet(),
        state = null,
        authors = setOfNotNull(author),
        description = description,
        source = parserSource,
    )

    // ---------------------------------------------------------------------
    // API que consume la app
    // ---------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage = fetchPage(page) { offset ->
        parser.getList(offset = offset, order = SortOrder.POPULARITY, filter = MangaListFilter.EMPTY)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        fetchPage(page) { offset ->
            parser.getList(
                offset = offset,
                order = SortOrder.POPULARITY,
                filter = MangaListFilter(query = query.ifBlank { null }),
            )
        }

    private suspend fun fetchPage(page: Int, block: suspend (Int) -> List<Manga>): MangasPage {
        val offset = (page - 1) * pageSize
        val items = block(offset)
        val hasNext = items.size > pageSize
        return MangasPage(items.take(pageSize).map { it.toSManga() }, hasNext)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var updatedManga = manga
        var updatedChapters = chapters
        if (fetchDetails || fetchChapters) {
            val details = parser.getDetails(manga.toKManga())
            if (fetchDetails) {
                updatedManga = details.toSManga().also { it.initialized = true }
            }
            if (fetchChapters) {
                updatedChapters = details.chapters?.map { it.toSChapter() } ?: emptyList()
            }
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = parser.getPages(chapter.toKMangaChapter())
        return pages.mapIndexed { index, mangaPage ->
            pagesByUrl[mangaPage.url] = mangaPage
            Page(index = index, url = mangaPage.url)
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        val mangaPage = pagesByUrl[page.url]
            ?: MangaPage(page.url.hashCode().toLong(), page.url, null, parserSource)
        return parser.getPageUrl(mangaPage)
    }

    override suspend fun getImage(page: Page, existingSize: Long): Response {
        val url = page.imageUrl ?: throw Exception("Page image url is not resolved")
        val request: Request = GET(url, headers)
        return client.newCall(request).execute()
    }

    override fun getFilterList(): FilterList = FilterList()
}
