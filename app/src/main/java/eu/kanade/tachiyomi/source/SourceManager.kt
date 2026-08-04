package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.extension.model.Extension
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds all sources available from the installed extensions. Sources are
 * keyed by their unique [Source.id]; the map is repopulated whenever the
 * installed extensions are reloaded.
 */
object SourceManager {

    private val sources = ConcurrentHashMap<Long, Source>()

    /** Registers all sources from the given installed extensions. */
    fun registerExtensions(extensions: List<Extension.Installed>) {
        val next = ConcurrentHashMap<Long, Source>()
        extensions.forEach { ext ->
            ext.sources.forEach { source ->
                next[source.id] = source
            }
        }
        sources.clear()
        sources.putAll(next)
    }

    /** Clears the registry. */
    fun clear() {
        sources.clear()
    }

    fun get(id: Long): Source? = sources[id]

    fun getAll(): List<Source> = sources.values.toList()

    fun getOrThrow(id: Long): Source = sources[id]
        ?: throw Exception("Source with id $id is not installed")

    fun getByIdOrNull(id: Long): Source? = sources[id]
}