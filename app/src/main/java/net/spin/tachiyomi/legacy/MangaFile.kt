package net.spin.tachiyomi.legacy

import java.io.File

data class MangaFile(
    val file: File,
    val title: String = file.nameWithoutExtension,
    val lastPage: Int = 0,
    val privateKey: String? = null
)
