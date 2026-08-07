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
import org.koitharu.kotatsu.parsers.model.MangaTag
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

    override val headers: Headers
        get() = Headers.Builder()
            .add("User-Agent", KotatsuLoaderContext.DEFAULT_USER_AGENT)
            .add("Referer", "https://${parser.domain}/")
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()

    private val parser: MangaParser by lazy { loader.newParserInstance(parserSource) }

    /** MangaPage por url, para poder resolver getImageUrl despues de getPageList. */
    private val pagesByUrl = ConcurrentHashMap<String, MangaPage>()

    /**
     * Tamaño de página REAL del parser (cada fuente define el suyo: 10-30).
     * El factory envuelve TODOS los parsers en [MangaParserWrapper], así que hay
     * que desenrollar hasta el parser concreto para leer su pageSize: si no, se
     * usaba un valor fijo (24) y el Paginator interno desalineaba el offset +
     * `hasNext` moría en la 1ª página (~10-24 mangas).
     */
    @OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)
    private val parserPageSize: Int
        get() {
            var current: org.koitharu.kotatsu.parsers.MangaParser = parser
            while (current is org.koitharu.kotatsu.parsers.core.MangaParserWrapper) {
                current = current.delegate
            }
            return (current as? org.koitharu.kotatsu.parsers.core.PagedMangaParser)?.pageSize ?: 24
        }

    // ---------------------------------------------------------------------
    // Mapeo de modelos
    // ---------------------------------------------------------------------

    /**
     * Limpia el texto que devuelven los parsers (HTML residual, whitespace
     * múltiple, caracteres basura): sin esto algunas fuentes muestran la
     * descripción pegada al título o con <br>/<p> crudos.
     */
    private fun cleanText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        s = s.replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n")
        return s.trim().ifBlank { null }
    }

    private fun Manga.toSManga(): SManga = SManga(
        url = url,
        title = cleanText(title) ?: title,
        author = cleanText(authors.joinToString(", ")),
        description = cleanText(description),
        genre = cleanText(tags.joinToString(", ") { it.title }),
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

    /** Ordenaciones de catálogo que soporta esta fuente (Populares, Recientes, Nuevos...). */
    val availableSortOrders: List<SortOrder>
        get() = parser.availableSortOrders.toList()

    /** Etiquetas (tags) disponibles para filtrar el catálogo de esta fuente. */
    suspend fun getFilterTags(): List<MangaTag> =
        runCatching { parser.getFilterOptions().availableTags.toList() }.getOrDefault(emptyList())

    /**
     * Página del catálogo con orden + filtro de tags (y opcionalmente búsqueda),
     * como el navegador de Kotatsu (Populares / Recientes / Nuevos / etiquetas).
     */
    suspend fun getCatalogPage(
        page: Int,
        order: SortOrder,
        query: String?,
        tags: Set<MangaTag>,
    ): MangasPage = fetchPage(page) { offset ->
        parser.getList(
            offset = offset,
            order = order,
            filter = MangaListFilter(
                query = query?.ifBlank { null },
                tags = tags,
            ),
        )
    }

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
        val size = parserPageSize
        val offset = (page - 1) * size
        val items = block(offset)
        // Página completa => probablemente hay más; vacía o corta => fin.
        // (Una página exacta provoca una petición extra vacía y corta: igual que Kotatsu.)
        val hasNext = items.isNotEmpty() && items.size >= size
        return MangasPage(items.map { it.toSManga() }, hasNext)
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
