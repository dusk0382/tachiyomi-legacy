package net.spin.tachiyomi.legacy

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

object MangaScanner {

    fun scan(context: Context): List<MangaFile> {
        val results = mutableSetOf<MangaFile>()
        val roots = mutableListOf<File>()

        roots.add(Environment.getExternalStorageDirectory())
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))

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

        for (root in roots.distinct()) {
            scanDir(root, results, depth = 0, maxDepth = 3)
        }

        return results.toList()
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
