package eu.kanade.tachiyomi.extension.api

import android.util.Log
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo
import mihon.data.extension.model.NetworkExtensionStore as Store

/**
 * Fetches available extensions from one or more extension repositories.
 *
 * A repo base URL is e.g. "https://keiyoushi.github.io/extensions". Both the
 * legacy JSON format (`index.min.json` + `repo.json`) and the newer Mihon
 * format (`index.json` + optional `extensionListUrl`) are supported.
 */
class ExtensionApi(
    private val network: NetworkHelper,
) {

    fun networkClient() = network.client

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Fetches the extension lists for the given repository base URLs.
     * Failures of individual repos are collected in [RepoResult.errors] instead of
     * failing the whole request, so the UI can tell the user what went wrong.
     */
    suspend fun findExtensions(repoBaseUrls: List<String>): RepoResult {
        return withContext(Dispatchers.IO) {
            supervisorScope {
                val results = repoBaseUrls.map { baseUrl ->
                    async { baseUrl to runCatching { fetchRepo(baseUrl) } }
                }.awaitAll()

                RepoResult(
                    extensions = results.flatMap { it.second.getOrElse { emptyList() } },
                    errors = results.mapNotNull { (baseUrl, res) ->
                        res.exceptionOrNull()?.let { e ->
                            Log.e("ExtensionApi", "Repo $baseUrl falló: ${e.message}", e)
                            "$baseUrl\n${e.message ?: e.javaClass.simpleName}"
                        }
                    },
                )
            }
        }
    }

    /** Result of fetching one or more repos: loaded extensions plus per-repo errors. */
    data class RepoResult(
        val extensions: List<Extension.Available>,
        val errors: List<String>,
    )

    private suspend fun fetchRepo(baseUrl: String): List<Extension.Available> {
        // Direct index/repo URL pasted by the user (e.g. ".../index.min.json").
        if (baseUrl.endsWith(".json") || baseUrl.endsWith(".pb")) {
            return fetchIndexOrRepo(baseUrl)
        }
        val legacy = fetchLegacy(baseUrl)
        if (legacy.isNotEmpty()) return legacy
        return fetchV2(baseUrl)
    }

    /**
     * Fetches a direct index or repo URL, detecting the format from its payload:
     * a `repo.json` may redirect to a v2 index; otherwise it is an inline list.
     */
    private suspend fun fetchIndexOrRepo(url: String): List<Extension.Available> {
        val response = network.client.newCall(GET(url)).awaitSuccess()
        val body = response.body.string().trimStart()
        return when {
            body.startsWith("[") -> json.decodeFromString(ListSerializer(NetworkLegacyExtension.serializer()), body)
                .map { it.toAvailable(url.substringBeforeLast('/')) }

            // Object: either a repo.json (with indexV2) or a v2 store.
            body.startsWith("{") -> {
                val repo = try {
                    json.decodeFromString(NetworkLegacyExtensionRepo.serializer(), body)
                } catch (_: Exception) {
                    null
                }
                val v2Url = repo?.indexV2
                if (v2Url != null) fetchV2(v2Url) else parseV2Store(body)
            }

            else -> emptyList()
        }
    }

    /** Legacy format: `index.min.json` array, with optional `repo.json` v2 redirect. */
    private suspend fun fetchLegacy(baseUrl: String): List<Extension.Available> {
        val index = try {
            network.client.newCall(GET("$baseUrl/index.min.json")).awaitSuccess()
        } catch (_: Exception) {
            return emptyList()
        }
        val body = index.body.string()

        // Follow repo.json -> indexV2 when present (modern repos redirect legacy).
        try {
            val repo = network.client.newCall(GET("$baseUrl/repo.json")).awaitSuccess()
            val parsed = json.decodeFromString(NetworkLegacyExtensionRepo.serializer(), repo.body.string())
            val indexV2 = parsed.indexV2 ?: return emptyList()
            return fetchV2(indexV2)
        } catch (_: Exception) {
            // fall through to inline legacy list
        }

        return json.decodeFromString(ListSerializer(NetworkLegacyExtension.serializer()), body)
            .map { it.toAvailable(baseUrl) }
    }

    /** V2 format: `index.json` store, possibly with an external extension list URL. */
    private suspend fun fetchV2(baseUrlOrIndex: String): List<Extension.Available> {
        val indexUrl = when {
            baseUrlOrIndex.endsWith(".json") || baseUrlOrIndex.endsWith(".pb") -> baseUrlOrIndex
            else -> "$baseUrlOrIndex/index.json"
        }

        // .pb (protobuf) stores are not supported: skip.
        if (indexUrl.endsWith(".pb")) return emptyList()

        val index = network.client.newCall(GET(indexUrl)).awaitSuccess()
        val body = index.body.string()
        return parseV2Store(body)
    }

    private suspend fun parseV2Store(body: String): List<Extension.Available> {
        val store = json.decodeFromString(Store.serializer(), body)

        val list = if (store.extensionList != null) {
            store.extensionList
        } else if (store.extensionListUrl != null) {
            val res = network.client.newCall(GET(store.extensionListUrl)).awaitSuccess()
            val extBody = res.body.string()
            json.decodeFromString(Store.ExtensionList.serializer(), extBody)
        } else {
            return emptyList()
        }

        return list.extensions.map { it.toAvailable() }
    }
}

private fun Store.Extension.toAvailable(): Extension.Available {
    val lang = sources.map { it.language }.toSet()
    return Extension.Available(
        name = name,
        pkgName = packageName,
        versionName = versionName,
        versionCode = versionCode.toLongOrNull() ?: 0,
        libVersion = extensionLib.toDoubleOrNull() ?: 1.6,
        lang = if (lang.size == 1) lang.first() else "all",
        isNsfw = contentWarning == "CONTENT_WARNING_NSFW" || contentWarning == "CONTENT_WARNING_MIXED",
        apkUrl = resources.apkUrl,
        iconUrl = resources.iconUrl ?: "",
        sources = sources.map {
            Extension.Available.Source(
                id = it.id.toLongOrNull() ?: 0,
                lang = it.language,
                name = it.name,
                baseUrl = it.homeUrl,
            )
        },
    )
}
