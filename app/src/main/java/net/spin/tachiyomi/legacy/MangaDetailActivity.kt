package net.spin.tachiyomi.legacy

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * La lista usa un único RecyclerView (header + capítulos) que virtualiza las
 * filas: mangas con 1000+ capítulos ya no crean miles de vistas en memoria.
 */
class MangaDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMangaDetailBinding
    private lateinit var repository: LibraryRepository
    private lateinit var adapter: DetailAdapter

    private var sourceId: Long = 0
    private var mangaUrl = ""
    private var mangaTitle = ""

    private var manga: SManga? = null
    private var isFavorite = false
    private var isPrivate = false
    private var isDownloading = false

    private var chapters: List<SChapter> = emptyList()
    private var downloadedNames: Set<String> = emptySet()

    /** Estado pendiente del header: se aplica cuando el RecyclerView lo bindea. */
    private var pendingThumb: String? = null
    private var detailsLoaded = false

    /** Modo selección: long-press en un capítulo activa checkboxes para descarga selectiva. */
    private var selectionMode = false
    private val selectedChapters = LinkedHashSet<String>()
    private val chapterCheckboxes = HashMap<String, CheckBox>()

    private var header: HeaderHolder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = (application as App).libraryRepository

        sourceId = intent.getLongExtra("source_id", 0)
        mangaUrl = intent.getStringExtra("manga_url") ?: ""
        mangaTitle = intent.getStringExtra("manga_title") ?: ""
        binding.titleText.text = mangaTitle

        adapter = DetailAdapter()
        binding.chaptersContainer.layoutManager = LinearLayoutManager(this)
        binding.chaptersContainer.itemAnimator = null
        binding.chaptersContainer.adapter = adapter

        binding.btnBack.setOnClickListener {
            if (selectionMode) exitSelection() else finish()
        }

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

    /** Descargar: en modo selección descarga solo los marcados; si no, todos o elimina. */
    private fun onDownloadClick() {
        if (isDownloading) return

        if (selectionMode) {
            if (selectedChapters.isEmpty()) {
                Toast.makeText(this, "Mantén pulsado los capítulos que quieras descargar", Toast.LENGTH_SHORT).show()
                return
            }
            val selected = chapters.filter { selectedChapters.contains(it.url) }
            if (selected.isEmpty()) return
            AlertDialog.Builder(this)
                .setTitle("¿Descargar seleccionados?")
                .setMessage("Se descargarán ${selected.size} capítulos a 'Descargas/MangaLite/$mangaTitle'.\\nPuedes seguir usando la app mientras descarga.")
                .setPositiveButton("Descargar") { _, _ -> startDownload(selected) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        if (MangaDownloader.isMangaDownloaded(mangaTitle)) {
            AlertDialog.Builder(this)
                .setTitle("¿Eliminar descarga?")
                .setMessage("Se borrarán los capítulos descargados de '$mangaTitle'.")
                .setPositiveButton("Eliminar") { _, _ ->
                    MangaDownloader.deleteManga(mangaTitle)
                    refreshDownloadedState()
                    updateDownloadIcon()
                    adapter.notifyDataSetChanged()
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
            .setMessage(
                "Se descargarán ${chapters.size} capítulos a 'Descargas/MangaLite/$mangaTitle'.\\n" +
                    "Puedes seguir usando la app mientras descarga.\\n\\n" +
                    "💡 ¿Solo algunos? Mantén pulsado un capítulo para elegirlos."
            )
            .setPositiveButton("Descargar todos") { _, _ -> startDownload(chapters) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Modo selección: entrada, toggle, salida y estado. */
    private fun enterSelection() {
        selectionMode = true
        selectedChapters.clear()
        updateSelectionStatus()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(url: String) {
        if (!selectedChapters.add(url)) selectedChapters.remove(url)
        chapterCheckboxes[url]?.isChecked = selectedChapters.contains(url)
        updateSelectionStatus()
    }

    private fun updateSelectionStatus() {
        if (!selectionMode) return
        header?.downloadStatus?.visibility = View.VISIBLE
        header?.downloadStatus?.text =
            "${selectedChapters.size} seleccionados — toca ⬇ para descargar"
        binding.btnDownload.contentDescription =
            "Descargar ${selectedChapters.size} capítulos seleccionados"
    }

    private fun exitSelection() {
        selectionMode = false
        selectedChapters.clear()
        chapterCheckboxes.clear()
        header?.downloadStatus?.visibility = View.GONE
        adapter.notifyDataSetChanged()
        updateDownloadIcon()
    }

    private fun startDownload(list: List<SChapter>) {
        if (list.isEmpty()) return

        val source = runCatching { SourceManager.getOrThrow(sourceId) }
            .getOrElse {
                Toast.makeText(this, "Fuente no disponible", Toast.LENGTH_SHORT).show()
                return
            }

        isDownloading = true
        binding.btnDownload.isEnabled = false
        binding.btnDownload.setImageResource(android.R.drawable.ic_popup_sync)
        header?.downloadStatus?.visibility = View.VISIBLE
        header?.downloadStatus?.text = "Descargando 0/${list.size}..."

        lifecycleScope.launch(Dispatchers.IO) {
            MangaDownloader.downloadManga(source, list, mangaTitle) { done, total ->
                runOnUiThread {
                    header?.downloadStatus?.text = "Descargando $done/$total..."
                }
            }

            runOnUiThread {
                isDownloading = false
                binding.btnDownload.isEnabled = true
                if (selectionMode) {
                    exitSelection()
                } else {
                    updateDownloadIcon()
                }
                refreshDownloadedState()
                adapter.notifyDataSetChanged()
                header?.downloadStatus?.visibility = View.VISIBLE
                header?.downloadStatus?.text = "Descarga completa"
                header?.downloadStatus?.postDelayed({
                    header?.downloadStatus?.visibility = View.GONE
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

    /**
     * Recarga el set de CBZ descargados (una sola llamada a disco, en IO) y
     * re-renderiza la lista cuando llega — así el marcador ⬇ se actualiza.
     */
    private fun refreshDownloadedState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val names = MangaDownloader.downloadedCbzNames(mangaTitle)
            withContext(Dispatchers.Main) {
                downloadedNames = names
                adapter.notifyDataSetChanged()
            }
        }
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

        pendingThumb = intent.getStringExtra("manga_thumb")
        header?.cover?.setImageResource(android.R.color.darker_gray)

        // Detalles y capitulos se cargan EN PARALELO y cada uno renderiza
        // en cuanto llega (sin que la lista espere a que acabe el detalle).
        lifecycleScope.launch {
            coroutineScope {
                val details = async { OnlineRepository.fetchMangaDetails(sourceId, smanga) }
                val chapters = async { OnlineRepository.fetchChapterList(sourceId, smanga) }

                launch {
                    details.await().onSuccess {
                        manga = it
                        detailsLoaded = true
                        binding.titleText.text = it.title
                        it.thumbnail_url?.let { pendingThumb = it }
                        applyHeaderDetails()

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
                        header?.chaptersProgress?.visibility = View.GONE
                        Toast.makeText(this@MangaDetailActivity, "Error capítulos: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            updateFavoriteIcon()
        }

        updatePrivateIcon()
        updateDownloadIcon()
        refreshDownloadedState()
    }

    private fun renderChapters(chapters: List<SChapter>) {
        header?.chaptersProgress?.visibility = View.GONE
        this.chapters = chapters
        adapter.notifyDataSetChanged()

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

    /** Fecha relativa para lo reciente ("hace 3 dias"), dd/MM/yyyy para lo antiguo. */
    private fun formatUploadDate(dateMillis: Long): String = TimeUtil.formatRelative(dateMillis)

    private fun openLocalReader(file: java.io.File, chapterName: String) {
        val lastPage = Prefs.getLastPage(file.absolutePath)
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_PATH, file.absolutePath)
            putExtra(ReaderActivity.EXTRA_TITLE, chapterName)
            putExtra(ReaderActivity.EXTRA_LAST_PAGE, lastPage)
        }
        startActivity(intent)
    }

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

    override fun onBackPressed() {
        if (selectionMode) {
            exitSelection()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------
    // Adapter: item 0 = header (detalle), resto = capítulos.
    // ------------------------------------------------------------------

    private inner class DetailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = chapters.size + 1

        override fun getItemViewType(position: Int): Int =
            if (position == 0) TYPE_HEADER else TYPE_CHAPTER

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(inflater.inflate(R.layout.item_detail_header, parent, false))
            } else {
                ChapterHolder(inflater.inflate(R.layout.item_chapter_row, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> {
                    header = holder
                    bindHeader(holder)
                }
                is ChapterHolder -> {
                    val chapter = chapters[position - 1]
                    holder.bind(chapter, position - 1)
                }
            }
        }
    }

    private fun bindHeader(h: HeaderHolder) {
        if (detailsLoaded) {
            val it = manga
            if (it != null) {
                h.authorText.text = listOfNotNull(it.author, it.artist).filter { it.isNotBlank() }.joinToString(" · ")
                h.statusText.text = statusLabel(it.status)
                h.genreText.text = it.genre
                h.descriptionText.text = it.description?.trim()
            }
        }
        pendingThumb?.let { thumb -> ImageLoader.load(thumb, h.cover) }

        h.downloadStatus.visibility = if (selectionMode) View.VISIBLE else View.GONE
        if (selectionMode) h.downloadStatus.text =
            "${selectedChapters.size} seleccionados — toca ⬇ para descargar"
    }

    /** Re-aplica los detalles al header si ya está bindeado (o queda pendiente). */
    private fun applyHeaderDetails() {
        val h = header ?: return
        val it = manga ?: return
        h.authorText.text = listOfNotNull(it.author, it.artist).filter { it.isNotBlank() }.joinToString(" · ")
        h.statusText.text = statusLabel(it.status)
        h.genreText.text = it.genre
        h.descriptionText.text = it.description?.trim()
        pendingThumb?.let { thumb -> ImageLoader.load(thumb, h.cover) }
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val authorText: TextView = view.findViewById(R.id.authorText)
        val statusText: TextView = view.findViewById(R.id.statusText)
        val genreText: TextView = view.findViewById(R.id.genreText)
        val descriptionText: TextView = view.findViewById(R.id.descriptionText)
        val downloadStatus: TextView = view.findViewById(R.id.downloadStatus)
        val chaptersProgress: ProgressBar = view.findViewById(R.id.chaptersProgress)
    }

    private inner class ChapterHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val checkbox: CheckBox = view.findViewById(R.id.chapterCheck)
        private val name: TextView = view.findViewById(R.id.chapterName)
        private val date: TextView = view.findViewById(R.id.chapterDate)

        fun bind(chapter: SChapter, index: Int) {
            val downloaded = MangaDownloader.isDownloaded(chapter.name, chapter.url, downloadedNames)
            checkbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkbox.isChecked = selectedChapters.contains(chapter.url)
            if (selectionMode) chapterCheckboxes[chapter.url] = checkbox

            name.text = (if (downloaded) "⬇ " else "") + chapter.name

            if (chapter.date_upload > 0L) {
                date.text = formatUploadDate(chapter.date_upload)
                date.visibility = View.VISIBLE
            } else {
                date.visibility = View.GONE
            }

            itemView.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(chapter.url)
                } else if (MangaDownloader.isDownloaded(chapter.name, chapter.url, downloadedNames)) {
                    // Si el capitulo esta descargado, abrir el CBZ local (offline).
                    MangaDownloader.chapterFile(mangaTitle, chapter.name, chapter.url)?.let {
                        openLocalReader(it, chapter.name)
                    } ?: openReader(chapter)
                } else {
                    openReader(chapter)
                }
            }
            itemView.setOnLongClickListener {
                if (!isDownloading) {
                    if (!selectionMode) enterSelection()
                    toggleSelection(chapter.url)
                }
                true
            }
            val openChapterUrl = intent.getStringExtra("open_chapter_url")
            val isCurrent = !openChapterUrl.isNullOrBlank() && chapter.url == openChapterUrl
            itemView.background = ContextCompat.getDrawable(
                this@MangaDetailActivity,
                if (isCurrent) R.drawable.item_current_chapter
                else android.R.drawable.list_selector_background,
            )
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHAPTER = 1
    }
}
