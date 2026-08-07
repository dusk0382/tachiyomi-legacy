package net.spin.tachiyomi.legacy.kotatsu

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Construye y registra las fuentes de Kotatsu en el [SourceManager] de la app.
 * Las fuentes habilitadas: no rotas, sin contenido NSFW y en los idiomas de
 * la lista (es, en, all, pt).
 */
object KotatsuSourceManager {

    // Nota: 'pt' NO está — el usuario pidió eliminar todo el portugués (incluidas
    // las 28 fuentes hentai PT que aparecían al activar NSFW).
    private const val ENABLED_LOCALES = "es,en,all"

    private val excludedTypes = setOf(
        ContentType.HENTAI,
        ContentType.DOUJINSHI,
        ContentType.ARTIST_CG,
        ContentType.GAME_CG,
    )

    /**
     * Fuentes excluidas por decision del usuario (verificadas con test de
     * disponibilidad): caidas (DNS muerto, errores estables, timeouts) y
     * las que pidio quitar manualmente (YaoiManga; portugues completo).
     */
    private val excludedNames = setOf(
        // --- Español: caidas + YaoiManga ---
        "ASIALOTUSS", "HERENSCAN", "INFRAFANDUB", "LEGENDSCANLATIONS",
        "MARMOTA", "MENUDO_FANSUB", "MHSCANS", "RAIKISCAN", "TAURUSMANGA",
        "TRADUCCIONESAMISTOSAS", "TUMANGAONLINE", "WEEBDEX_ES", "YAOIMANGA",
        // --- Portugués: todas ---
        "ALONESCANLATOR", "ANIMEXNOVEL", "ARCTICSCAN", "ARTHUR_SCAN", "ATEMPORAL",
        "BORUTOEXPLORER", "BRMANGASTOP", "CRYSTALSCAN", "DEMONSECT", "DIANXIATRADS",
        "DREAMSCAN", "DUOSCANLATORS", "ELEVENSCANLATOR", "FAYSCANS", "FENIXPROJECT",
        "FLOWERMANGA", "GALAXSCANS", "GHOSTSCAN", "HECKSCANS", "HIKARISCAN",
        "IMPERIODABRITANNIA", "IRISSCANLATOR", "KAKUSEIPROJECT", "KALANGO",
        "LEITORDEMANGA", "LEITORKAMISAMA", "LER999", "LERMANGAS", "LICHMANGAS",
        "LIMBOSCAN", "LIMITEDTIMEPOJECT", "MAIDSCAN", "MAIDSECRET", "MANGABALL_PTBR",
        "MANGAFIRE_PT", "MANGAFIRE_PTBR", "MANGALIVRE", "MANGANINJA", "MANGAONLINE",
        "MANGAONLINE_BLOG", "MANGAPLUSPARSER_PTBR", "MANGATERRA", "MANHASTRO",
        "MEDIOCRETOONS", "MINITWOSCAN", "MOONWITCHINLOVESCAN", "MUGIWARASOFICIAL",
        "NEROXUS", "NINJASCAN", "NIRVANASCAN", "NORTEROSE", "ORIGAMIORPHEANS",
        "PASSAMAOSCAN", "PLUMACOMICS", "POINTZEROTOONS", "RAYSSCAN", "SSREADING",
        "SUSSYSCAN", "SWEETSCAN", "TATAKAE_SCANS", "TEMAKIMANGAS", "TOOMICSPT",
        "TSUNDOKU", "TYRANTSCANS", "WEEBDEX_PT", "WICKEDWITCHSCAN", "WINTERSCAN",
        "WOLFSCANBR", "WONDERLANDSCAN", "XSSCAN", "YANPFANSUB", "YAOIFANCLUB",
        "YOMUMANGAS", "YUGENMANGAS",
        // --- Inglés: caidas ---
        "ASTRASCANS", "BANANA_MANGA", "BEETOON", "BOOKMANGA", "CYPHERSCANS",
        "DAYCOMICS", "FIRESCANS", "HENTALK", "JIMANGA", "KUMASCANS",
        "MANGACLASH", "MANGAECLIPSE", "MANGAFOREST", "MANGAGEKO", "MANGAGOJO",
        "MANGAKISS", "MANGALEVELING", "MANGASECT", "MANGATXUNOFFICIAL", "MANGATX_GG",
        "MANGAWEEBS", "MANHUAGA", "MANHUAGOLD", "MANHUAUSS", "MANHWAMANHUA",
        "MANHWASMEN", "NECROSCANS", "READER_EVILFLOWERS", "REAPERSCANSUNORIGINAL",
        "RESETSCANS", "SHOOTINGSTARSCANS", "TCBSCANSMANGA", "TECNOSCANS", "UTOON",
        "WEEBDEX_EN", "ZANDYNOFANSUB", "ZIN_MANGA_COM",
    )

