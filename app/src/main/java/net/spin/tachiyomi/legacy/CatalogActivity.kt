package net.spin.tachiyomi.legacy

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.spin.tachiyomi.legacy.databinding.ActivityCatalogBinding
import net.spin.tachiyomi.legacy.databinding.ItemMangaOnlineBinding
import net.spin.tachiyomi.legacy.data.online.OnlineRepository
import net.spin.tachiyomi.legacy.util.ImageLoader

/**
 * Catalog screen for a single source: shows popular manga initially,
 * supports pagination and searching within the source.
 */
class CatalogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCatalogBinding
    private lateinit var adapter: MangaAdapter

    private var sourceId: Long = 0
    private var sourceName: String = ""

    private var currentPage = 0
    private var isLoading = false
    private var hasNext = true
    private var query = ""
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCatalogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourceId = intent.getLongExtra("source_id", 0)
        sourceName = intent.getStringExtra("source_name") ?: ""
        binding.titleText.text = sourceName

        adapter = MangaAdapter { manga ->
            val intent = Intent(this, MangaDetailActivity::class.java).apply {
                putExtra("source_id", sourceId)
                putExtra("source_name", sourceName)
                putExtra("manga_url", manga.url)
                putExtra("manga_title", manga.title)
                manga.thumbnail_url?.let { putExtra("manga_thumb", it) }
            }
            startActivity(intent)
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnSearch.setOnClickListener {
            val show = binding.searchContainer.visibility == View.GONE
            binding.searchContainer.visibility = if (show) View.VISIBLE else View.GONE
            if (show) binding.searchBox.requestFocus()
        }

        binding.btnClearSearch.setOnClickListener {
            binding.searchBox.text.clear()
        }

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val newQuery = s?.toString()?.trim().orEmpty()
                if (newQuery != query) {
                    query = newQuery
                    reload()
                }
            }
        })

        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(1)) {
                    loadNextPage()
                }
            }
        })

        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        adapter.clear()
        currentPage = 0
        hasNext = true
        loadPage(1)
    }

    private fun loadNextPage() {
        if (hasNext && !isLoading) loadPage(currentPage + 1)
    }

    private fun loadPage(page: Int) {
        if (isLoading) return
        isLoading = true
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE

        loadJob = lifecycleScope.launch {
            val result = if (query.isEmpty()) {
                OnlineRepository.getPopular(sourceId, page)
            } else {
                OnlineRepository.getSearch(sourceId, query, page)
            }

            result.onSuccess { (mangas, hasNextPage) ->
                currentPage = page
                hasNext = hasNextPage
                if (page == 1) adapter.setItems(mangas) else adapter.addItems(mangas)
                binding.emptyText.text = getString(R.string.no_results, query)
                binding.emptyText.visibility =
                    if (adapter.itemCount == 0 && page == 1) View.VISIBLE else View.GONE
            }.onFailure {
                Toast.makeText(this@CatalogActivity, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
            }

            isLoading = false
            binding.progressBar.visibility = View.GONE
        }
    }

    inner class MangaAdapter(
        private val onClick: (SManga) -> Unit,
    ) : RecyclerView.Adapter<MangaAdapter.VH>() {

        private val items = mutableListOf<SManga>()

        fun setItems(newItems: List<SManga>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun addItems(newItems: List<SManga>) {
            val start = items.size
            items.addAll(newItems)
            notifyItemRangeInserted(start, newItems.size)
        }

        fun clear() {
            items.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemMangaOnlineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class VH(private val b: ItemMangaOnlineBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(manga: SManga) {
                b.title.text = manga.title
                b.subtitle.text = manga.author
                    ?.takeIf { it.isNotBlank() }
                    ?: manga.description?.takeIf { it.isNotBlank() }?.take(60)
                    ?: ""
                ImageLoader.load(manga.thumbnail_url, b.cover)
                b.root.setOnClickListener { onClick(manga) }
            }
        }
    }
}