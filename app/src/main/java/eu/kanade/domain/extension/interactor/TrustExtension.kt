package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.source.service.SourcePreferences
import tachiyomi.core.common.preference.getAndSet

class TrustExtension(
    private val preferences: SourcePreferences,
) {

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return key in preferences.trustedExtensions.get() || fingerprints.any { it in preferences.trustedExtensions.get() }
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions.getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()
            removed.add("$pkgName:$versionCode:$signatureHash")
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions.delete()
    }
}