package net.spin.tachiyomi.legacy

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File

object MangaScanner {

    fun scan(context: Context): List<MangaFile> {
        val results = mutableSetOf<MangaFile>()
        val roots = mutableListOf<File>()

        roots.add(Environment.getExternalStorageDirectory())
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))

        // NOTA: las descargas de la app (carpeta MangaLite) NO se escanean aqui:
        // tienen su propia pestaña "Descargas" (ver scanDir, que las salta).

        // Volumenes de almacenamiento del sistema (incluye la tarjeta SD externa).
        roots.addAll(storageVolumeRoots(context))

        // Rutas clasicas de montaje de la SD externa.
        roots.addAll(sdCardMountRoots())

        val externalDirs = ContextCompat.getExternalFilesDirs(context, null)

        for (dir in externalDirs) {
            if (dir != null && !dir.absolutePath.contains("emulated", ignoreCase = true)) {
                var current = dir

                while (current.parentFile != null && current.parentFile?.canRead() == true) {
                    current = current.parentFile
                }

                if (current.canRead() && !roots.contains(current)) {
                    roots.add(current)
                }
            }
        }

        val secondaryStorage = System.getenv("SECONDARY_STORAGE")
        if (!secondaryStorage.isNullOrEmpty()) {
            secondaryStorage.split(":").forEach { path ->
                val file = File(path)
                if (file.exists() && file.canRead() && !roots.contains(file)) {
                    roots.add(file)
                }
            }
        }

        for (root in roots.distinct().filter { it.exists() && it.canRead() }) {
            scanDir(root, results, depth = 0, maxDepth = 3)
        }

        return results.toList()
    }

    /**
     * Descubre los volumenes de almacenamiento montados (interno y SD externa)
     * usando StorageManager. Funciona en Android 6 (getVolumeList, deprecated)
     * y en versiones nuevas (getStorageVolumes).
     */
    private fun storageVolumeRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return roots

        try {
            val volumes = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // API 24+: StorageManager.getStorageVolumes()
                    sm.javaClass.getMethod("getStorageVolumes").invoke(sm) as? Array<*>
                } else {
                    // API 23 (Android 6): StorageManager.getVolumeList()
                    sm.javaClass.getMethod("getVolumeList").invoke(sm) as? Array<*>
                }
            } catch (_: Exception) {
                null
            }

            for (volume in volumes.orEmpty()) {
                val vol = volume ?: continue
                try {
                    // StorageVolume.getDirectory() (API 30+) / getPathFile() (API 24-29)
                    // / getPath() (API 23, deprecated). Lo obtenemos por reflexion
                    // para cubrir todas las versiones sin romper la compilacion.
                    val file = try {
                        vol.javaClass.getMethod("getDirectory").invoke(vol) as? File
                    } catch (_: Exception) {
                        null
                    } ?: try {
                        vol.javaClass.getMethod("getPathFile").invoke(vol) as? File
                    } catch (_: Exception) {
                        null
                    }
                    if (file != null) {
                        roots.add(file)
                    } else {
                        val path = vol.javaClass.getMethod("getPath").invoke(vol) as? String
                        if (!path.isNullOrBlank()) roots.add(File(path))
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        return roots
    }

    /** Rutas clasicas donde Android monta la SD externa. */
    private fun sdCardMountRoots(): List<File> {
        val roots = mutableListOf<File>()
        for (base in listOf("/storage", "/mnt")) {
            val dir = File(base)
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                val name = child.name.lowercase()
                if (name.startsWith(".") || name == "emulated" || name == "self") continue
                roots.add(child)
            }
        }
        return roots
    }

    private fun scanDir(
        dir: File,
        results: MutableSet<MangaFile>,
        depth: Int,
        maxDepth: Int
    ) {
        if (!dir.canRead() || depth > maxDepth) return

        if (dir.name.startsWith(".") || dir.name.equals("Android", ignoreCase = true)) {
            return
        }

        // Las descargas de la app viven en su pestaña propia, no en Local.
        if (dir.name.equals("MangaLite", ignoreCase = true)) {
            return
        }

        val files = dir.listFiles() ?: return

        for (f in files) {
            if (f.isDirectory) {
                scanDir(f, results, depth + 1, maxDepth)
            } else if (f.isFile && f.extension.equals("cbz", ignoreCase = true)) {
                val safeFile = try {
                    f.canonicalFile
                } catch (e: Exception) {
                    f.absoluteFile
                }
                results.add(MangaFile(safeFile))
            }
        }
    }
}
