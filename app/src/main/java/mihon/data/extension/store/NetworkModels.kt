package mihon.data.extension.model

import kotlinx.serialization.Serializable

/**
 * Network models for the legacy extension repo format (`index.min.json` / `repo.json`),
 * which the vast majority of extension repositories use. Kept minimal: no protobuf store
 * needs are handled here; only the classic JSON index that SY/Tachiyomi repos expose.
 */

@Serializable
data class NetworkLegacyExtensionRepo(
    val meta: Meta,
    val indexV2: String? = null,
) {
    @Serializable
    data class Meta(
        val name: String,
        val shortName: String? = null,
        val website: String = "",
        val signingKeyFingerprint: String = "",
    )
}

@Serializable
data class NetworkLegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int = 0,
    val sources: List<Source>? = null,
) {
    @Serializable
    data class Source(
        val id: Long,
        val lang: String,
        val name: String,
        val baseUrl: String,
    )

    fun toAvailable(storeBaseUrl: String): eu.kanade.tachiyomi.extension.model.Extension.Available {
        return eu.kanade.tachiyomi.extension.model.Extension.Available(
            name = name.substringAfter("Tachiyomi: "),
            pkgName = pkg,
            versionName = version,
            versionCode = code,
            libVersion = version.substringBeforeLast('.').toDoubleOrNull() ?: 1.6,
            lang = lang,
            isNsfw = nsfw == 1,
            apkUrl = "$storeBaseUrl/apk/$apk",
            iconUrl = "$storeBaseUrl/icon/$pkg.png",
            sources = sources.orEmpty().map {
                eu.kanade.tachiyomi.extension.model.Extension.Available.Source(
                    id = it.id,
                    lang = it.lang,
                    name = it.name,
                    baseUrl = it.baseUrl,
                )
            },
        )
    }
}