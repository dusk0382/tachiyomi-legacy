package net.spin.tachiyomi.legacy

import android.app.Application
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
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

    override fun onCreate() {
        super.onCreate()
        preferenceStore = AndroidPreferenceStore(this)
        networkPreferences = NetworkPreferences(preferenceStore)
        networkHelper = NetworkHelper(this, networkPreferences, BuildConfig.DEBUG)
        sourcePreferences = SourcePreferences(preferenceStore)
        extensionManager = ExtensionManager(
            context = this,
            preferences = sourcePreferences,
            trustExtension = TrustExtension(sourcePreferences),
            loader = ExtensionLoader(sourcePreferences, TrustExtension(sourcePreferences)),
            api = ExtensionApi(networkHelper),
        )
    }
}
