package net.spin.tachiyomi.legacy

import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.spin.tachiyomi.legacy.databinding.ActivityReaderBinding

class ReaderActivity : AppCompatActivity(), ZoomableImageView.OnTapListener {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LAST_PAGE = "last_page"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_CHAPTER_URL = "chapter_url"
        const val EXTRA_CHAPTER_NAME = "chapter_name"
        const val EXTRA_MANGA_URL = "manga_url"
        const val EXTRA_MANGA_TITLE = "manga_title"
        const val EXTRA_CHAPTER_URLS = "chapter_urls"
        const val EXTRA_CHAPTER_NAMES = "chapter_names"
        const val EXTRA_SCREEN_W = "screen_w"
        const val EXTRA_SCREEN_H = "screen_h"
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var viewModel: ReaderViewModel

    private var chapterUrls: List<String> = emptyList()
    private var chapterNames: List<String> = emptyList()
    private var currentChapterIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Prefs.init(applicationContext)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)

        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        viewModel = ViewModelProvider(this)[ReaderViewModel::class.java]

        val path = intent.getStringExtra(EXTRA_PATH)
        val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, -1L)
        val chapterUrl = intent.getStringExtra(EXTRA_CHAPTER_URL)

        chapterUrls = intent.getStringArrayListExtra(EXTRA_CHAPTER_URLS) ?: emptyList()
        chapterNames = intent.getStringArrayListExtra(EXTRA_CHAPTER_NAMES) ?: emptyList()
        currentChapterIndex = chapterUrls.indexOfFirst { it == chapterUrl }
            .takeIf { it >= 0 } ?: 0

        when {
            path != null -> viewModel.initLocal(path, screenWidth, screenHeight)
            sourceId > 0 && !chapterUrl.isNullOrBlank() -> {
                val name = intent.getStringExtra(EXTRA_CHAPTER_NAME) ?: chapterUrl

                // El historial manda la pagina exacta: sincronizar Prefs antes de cargar.
                val historyPage = intent.getIntExtra(EXTRA_LAST_PAGE, -1)
                if (historyPage >= 0) {
                    Prefs.setLastPage("${sourceId}_$chapterUrl", historyPage)
                }

                val mangaTitle = intent.getStringExtra(EXTRA_MANGA_TITLE)
                viewModel.initOnline(
                    sourceId,
                    chapterUrl,
                    name,
                    screenWidth,
                    screenHeight,
                    mangaTitle,
                )
            }
            else -> {
                finish()
                return
            }
        }

        val adapter = PageAdapter(viewModel, this)

        binding.pager.adapter = adapter
        binding.pager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        binding.pager.offscreenPageLimit = 1

        applyReadingDirection()

        binding.directionButton.setOnClickListener { showDirectionDialog() }

        binding.prevChapterButton.setOnClickListener { goToChapter(currentChapterIndex - 1) }
        binding.nextChapterButton.setOnClickListener { goToChapter(currentChapterIndex + 1) }

        viewModel.pageCount.observe(this) { count ->
            adapter.notifyDataSetChanged()

            if (count == 0) {
                binding.pageInfo.text = if (viewModel.isReady.value == true) {
                    getString(R.string.no_pages)
                } else {
                    getString(R.string.loading_chapter)
                }
            } else {
                val lastPage = viewModel.currentPage.value
                    ?: intent.getIntExtra(EXTRA_LAST_PAGE, 0)
                val startPage = lastPage.coerceIn(0, count - 1)
                binding.pager.setCurrentItem(startPage, false)
                updatePageInfo(startPage, count)
            }
        }

        viewModel.currentPage.observe(this) { current ->
            val total = viewModel.pageCount.value ?: 0
            if (total > 0) updatePageInfo(current, total)
        }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.releaseHighRes()
                viewModel.goTo(position)
            }
        })
    }

    private fun updatePageInfo(current: Int, total: Int) {
        binding.pageInfo.text = getString(R.string.page_format, current + 1, total)
        updateChapterInfo()
    }

    private fun updateChapterInfo() {
        val name = chapterNames.getOrNull(currentChapterIndex) ?: ""
        binding.chapterInfo.text = name
        binding.prevChapterButton.isEnabled = currentChapterIndex > 0
        binding.nextChapterButton.isEnabled = currentChapterIndex < chapterUrls.size - 1
        binding.prevChapterButton.alpha = if (currentChapterIndex > 0) 1f else 0.4f
        binding.nextChapterButton.alpha = if (currentChapterIndex < chapterUrls.size - 1) 1f else 0.4f
    }

    /** Cambia al capitulo [index] de la lista (si existe y es valido). */
    private fun goToChapter(index: Int) {
        if (index < 0 || index >= chapterUrls.size) return
        // Ignorar taps mientras el lector esta cargando (evita carreras con taps rapidos).
        if (viewModel.isReady.value != true) return
        val url = chapterUrls[index]
        val name = chapterNames.getOrNull(index) ?: url

        // Actualizar el historial del capitulo ANTERIOR antes de cambiar el indice.
        updateHistoryProgress()

        currentChapterIndex = index

        // Que la recreacion de la Activity (rotacion) conserve el capitulo actual.
        intent.putExtra(EXTRA_CHAPTER_URL, url)
        intent.putExtra(EXTRA_CHAPTER_NAME, name)

        viewModel.switchChapter(
            intent.getLongExtra(EXTRA_SOURCE_ID, -1L),
            url,
            name,
            screenW(),
            screenH(),
            intent.getStringExtra(EXTRA_MANGA_TITLE),
        )
        updateChapterInfo()
    }

    private fun screenW(): Int {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        return dm.widthPixels
    }

    private fun screenH(): Int {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        return dm.heightPixels
    }

    override fun onTapLeft() {
        val rtl = Prefs.getReadingDirection() == Prefs.DIRECTION_RTL
        if (rtl) {
            goToPage(binding.pager.currentItem + 1)
        } else {
            goToPage(binding.pager.currentItem - 1)
        }
    }

    override fun onTapRight() {
        val rtl = Prefs.getReadingDirection() == Prefs.DIRECTION_RTL
        if (rtl) {
            goToPage(binding.pager.currentItem - 1)
        } else {
            goToPage(binding.pager.currentItem + 1)
        }
    }

    private fun goToPage(index: Int) {
        val max = (viewModel.pageCount.value ?: 1) - 1
        val target = index.coerceIn(0, max)
        if (target != binding.pager.currentItem) {
            binding.pager.setCurrentItem(target, true)
        }
    }

    private fun applyReadingDirection() {
        val rtl = Prefs.getReadingDirection() == Prefs.DIRECTION_RTL
        binding.pager.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        binding.directionButton.text = if (rtl) "RTL" else "LTR"
        binding.pager.requestLayout()
    }

    private fun showDirectionDialog() {
        val options = arrayOf(
            getString(R.string.reading_direction_ltr),
            getString(R.string.reading_direction_rtl)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reading_direction)
            .setSingleChoiceItems(options, Prefs.getReadingDirection()) { dialog, which ->
                Prefs.setReadingDirection(which)
                applyReadingDirection()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onTapCenter() {
        toggleUI()
    }

    private fun toggleUI() {
        val visible = binding.topBar.visibility == View.VISIBLE
        binding.topBar.visibility = if (visible) View.GONE else View.VISIBLE
        binding.bottomBar.visibility = if (visible) View.GONE else View.VISIBLE
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (::viewModel.isInitialized) {
                viewModel.clearCache()
                viewModel.releaseHighRes()
            }
        }
    }

    override fun onPause() {
        if (::viewModel.isInitialized) {
            viewModel.saveProgress()
            updateHistoryProgress()
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::viewModel.isInitialized) {
            viewModel.saveProgress()
            updateHistoryProgress()
        }
        super.onBackPressed()
    }

    /** Registra el progreso del ultimo capitulo leido en el historial online. */
    private fun updateHistoryProgress() {
        val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, -1L)
        val chapterUrl = chapterUrls.getOrNull(currentChapterIndex) ?: return
        val mangaUrl = intent.getStringExtra(EXTRA_MANGA_URL) ?: return
        if (sourceId <= 0 || chapterUrl.isBlank() || mangaUrl.isBlank()) return

        // No escribir progreso basura si el lector aun no termino de inicializar.
        if (viewModel.isReady.value != true) return
        val page = viewModel.currentPage.value ?: return
        val total = viewModel.pageCount.value ?: 0
        if (total <= 0) return

        (application as App).libraryRepository.updateHistoryProgress(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            chapterName = chapterNames.getOrNull(currentChapterIndex),
            pageIndex = page,
            totalPages = total,
        )
    }
}
