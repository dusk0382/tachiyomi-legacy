package net.spin.tachiyomi.legacy

import android.app.Application
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.SourceManager
import net.spin.tachiyomi.legacy.data.db.LibraryRepository
import net.spin.tachiyomi.legacy.kotatsu.KotatsuSourceManager
import net.spin.tachiyomi.legacy.util.ImageLoader
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore

class App : Application() {

    lateinit var preferenceStore: PreferenceStore
        private set

    lateinit var networkHelper: NetworkHelper
        private set

    lateinit var networkPreferences: NetworkPreferences
        private set

    lateinit var sourcePreferences: SourcePreferences
        private set

    lateinit var extensionManager: ExtensionManager
        private set

    lateinit var libraryRepository: LibraryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        preferenceStore = AndroidPreferenceStore(this)
        networkPreferences = NetworkPreferences(preferenceStore)
        networkHelper = NetworkHelper(this, networkPreferences, BuildConfig.DEBUG)
        sourcePreferences = SourcePreferences(preferenceStore)
        libraryRepository = LibraryRepository(this)
        val trustExtension = TrustExtension(sourcePreferences)
        extensionManager = ExtensionManager(
            context = this,
            preferences = sourcePreferences,
            trustExtension = trustExtension,
            loader = ExtensionLoader(sourcePreferences, trustExtension),
            api = ExtensionApi(networkHelper),
        )
        SourceManager.registerExtensions(extensionManager.installedExtensions)
        // Las fuentes Kotatsu se registran desde el arranque: el lector directo
        // (historial/favoritos) y el detalle las necesitan sin pasar por Explorar.
        KotatsuSourceManager.init(networkHelper)
        KotatsuSourceManager.registerAll()
        ImageLoader.init(this, networkHelper)
    }
}
