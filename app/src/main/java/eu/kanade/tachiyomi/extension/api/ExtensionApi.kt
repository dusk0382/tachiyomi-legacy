package eu.kanade.tachiyomi.extension.api

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
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo

/**
 * Fetches available extensions from one or more legacy extension repositories
 * (`index.min.json` + `repo.json`). Repositories are plain URLs passed in by
 * [ExtensionManager]; the canonical Tachiyomi repo and any user-added ones.
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
     * A repo base URL is e.g. "https://keiyoushi.github.io/extensions".
     */
    suspend fun findExtensions(repoBaseUrls: List<String>): List<Extension.Available> {
        return withContext(Dispatchers.IO) {
            supervisorScope {
                repoBaseUrls.map { baseUrl ->
                    async { fetchRepo(baseUrl) }
                }.awaitAll().flatten()
            }
        }
    }

    private suspend fun fetchRepo(baseUrl: String): List<Extension.Available> {
        return try {
            val index = network.client.newCall(GET("$baseUrl/index.min.json")).awaitSuccess()
            val storeBaseUrl = resolveStoreBaseUrl(baseUrl)
            index.body.string().let { body ->
                json.decodeFromString(ListSerializer(NetworkLegacyExtension.serializer()), body)
                    .map { it.toAvailable(storeBaseUrl) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveStoreBaseUrl(baseUrl: String): String {
        return try {
            val repo = network.client.newCall(GET("$baseUrl/repo.json")).awaitSuccess()
            repo.body.string().let { body ->
                val parsed = json.decodeFromString(NetworkLegacyExtensionRepo.serializer(), body)
                if (!parsed.indexV2.isNullOrBlank()) {
                    // repo.json points to a v2 index: use its folder as the base
                    parsed.indexV2.substringBeforeLast('/')
                } else {
                    baseUrl
                }
            }
        } catch (_: Exception) {
            baseUrl
        }
    }
}