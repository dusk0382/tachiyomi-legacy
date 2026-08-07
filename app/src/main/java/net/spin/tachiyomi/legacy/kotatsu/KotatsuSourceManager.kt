package net.spin.tachiyomi.legacy.kotatsu

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

    private const val ENABLED_LOCALES = "es,en,all,pt"

    private val excludedTypes = setOf(
        ContentType.HENTAI,
        ContentType.DOUJINSHI,
        ContentType.ARTIST_CG,
        ContentType.GAME_CG,
    )

    private var bridges: List<KotatsuSourceBridge> = emptyList()

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
        .filter { it.contentType !in excludedTypes }
        .filter { it.locale in ENABLED_LOCALES.split(',') }
}
