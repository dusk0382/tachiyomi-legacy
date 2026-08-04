package net.spin.tachiyomi.legacy

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.source.SourceManager
import net.spin.tachiyomi.legacy.databinding.ActivityBrowseBinding
import net.spin.tachiyomi.legacy.databinding.ItemSourceBinding

/**
 * Browse activity: lists the sources available from installed extensions.
 * Tapping a source opens its catalog. Search box performs global search
 * across all installed sources.
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowseBinding
    private lateinit var adapter: SourceAdapter

    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SourceAdapter { source ->
            val intent = Intent(this, CatalogActivity::class.java).apply {
                putExtra("source_id", source.id)
                putExtra("source_name", source.name)
            }
            startActivity(intent)
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnRefresh.setOnClickListener {
            reloadSources()
            Toast.makeText(this, R.string.loading_sources, Toast.LENGTH_SHORT).show()
        }

        binding.btnExtensions.setOnClickListener {
            startActivity(Intent(this, ExtensionsActivity::class.java))
        }

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })

        reloadSources()
    }

    private fun reloadSources() {
        val sources = SourceManager.getAll()
        adapter.submit(sources)
        binding.emptyText.text = getString(R.string.no_sources)
        binding.emptyText.visibility = if (sources.isEmpty()) View.VISIBLE else View.GONE
        applyFilter()
    }

    private fun applyFilter() {
        val all = SourceManager.getAll()
        val filtered = if (query.isEmpty()) {
            all
        } else {
            all.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.lang.equals(query, ignoreCase = true)
            }
        }
        adapter.submit(filtered)
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        val app = application as App
        app.extensionManager.reloadInstalled()
        SourceManager.registerExtensions(app.extensionManager.installedExtensions)
        reloadSources()
    }

    inner class SourceAdapter(
        private val onClick: (eu.kanade.tachiyomi.source.Source) -> Unit,
    ) : RecyclerView.Adapter<SourceAdapter.VH>() {

        private val items = mutableListOf<eu.kanade.tachiyomi.source.Source>()

        fun submit(newItems: List<eu.kanade.tachiyomi.source.Source>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class VH(private val b: ItemSourceBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(source: eu.kanade.tachiyomi.source.Source) {
                b.sourceName.text = source.name
                b.sourceLang.text = source.lang
                b.root.setOnClickListener { onClick(source) }
            }
        }
    }
}