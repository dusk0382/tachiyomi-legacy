package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.io.File

/**
 * Verificacion de salud de las fuentes: ejecuta los parsers reales desde el
 * JVM con el flujo completo que usa la app (catalogo -> detalle -> capitulos ->
 * paginas -> descarga de la primera imagen con los headers del bridge: UA +
 * Referer + Accept, y portada) y reporta en que paso falla cada fuente.
 *
 * Uso:
 *   ./gradlew :kotatsu-parsers:testDebugUnitTest --tests "*SourceHealthCheck*" --console=plain
 *   ./gradlew :kotatsu-parsers:testDebugUnitTest --tests "*SourceHealthCheck*" -Dhealth.sources=INMANGA,MANGAFIRE_ES
 *   ./gradlew :kotatsu-parsers:testDebugUnitTest --tests "*SourceHealthCheck*" -Dhealth.nsfw=true -Dhealth.locale=es
 *   ./gradlew :kotatsu-parsers:testDebugUnitTest --tests "*SourceHealthCheck*" -Dhealth.nsfw=true -Dhealth.locale=en -Dhealth.timeout=20
 *
 * Resultado en consola y en /tmp/{locale}_nsfw_health.tsv (o _sources_health.tsv
 * sin nsfw). Se escribe INCREMENTALMENTE por si el proceso se corta.
 */
class SourceHealthCheck {

    private val context = JvmLoaderContext()

    private val nsfwTypes = setOf(
        ContentType.HENTAI,
        ContentType.DOUJINSHI,
        ContentType.ARTIST_CG,
        ContentType.GAME_CG,
    )

    @Test
    fun checkSources() {
        val nsfw = System.getProperty("health.nsfw")?.toBoolean() == true
        val locales = (System.getProperty("health.locale") ?: "es")
            .split(',').map { it.trim() }.filter { it.isNotBlank() }
        val timeoutSec = (System.getProperty("health.timeout") ?: "45").toLongOrNull() ?: 45L
        val requested = System.getProperty("health.sources")?.takeIf { it.isNotBlank() }

        val sources = MangaParserSource.entries
            .filter { !it.isBroken }
            .filter { if (nsfw) it.contentType in nsfwTypes else it.contentType !in nsfwTypes }
            // Las fuentes "all" tienen locale vacio en el enum (el bridge usa "all").
            .filter { it.locale in locales || (it.locale.isEmpty() && "all" in locales) }
            .filter {
                requested == null || requested.split(',').any { r ->
                    r.trim().equals(it.name, ignoreCase = true) || r.trim().equals(it.title, ignoreCase = true)
                }
            }
            .sortedBy { it.title.lowercase() }

        val tag = if (nsfw) "nsfw" else "sources"
        val file = File("/tmp/${locales.joinToString("_")}_${tag}_health.tsv")

        // Reanudacion: saltar fuentes ya probadas en una corrida anterior (el
        // archivo se escribe incrementalmente, asi se puede cortar y relanzar).
        val done: Set<String> = if (file.exists()) {
            file.readLines().mapNotNull { line ->
                val name = line.substringBefore('\t')
                if (name.isBlank() || line.startsWith("#")) null else name
            }.toSet()
        } else {
            emptySet()
        }
        val pending = sources.filter { it.name !in done }
        if (!file.exists()) {
            file.writeText("# ${sources.size} fuentes ${if (nsfw) "NSFW " else ""}locales=${locales.joinToString(",")} ${java.time.LocalTime.now()}\n")
        }

        val out = StringBuilder()
        out.appendLine("# ${pending.size} pendientes de ${sources.size} fuentes ${if (nsfw) "NSFW " else ""}locales=${locales.joinToString(",")} ${java.time.LocalTime.now()}")
        pending.forEach { source ->
            val line = checkSource(source, timeoutSec)
            out.appendLine(line)
            println(line)
            file.appendText(line + "\n")
            Thread.sleep(350)
        }
        print(out)
        println("\nREPORTE: ${file.absolutePath}")
    }

    private fun checkSource(source: MangaParserSource, timeoutSec: Long): String {
        val sb = StringBuilder()
        sb.append(source.name).append('\t').append(source.title).append('\t').append(source.contentType)
        return try {
            val parser = context.newParserInstance(source)
            sb.append("\tparser\t")

            val mangas = runBlocking {
                withTimeout(timeoutSec * 1000) { parser.getList(0, SortOrder.POPULARITY, MangaListFilter.EMPTY) }
            }
            if (mangas.isEmpty()) return sb.append("\tCATALOGO_VACIO").toString()
            sb.append("catalogo:${mangas.size}\t")

            // Portada: descargar la cover del primer manga (el "no muestra la
            // portada" reportado por el usuario). Fallo = portada rota.
            val cover = mangas.first().coverUrl
            if (cover.isNullOrBlank()) {
                sb.append("COVER_SIN_URL\t")
            } else {
                val coverReq = Request.Builder().url(cover)
                    .header("User-Agent", context.getDefaultUserAgent())
                    .header("Referer", "https://${parser.domain}/")
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .build()
                val (code, contentType, length) = runBlocking {
                    context.httpClient.newCall(coverReq).execute().use { resp ->
                        Triple(resp.code, resp.header("Content-Type"), resp.body?.contentLength() ?: -1)
                    }
                }
                if (code in 200..299 && contentType?.startsWith("image") == true) {
                    sb.append("COVER_OK:$length\t")
                } else {
                    sb.append("COVER_$code:$contentType\t")
                }
            }

            val details = runBlocking {
                withTimeout(timeoutSec * 1000) { parser.getDetails(mangas.first()) }
            }
            val chapters = details.chapters ?: emptyList()
            if (chapters.isEmpty()) return sb.append("\tSIN_CAPITULOS").toString()
            sb.append("caps:${chapters.size}\t")

            // Probe several chapters (first / middle / last) and keep the best result.
            // Some sites (InManga) are migrating their CDN by upload date, so old
            // chapters may 404 while recent ones work.
            val candidates = listOf(0, chapters.size / 2, chapters.size - 1).distinct().map { chapters[it] }
            var best = "\tSIN_PAGINAS"
            candidates.forEach { chapter ->
                val pages = runBlocking {
                    withTimeout(timeoutSec * 1000) { parser.getPages(chapter) }
                }
                if (pages.isEmpty()) return@forEach
                val imageUrl = runBlocking {
                    withTimeout(timeoutSec * 1000) { parser.getPageUrl(pages.first()) }
                }

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

                val result = if (code in 200..299 && contentType?.startsWith("image") == true) {
                    "\tIMAGEN_OK:$length"
                } else {
                    "\tIMAGEN_$code:$contentType"
                }
                if (result.startsWith("\tIMAGEN_OK")) {
                    best = result
                    return@forEach
                }
                if (best == "\tSIN_PAGINAS") best = result
            }
            sb.append("\turl\t")
            sb.append(best)
            sb.toString()
        } catch (e: Exception) {
            sb.append("\tFALLO:${e.message?.take(130) ?: e.javaClass.simpleName}")
            sb.toString()
        }
    }
}
