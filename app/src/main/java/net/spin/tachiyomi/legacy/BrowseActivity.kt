package net.spin.tachiyomi.legacy

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import net.spin.tachiyomi.legacy.databinding.ActivityBrowseBinding

/**
 * Browse activity: lists the sources available from installed extensions,
 * grouped by language (with headers). Tapping a source opens its catalog.
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

        BottomNavHelper.setup(this, binding.bottomNav.root, BottomNavHelper.TAB_DISCOVER)

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
                    it.lang.equals(query, ignoreCase = true) ||
                    languageName(it.lang).contains(query, ignoreCase = true)
            }
        }
        adapter.submit(groupByLanguage(filtered))
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Agrupa las fuentes por idioma, ordenado por nombre de idioma y luego por fuente. */
    private fun groupByLanguage(sources: List<Source>): List<Row> {
        val grouped = sources.groupBy { it.lang.ifBlank { "all" } }
        val languages = grouped.keys.sortedBy { languageName(it) }

        return buildList {
            languages.forEach { lang ->
                add(Row.Header(languageName(lang)))
                grouped.getValue(lang).sortedBy { it.name }.forEach { source ->
                    add(Row.Source(source))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as App
        app.extensionManager.reloadInstalled()
        SourceManager.registerExtensions(app.extensionManager.installedExtensions)
        net.spin.tachiyomi.legacy.kotatsu.KotatsuSourceManager.init(app.networkHelper)
        net.spin.tachiyomi.legacy.kotatsu.KotatsuSourceManager.registerAll()
        reloadSources()
    }

    sealed class Row {
        data class Header(val label: String) : Row()
        data class Source(val source: eu.kanade.tachiyomi.source.Source) : Row()
    }

    inner class SourceAdapter(
        private val onClick: (eu.kanade.tachiyomi.source.Source) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = mutableListOf<Row>()

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is Row.Header -> TYPE_HEADER
                is Row.Source -> TYPE_SOURCE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderVH(inflater.inflate(R.layout.item_source_header, parent, false))
            } else {
                SourceVH(inflater.inflate(R.layout.item_source, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).bind(row.label)
                is Row.Source -> (holder as SourceVH).bind(row.source, onClick)
            }
        }

        override fun getItemCount() = rows.size

        inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            private val label: TextView = view.findViewById(R.id.sourceHeader)
            fun bind(text: String) {
                label.text = text
            }
        }

        inner class SourceVH(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.sourceName)
            private val lang: TextView = view.findViewById(R.id.sourceLang)

            fun bind(source: eu.kanade.tachiyomi.source.Source, onClick: (eu.kanade.tachiyomi.source.Source) -> Unit) {
                name.text = source.name
                lang.text = source.lang
                itemView.setOnClickListener { onClick(source) }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SOURCE = 1

        fun languageName(lang: String): String {
            return when (lang.lowercase()) {
                "es" -> "Español"
                "en" -> "English"
                "pt" -> "Português"
                "pt-rbr" -> "Português (Brasil)"
                "fr" -> "Français"
                "de" -> "Deutsch"
                "it" -> "Italiano"
                "ja" -> "日本語"
                "zh" -> "中文"
                "zh-rhk", "zh-rtw" -> "中文"
                "ko" -> "한국어"
                "ru" -> "Русский"
                "ar" -> "العربية"
                "tr" -> "Türkçe"
                "vi" -> "Tiếng Việt"
                "th" -> "ไทย"
                "id" -> "Bahasa Indonesia"
                "hi" -> "हिन्दी"
                "pl" -> "Polski"
                "nl" -> "Nederlands"
                "uk" -> "Українська"
                "el" -> "Ελληνικά"
                "fil" -> "Filipino"
                "fa" -> "فارسی"
                "he" -> "עברית"
                "be" -> "Беларуская"
                "all" -> "Todos los idiomas"
                else -> lang
            }
        }
    }
}
