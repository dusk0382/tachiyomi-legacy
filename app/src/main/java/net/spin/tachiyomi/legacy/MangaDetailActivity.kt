package net.spin.tachiyomi.legacy

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

        loadDetails()
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

        lifecycleScope.launch {
            val detailResult = OnlineRepository.fetchMangaDetails(sourceId, smanga)
            detailResult.onSuccess {
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

            val chaptersResult = OnlineRepository.fetchChapterList(sourceId, smanga)
            chaptersResult.onSuccess { chapters ->
                renderChapters(chapters)
            }.onFailure {
                binding.chaptersProgress.visibility = View.GONE
                Toast.makeText(this@MangaDetailActivity, "Error capítulos: ${it.message}", Toast.LENGTH_SHORT).show()
            }

            updateFavoriteIcon()
        }
    }

    private fun renderChapters(chapters: List<SChapter>) {
        binding.chaptersProgress.visibility = View.GONE
        binding.chaptersContainer.removeAllViews()

        // Persist the chapter list for later offline/progress use.
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

        chapters.forEach { chapter ->
            binding.chaptersContainer.addView(chapterRow(chapter))
        }
    }

    private fun chapterRow(chapter: SChapter): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            background = androidx.core.content.ContextCompat.getDrawable(this@MangaDetailActivity, android.R.drawable.list_selector_background)
        }
        row.addView(TextView(this).apply {
            text = chapter.name
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
            openReader(chapter)
        }
        return row
    }

    /** Fecha relativa para lo reciente ("hace 3 dias"), dd/MM/yyyy para lo antiguo. */
    private fun formatUploadDate(dateMillis: Long): String = TimeUtil.formatRelative(dateMillis)

    private fun openReader(chapter: SChapter) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_SOURCE_ID, sourceId)
            putExtra(ReaderActivity.EXTRA_CHAPTER_URL, chapter.url)
            putExtra(ReaderActivity.EXTRA_CHAPTER_NAME, chapter.name)
            putExtra(ReaderActivity.EXTRA_MANGA_URL, mangaUrl)
            putExtra(ReaderActivity.EXTRA_MANGA_TITLE, mangaTitle)
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