package net.spin.tachiyomi.legacy

import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import net.spin.tachiyomi.legacy.databinding.ActivityReaderBinding

class ReaderActivity : AppCompatActivity(), ZoomableImageView.OnTapListener {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LAST_PAGE = "last_page"
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

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }

        viewModel = ViewModelProvider(this)[ReaderViewModel::class.java]
        viewModel.init(path, screenWidth, screenHeight)

        val adapter = PageAdapter(viewModel, this)

        binding.pager.adapter = adapter
        binding.pager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        binding.pager.offscreenPageLimit = 1

        viewModel.pageCount.observe(this) { count ->
            adapter.notifyDataSetChanged()

            if (count == 0) {
                binding.pageInfo.text = "Sin páginas"
            } else {
                val lastPage = intent.getIntExtra(EXTRA_LAST_PAGE, 0)
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
        val cur = binding.pager.currentItem
        if (cur > 0) {
            binding.pager.setCurrentItem(cur - 1, true)
        }
    }

    override fun onTapRight() {
        val cur = binding.pager.currentItem
        val max = (viewModel.pageCount.value ?: 1) - 1
        if (cur < max) {
            binding.pager.setCurrentItem(cur + 1, true)
        }
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
            viewModel.currentPage.value?.let { page ->
                intent.getStringExtra(EXTRA_PATH)?.let { path ->
                    Prefs.setLastPage(path, page)
                }
            }
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::viewModel.isInitialized) {
            viewModel.currentPage.value?.let { page ->
                val path = intent.getStringExtra(EXTRA_PATH) ?: return@let
                Prefs.setLastPage(path, page)
            }
        }
        super.onBackPressed()
    }
}
