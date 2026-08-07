package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.core.MangaParserWrapper
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Verifica la paginación real como la usa el bridge de la app:
 * desenrolla el MangaParserWrapper (que el factory aplica a TODOS los parsers),
 * lee el pageSize real del parser y pide varias páginas con offsets alineados,
 * comprobando que cada página devuelve contenido (scroll infinito).
 *
 * Uso:
 *   ./gradlew :kotatsu-parsers:testDebugUnitTest --tests "*PaginationCheck*" -Dhealth.sources=INMANGA
 */
class PaginationCheck {

    private val context = JvmLoaderContext()

    @Test
    fun checkPagination() {
        val requested = (System.getProperty("health.sources") ?: "INMANGA")
            .split(',').map { it.trim() }.filter { it.isNotBlank() }
        val sources = MangaParserSource.entries
            .filter { !it.isBroken }
            .filter { s -> requested.any { r -> r.equals(s.name, ignoreCase = true) || r.equals(s.title, ignoreCase = true) } }

        sources.forEach { source ->
            println("\n===== $source =====")
            val parser = context.newParserInstance(source)

            // Desenrollar el wrapper como hace el bridge
            var current: MangaParser = parser
            while (current is MangaParserWrapper) current = current.delegate
            val paged = current as? PagedMangaParser
            val pageSize = paged?.pageSize ?: 24
            println("parser class: ${current.javaClass.simpleName} | pageSize REAL: $pageSize")

            var total = 0
            var page = 1
            while (page <= 6) {
                val offset = (page - 1) * pageSize
                val items = runBlocking {
                    withTimeout(30_000) { parser.getList(offset, SortOrder.POPULARITY, MangaListFilter.EMPTY) }
                }
                val hasNext = items.isNotEmpty() && items.size >= pageSize
                total += items.size
                println("  página $page (offset=$offset) -> ${items.size} mangas | hasNext=$hasNext")
                if (!hasNext) break
                page++
                Thread.sleep(300)
            }
            println("  TOTAL: $total mangas en $page páginas")
        }
    }
}