    /**
     * Fuentes NSFW caídas o rotas (verificadas con el health check JVM: 404
     * estables, DNS muerto, Cloudflare 403/521, cert inválido, timeouts).
     * Solo quedan habilitadas las que pasan el flujo completo (catálogo ->
     * portada -> capítulos -> imagen).
     */
    private val excludedNsfw = setOf(
        // --- Español + multiidioma (locale all): caídas ---
        "DOUJINSHELL", "DOUJIN_HENTAI_NET", "ERO18X", "HENTAIREADER", "MANGACRAZY",
        "MANGALAND", "MANGAXICO", "MANHWARAW", "MANHWAS_ES", "MANYTOON_CLUB",
        "PZYKOSIS666HFANSUB", "SEINAGIADULTO", "TOPCOMICPORNO", "TRADUCCIONESMOONLIGHT",
        "TUMANHWAS", "VERCOMICSPORNO", "VERMANGASPORNO", "VERMANHWA",
        // --- Inglés: caídas ---
        "ADULT_WEBTOON", "BEEHENTAI", "COMIZ", "DEXHENTAI", "EDOUJIN", "HENTAI3ZCC",
        "HENTAIMANGA", "HENTAIWEBTOON", "HENTAIXYURI", "HENTAI_4FREE", "HEYTOON",
        "HIPERDEX", "LILYMANGA", "LUNAR_SCAN", "MADARADEX", "MANGA18X", "MANGADASS",
        "MANGAHENTAI", "MANHWA18ORG", "MANHWA68", "MANHWAHENTAI", "MANHWAHENTAITO",
        "MANHWARAW_COM", "MANHWATOON", "MANHWAX", "MANYTOON", "MANYTOONME",
        "MILFTOON", "NOVELCROW", "OMEGASCANS", "PAWMANGA", "PORNCOMIXONLINE",
        "SUMMANGA", "TOONGOD", "TOONILY_ME", "TOONITUBE", "TOONIZY", "WEBTOONSCAN",
        "WEBTOONXYZ", "YAOIHUB", "ZINCHANMANGA",
    )

    private var bridges: List<KotatsuSourceBridge> = emptyList()

    /**
     * Fuentes NSFW (hentai/doujinshi) visibles. Solo en memoria: se desactiva
     * al cerrar la app o al volver a escribir "NSFWActivate" en el buscador.
     */
    @Volatile
    var nsfwEnabled: Boolean = false
        private set

    /** Construye (una vez) el loader y los bridges a partir del NetworkHelper de la app. */
    fun init(network: NetworkHelper) {
        if (bridges.isNotEmpty()) return
        // baseClient no trae el CloudflareInterceptor WebView (30s muertos en
        // 403 legitimos); el rate limiter evita el rate-limit de APIs tipo MangaFire.
        val kotatsuClient = network.baseClient.newBuilder()
            .addInterceptor(KotatsuRateLimitInterceptor())
            .build()
        val loader = KotatsuLoaderContext(kotatsuClient, network.cookieJar)
        bridges = allSources().map { KotatsuSourceBridge(it, loader, kotatsuClient) }
    }

    fun registerAll() {
        SourceManager.registerKotatsuSources(bridges)
    }

    /** Fuentes de Kotatsu habilitadas. */
    fun allSources(): List<MangaParserSource> = MangaParserSource.entries
        .filter { !it.isBroken }
        .filter { nsfwEnabled || it.contentType !in excludedTypes }
        .filter { it.locale in ENABLED_LOCALES.split(',') }
        .filter { it.name !in excludedNames }
        // NSFW: solo las que pasan el health check (las caídas quedan fuera).
        .filter { it.contentType !in excludedTypes || it.name !in excludedNsfw }
        // Blindaje extra: jamás registrar una fuente de portugués (cualquiera que sea su contentType).
        .filter { it.locale != "pt" && !it.locale.startsWith("pt-") }

    /**
     * Activa/desactiva las fuentes NSFW reconstruyendo los bridges y
     * re-registrandolos en el SourceManager (efecto inmediato).
     */
    fun applyNsfw(network: NetworkHelper, extensions: List<Extension.Installed>, enabled: Boolean) {
        nsfwEnabled = enabled
        SourceManager.clear()
        SourceManager.registerExtensions(extensions)
        bridges = emptyList()
        init(network)
        registerAll()
    }
}
