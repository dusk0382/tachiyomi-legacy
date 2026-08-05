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
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var viewModel: ReaderViewModel

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

        when {
            path != null -> viewModel.initLocal(path, screenWidth, screenHeight)
            sourceId > 0 && !chapterUrl.isNullOrBlank() -> {
                val name = intent.getStringExtra(EXTRA_CHAPTER_NAME) ?: chapterUrl
                viewModel.initOnline(sourceId, chapterUrl, name, screenWidth, screenHeight)
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
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::viewModel.isInitialized) {
            viewModel.saveProgress()
        }
        super.onBackPressed()
    }
}
