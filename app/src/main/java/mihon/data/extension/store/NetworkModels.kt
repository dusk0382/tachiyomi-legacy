package mihon.data.extension.model

import kotlinx.serialization.Serializable

/**
 * Network models for the extension repo formats.
 *
 * Two formats are supported:
 *  - Legacy: `index.min.json` (JSON array) + `repo.json` (`NetworkLegacyExtensionRepo`).
 *  - V2 (Mihon): `index.json` (`NetworkExtensionStore` with an inline `extensionList`,
 *    or an `extensionListUrl` pointing to the extension list).
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

/** V2 store format used by Mihon-compatible repos (keiyoushi, etc.). */
@Serializable
data class NetworkExtensionStore(
    val name: String,
    val badgeLabel: String? = null,
    val signingKey: String? = null,
    val contact: Contact? = null,
    val extensionList: ExtensionList? = null,
    val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        val website: String = "",
        val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(val extensions: List<Extension>)

    @Serializable
    data class Extension(
        val name: String,
        val packageName: String,
        val resources: Resources,
        val extensionLib: String,
        val versionCode: String,
        val versionName: String,
        val contentWarning: String? = null,
        val sources: List<Source>,
    )

    @Serializable
    data class Resources(
        val apkUrl: String,
        val iconUrl: String? = null,
        val jarUrl: String? = null,
    )

    @Serializable
    data class Source(
        val id: String,
        val name: String,
        val language: String,
        val homeUrl: String = "",
    )
}
