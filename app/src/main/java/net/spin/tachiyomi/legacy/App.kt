package net.spin.tachiyomi.legacy

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        preferenceStore = AndroidPreferenceStore(this)
        networkPreferences = NetworkPreferences(preferenceStore)
        networkHelper = NetworkHelper(this, networkPreferences, BuildConfig.DEBUG)
    }
}
