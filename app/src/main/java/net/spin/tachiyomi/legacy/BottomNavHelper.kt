package net.spin.tachiyomi.legacy

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Wiring for the shared bottom navigation across the three main screens
 * (Library / Discover / Extensions). Navigation uses Intents with CLEAR_TOP
 * so the back stack stays shallow and the selected tab is highlighted.
 */
object BottomNavHelper {

    const val TAB_LIBRARY = R.id.nav_library
    const val TAB_DISCOVER = R.id.nav_discover
    const val TAB_EXTENSIONS = R.id.nav_extensions

    fun setup(activity: AppCompatActivity, nav: BottomNavigationView, current: Int) {
        nav.selectedItemId = current
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == current) {
                return@setOnItemSelectedListener true
            }
            val target = when (item.itemId) {
                TAB_LIBRARY -> LibraryActivity::class.java
                TAB_DISCOVER -> BrowseActivity::class.java
                TAB_EXTENSIONS -> ExtensionsActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            activity.startActivity(
                Intent(activity, target)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            activity.finish()
            true
        }
    }
}
