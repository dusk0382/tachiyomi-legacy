package eu.kanade.domain.source.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SourcePreferences(
    preferenceStore: PreferenceStore,
) {

    val enabledLanguages: Preference<Set<String>> = preferenceStore.getStringSet(
        "source_languages",
        setOf("es", "en"),
    )

    val showNsfwSource: Preference<Boolean> = preferenceStore.getBoolean("show_nsfw_source", true)

    val trustedExtensions: Preference<Set<String>> = preferenceStore.getStringSet(
        "trusted_extensions",
        emptySet(),
    )
}
