package net.spin.tachiyomi.legacy

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.spin.tachiyomi.legacy.data.db.LibraryRepository
import net.spin.tachiyomi.legacy.data.model.ChapterRef
import net.spin.tachiyomi.legacy.data.model.MangaRef
import net.spin.tachiyomi.legacy.data.online.OnlineRepository
import net.spin.tachiyomi.legacy.databinding.ActivityMangaDetailBinding
import net.spin.tachiyomi.legacy.util.ImageLoader
import net.spin.tachiyomi.legacy.util.TimeUtil

/**
 * Manga detail: shows the fetched details plus the chapter list.
 * Chapters are clickable to open the reader; favorites can be toggled.
 */
class MangaDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMangaDetailBinding
    private lateinit var repository: LibraryRepository

    private var sourceId: Long = 0
    private var mangaUrl = ""
    private var mangaTitle = ""

    private var manga: SManga? = null
    private var isFavorite = false
    private var isPrivate = false
    private var isDownloading = false

    private var chapters: List<SChapter> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = (application as App).libraryRepository

        sourceId = intent.getLongExtra("source_id", 0)
        mangaUrl = intent.getStringExtra("manga_url") ?: ""
        mangaTitle = intent.getStringExtra("manga_title") ?: ""
        binding.titleText.text = mangaTitle

        binding.btnBack.setOnClickListener { finish() }

        binding.btnFavorite.setOnClickListener {
            val m = manga ?: return@setOnClickListener
            if (isFavorite) {
                repository.removeFavorite(sourceId, mangaUrl)
                Toast.makeText(this, "Quitado de favoritos", Toast.LENGTH_SHORT).show()
            } else {
                repository.addFavorite(
                    MangaRef(
                        sourceId = sourceId,
                        url = mangaUrl,
                        title = m.title,
                        author = m.author,
                        artist = m.artist,
                        thumbnailUrl = m.thumbnail_url,
                        description = m.description,
                        genre = m.genre,
                        status = m.status,
                    ),
                )
                Toast.makeText(this, "Añadido a favoritos", Toast.LENGTH_SHORT).show()
            }
            updateFavoriteIcon()
        }

        binding.btnPrivate.setOnClickListener { onPrivateClick() }
        binding.btnDownload.setOnClickListener { onDownloadClick() }

        loadDetails()
    }

    /** Candado: mover a / sacar de la carpeta privada (quita de historial y favoritos). */
    private fun onPrivateClick() {
        if (isPrivate) {
            AlertDialog.Builder(this)
                .setTitle("¿Sacar de la carpeta privada?")
                .setMessage("'$mangaTitle' volverá a mostrarse en las fuentes.")
                .setPositiveButton("Sacar") { _, _ ->
                    repository.removePrivateOnline(sourceId, mangaUrl)
                    isPrivate = false
                    updatePrivateIcon()
                    Toast.makeText(this, "Sacado de la carpeta privada", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("¿Mover a carpeta privada?")
                .setMessage(
                    "'$mangaTitle' se ocultará de historial y favoritos. " +
                        "Para verlo de nuevo, entra en modo privado (SecureFolderActivate)."
                )
                .setPositiveButton("Mover") { _, _ ->
                    repository.addPrivateOnline(
                        net.spin.tachiyomi.legacy.data.model.PrivateRef(
                            sourceId = sourceId,
                            url = mangaUrl,
                            title = manga?.title ?: mangaTitle,
                            thumbnailUrl = manga?.thumbnail_url,
                        ),
                    )
                    repository.removeHistory(sourceId, mangaUrl)
                    repository.removeFavorite(sourceId, mangaUrl)
                    isPrivate = true
                    updatePrivateIcon()
                    Toast.makeText(this, "Movido a carpeta privada", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    /** Descargar: lanza la descarga de todos los capítulos, o elimina la existente. */
    private fun onDownloadClick() {
        if (isDownloading) return

        if (MangaDownloader.isMangaDownloaded(mangaTitle)) {
            AlertDialog.Builder(this)
                .setTitle("¿Eliminar descarga?")
                .setMessage("Se borrarán los capítulos descargados de '$mangaTitle'.")
                .setPositiveButton("Eliminar") { _, _ ->
                    MangaDownloader.deleteManga(mangaTitle)
                    updateDownloadIcon()
                    refreshChapterRows()
                    Toast.makeText(this, "Descarga eliminada", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        if (chapters.isEmpty()) {
            Toast.makeText(this, "Espera a que carguen los capítulos", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("¿Descargar el manga?")
            .setMessage("Se descargarán ${chapters.size} capítulos a 'Descargas/MangaLite/${mangaTitle}'.\nPuedes seguir usando la app mientras descarga.")
            .setPositiveButton("Descargar") { _, _ -> startDownload() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startDownload() {
        val list = chapters
        if (list.isEmpty()) return

        val source = runCatching { SourceManager.getOrThrow(sourceId) }
            .getOrElse {
                Toast.makeText(this, "Fuente no disponible", Toast.LENGTH_SHORT).show()
                return
            }

        isDownloading = true
        binding.btnDownload.isEnabled = false
        binding.btnDownload.setImageResource(android.R.drawable.ic_popup_sync)
        binding.downloadStatus.visibility = View.VISIBLE
        binding.downloadStatus.text = "Descargando 0/${list.size}..."

        lifecycleScope.launch(Dispatchers.IO) {
            MangaDownloader.downloadManga(source, list, mangaTitle) { done, total ->
                runOnUiThread {
                    binding.downloadStatus.text = "Descargando $done/$total..."
                }
            }

            runOnUiThread {
                isDownloading = false
                binding.btnDownload.isEnabled = true
                updateDownloadIcon()
                refreshChapterRows()
                binding.downloadStatus.text = "Descarga completa"
                binding.downloadStatus.postDelayed({
                    binding.downloadStatus.visibility = View.GONE
                }, 3000)
                Toast.makeText(
                    this@MangaDetailActivity,
                    "Descarga completa",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun updatePrivateIcon() {
        isPrivate = repository.isPrivateOnline(sourceId, mangaUrl)
        binding.btnPrivate.setImageResource(
            if (isPrivate) android.R.drawable.ic_lock_idle_lock
            else android.R.drawable.ic_lock_lock,
        )
        binding.btnPrivate.contentDescription =
            if (isPrivate) "En carpeta privada" else "Mover a carpeta privada"
    }

    private fun updateDownloadIcon() {
        binding.btnDownload.setImageResource(
            if (MangaDownloader.isMangaDownloaded(mangaTitle)) {
                android.R.drawable.ic_menu_agenda
            } else {
                android.R.drawable.ic_menu_save
            },
        )
        binding.btnDownload.contentDescription =
            if (MangaDownloader.isMangaDownloaded(mangaTitle)) "Descargado (tocar para eliminar)" else "Descargar manga"
    }

    private fun loadDetails() {
        val source = SourceManager.getByIdOrNull(sourceId) ?: run {
            Toast.makeText(this, "Fuente no instalada", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val smanga = SManga.create().apply {
            url = mangaUrl
            title = mangaTitle
        }

        binding.cover.setImageResource(android.R.color.darker_gray)
        intent.getStringExtra("manga_thumb")?.let {
            ImageLoader.load(it, binding.cover)
        }

        // Detalles y capitulos se cargan EN PARALELO y cada uno renderiza
        // en cuanto llega (sin que la lista espere a que acabe el detalle).
        lifecycleScope.launch {
            coroutineScope {
                val details = async { OnlineRepository.fetchMangaDetails(sourceId, smanga) }
                val chapters = async { OnlineRepository.fetchChapterList(sourceId, smanga) }

                launch {
                    details.await().onSuccess {
                        manga = it
                        binding.titleText.text = it.title
                        binding.authorText.text = listOfNotNull(it.author, it.artist).filter { it.isNotBlank() }.joinToString(" · ")
                        binding.statusText.text = statusLabel(it.status)
                        binding.genreText.text = it.genre
                        binding.descriptionText.text = it.description?.trim()
                        it.thumbnail_url?.let { thumb -> ImageLoader.load(thumb, binding.cover) }

                        // Historial: registrar el manga como visto recientemente,
                        // conservando el progreso del ultimo capitulo si ya existia.
                        val existing = repository.getHistoryEntry(sourceId, it.url)
                        repository.upsertHistory(
                            net.spin.tachiyomi.legacy.data.model.HistoryRef(
                                sourceId = sourceId,
                                url = it.url,
                                title = it.title,
                                thumbnailUrl = it.thumbnail_url,
                                lastChapterUrl = existing?.lastChapterUrl,
                                lastChapterName = existing?.lastChapterName,
                                lastPageIndex = existing?.lastPageIndex ?: 0,
                                lastTotalPages = existing?.lastTotalPages ?: 0,
                            ),
                        )
                    }.onFailure {
                        Toast.makeText(this@MangaDetailActivity, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                launch {
                    chapters.await().onSuccess { list ->
                        renderChapters(list)
                    }.onFailure {
                        binding.chaptersProgress.visibility = View.GONE
                        Toast.makeText(this@MangaDetailActivity, "Error capítulos: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            updateFavoriteIcon()
        }

        updatePrivateIcon()
        updateDownloadIcon()
    }

    private fun renderChapters(chapters: List<SChapter>) {
        binding.chaptersProgress.visibility = View.GONE
        this.chapters = chapters
        refreshChapterRows()

        // Persist the chapter list for later offline/progress use.
        // En background: no bloquear el renderizado de la lista en pantalla.
        lifecycleScope.launch(Dispatchers.IO) {
            repository.upsertChapters(
                chapters.map {
                    ChapterRef(
                        sourceId = sourceId,
                        mangaUrl = mangaUrl,
                        url = it.url,
                        name = it.name,
                        scanlator = it.scanlator,
                        chapterNumber = it.chapter_number.toDouble(),
                        uploadDate = it.date_upload,
                    )
                },
            )
        }

        // Si venimos del historial, abrir directamente el capitulo donde se dejo.
        val openChapterUrl = intent.getStringExtra("open_chapter_url")
        if (!openChapterUrl.isNullOrBlank()) {
            val target = chapters.firstOrNull { it.url == openChapterUrl }
            if (target != null) {
                val openPage = intent.getIntExtra("open_chapter_page", -1)
                binding.chaptersContainer.post {
                    if (!isFinishing && !isDestroyed) {
                        openReader(target, openPage)
                    }
                }
            }
        }
    }

    private fun chapterRow(chapter: SChapter): View {
        val openChapterUrl = intent.getStringExtra("open_chapter_url")
        val isCurrent = !openChapterUrl.isNullOrBlank() && chapter.url == openChapterUrl
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            background = if (isCurrent) {
                androidx.core.content.ContextCompat.getDrawable(
                    this@MangaDetailActivity,
                    R.drawable.item_current_chapter,
                )
            } else {
                androidx.core.content.ContextCompat.getDrawable(this@MangaDetailActivity, android.R.drawable.list_selector_background)
            }
        }
        val downloaded = MangaDownloader.chapterFile(mangaTitle, chapter.name, chapter.url) != null
        row.addView(TextView(this).apply {
            text = (if (downloaded) "⬇ " else "") + chapter.name
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
        })
        if (chapter.date_upload > 0L) {
            row.addView(TextView(this).apply {
                text = formatUploadDate(chapter.date_upload)
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 2, 0, 0)
            })
        }
        row.setOnClickListener {
            // Si el capitulo esta descargado, abrir el CBZ local (offline).
            val local = MangaDownloader.chapterFile(mangaTitle, chapter.name, chapter.url)
            if (local != null) {
                openLocalReader(local, chapter.name)
            } else {
                openReader(chapter)
            }
        }
        return row
    }

    /** Re-renderiza solo las filas (sin re-persistir ni re-disparar el auto-open). */
    private fun refreshChapterRows() {
        binding.chaptersContainer.removeAllViews()
        chapters.forEach { chapter ->
            binding.chaptersContainer.addView(chapterRow(chapter))
        }
    }

    private fun openLocalReader(file: java.io.File, chapterName: String) {
        val lastPage = Prefs.getLastPage(file.absolutePath)
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_PATH, file.absolutePath)
            putExtra(ReaderActivity.EXTRA_TITLE, chapterName)
            putExtra(ReaderActivity.EXTRA_LAST_PAGE, lastPage)
        }
        startActivity(intent)
    }

    /** Fecha relativa para lo reciente ("hace 3 dias"), dd/MM/yyyy para lo antiguo. */
    private fun formatUploadDate(dateMillis: Long): String = TimeUtil.formatRelative(dateMillis)

    private fun openReader(chapter: SChapter, openPage: Int = -1) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_SOURCE_ID, sourceId)
            putExtra(ReaderActivity.EXTRA_CHAPTER_URL, chapter.url)
            putExtra(ReaderActivity.EXTRA_CHAPTER_NAME, chapter.name)
            putExtra(ReaderActivity.EXTRA_MANGA_URL, mangaUrl)
            putExtra(ReaderActivity.EXTRA_MANGA_TITLE, mangaTitle)
            if (openPage >= 0) putExtra(ReaderActivity.EXTRA_LAST_PAGE, openPage)
            putExtra(
                ReaderActivity.EXTRA_CHAPTER_URLS,
                ArrayList(chapters.map { it.url }),
            )
            putExtra(
                ReaderActivity.EXTRA_CHAPTER_NAMES,
                ArrayList(chapters.map { it.name }),
            )
        }
        startActivity(intent)
    }

    private fun updateFavoriteIcon() {
        isFavorite = repository.isFavorite(sourceId, mangaUrl)
        binding.btnFavorite.setImageResource(
            if (isFavorite) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off,
        )
    }

    private fun statusLabel(status: Int): String {
        return when (status) {
            1 -> "En emisión"
            2 -> "Completado"
            3 -> "Cancelado"
            4 -> "En hiato"
            else -> ""
        }
    }
}