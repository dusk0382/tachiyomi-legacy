package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.io.File

/**
 * Verificacion de salud de las fuentes ES: ejecuta los parsers reales desde el
 * JVM con el flujo completo que usa la app (catalogo -> detalle -> capitulos ->
 * paginas -> descarga de la primera imagen con los headers del bridge: UA +
 * Referer + Accept) y reporta en que paso falla cada fuente.
 *
 * Uso:
 *   ./gradlew :kotatsu-parsers:testDebugUnitTest --tests "*SourceHealthCheck*" --console=plain
 *   ./gradlew :kotatsu-parsers:testDebugUnitTest --tests "*SourceHealthCheck*" -Dhealth.sources=INMANGA,MANGAFIRE_ES
 *
 * Resultado en consola y en /tmp/es_sources_health.tsv
 */
class SourceHealthCheck {

    private val context = JvmLoaderContext()

    @Test
    fun checkEsSources() {
        val requested = System.getProperty("health.sources")?.takeIf { it.isNotBlank() }
        val sources = MangaParserSource.entries
            .filter { !it.isBroken }
            .filter { it.locale == "es" }
            .filter {
                requested == null || requested.split(',').any { r ->
                    r.trim().equals(it.name, ignoreCase = true) || r.trim().equals(it.title, ignoreCase = true)
                }
            }

        val out = StringBuilder()
        out.appendLine("# ${sources.size} fuentes ES verificadas ${java.time.LocalTime.now()}")
        sources.forEach { source ->
            out.appendLine(checkSource(source))
            Thread.sleep(350)
        }
        File("/tmp/es_sources_health.tsv").writeText(out.toString())
        print(out)
        println("\nREPORTE: /tmp/es_sources_health.tsv")
    }

    private fun checkSource(source: MangaParserSource): String {
        val sb = StringBuilder()
        sb.append(source.name).append('\t').append(source.title)
        return try {
            val parser = context.newParserInstance(source)
            sb.append("\tparser\t")

            val mangas = runBlocking {
                withTimeout(45_000) { parser.getList(0, SortOrder.POPULARITY, MangaListFilter.EMPTY) }
            }
            if (mangas.isEmpty()) return sb.append("\tCATALOGO_VACIO").toString()
            sb.append("catalogo:${mangas.size}\t")

            val details = runBlocking {
                withTimeout(45_000) { parser.getDetails(mangas.first()) }
            }
            val chapters = details.chapters ?: emptyList()
            if (chapters.isEmpty()) return sb.append("\tSIN_CAPITULOS").toString()
            sb.append("caps:${chapters.size}\t")

            val pages = runBlocking {
                withTimeout(45_000) { parser.getPages(chapters.first()) }
            }
            if (pages.isEmpty()) return sb.append("\tSIN_PAGINAS").toString()
            sb.append("pags:${pages.size}\t")

            val imageUrl = runBlocking {
                withTimeout(30_000) { parser.getPageUrl(pages.first()) }
            }
            sb.append("\turl\t")

            val request = Request.Builder().url(imageUrl)
                .header("User-Agent", context.getDefaultUserAgent())
                .header("Referer", "https://${parser.domain}/")
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .build()
            val (code, contentType, length) = runBlocking {
                context.httpClient.newCall(request).execute().use { resp ->
                    Triple(resp.code, resp.header("Content-Type"), resp.body?.contentLength() ?: -1)
                }
            }

            if (code in 200..299 && contentType?.startsWith("image") == true) {
                sb.append("\tIMAGEN_OK:$length")
            } else {
                sb.append("\tIMAGEN_$code:$contentType")
            }
            sb.toString()
        } catch (e: Exception) {
            sb.append("\tFALLO:${e.message?.take(130) ?: e.javaClass.simpleName}")
            sb.toString()
        }
    }
}
