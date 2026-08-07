package net.spin.tachiyomi.legacy

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
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
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Catalog screen for a single source, navegable como el catálogo de Kotatsu:
 * orden (Populares / Recientes / Nuevos / Alfabético...) y filtro por etiquetas.
 * Soporta paginación y búsqueda dentro de la fuente (solo con Enter).
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

    /** Orden de catálogo seleccionado (Populares por defecto). */
    private var sortOrders: List<SortOrder> = emptyList()
    private var currentOrder: SortOrder = SortOrder.POPULARITY

    /** Etiquetas disponibles y seleccionadas para el filtro. */
    private var availableTags: List<MangaTag> = emptyList()
    private var selectedTags: Set<MangaTag> = emptySet()

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
            query = ""
            reload()
        }

        // La busqueda solo se dispara con Enter (o al limpiar), para no saturar
        // la fuente con una peticion por letra escrita.
        binding.searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                query = binding.searchBox.text.toString().trim()
                reload()
                true
            } else {
                false
            }
        }

        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(1)) {
                    loadNextPage()
                }
            }
        })

        binding.btnFilters.setOnClickListener { showTagFilter() }

        loadCatalogOptions()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchBox.windowToken, 0)
    }

    /** Carga las ordenaciones y etiquetas de la fuente, configura la UI y arranca. */
    private fun loadCatalogOptions() {
        lifecycleScope.launch {
            val orders = runCatching { OnlineRepository.getSortOrders(sourceId) }
                .getOrDefault(emptyList())

            if (orders.isEmpty()) {
                // Fuente sin soporte Kotatsu (p. ej. extensión clásica): ocultar la
                // barra de catálogo y cargar populares directamente.
                binding.catalogBar.visibility = View.GONE
                reload()
                return@launch
            }

            sortOrders = orders
            currentOrder = orders.firstOrNull { it == SortOrder.POPULARITY }
                ?: orders.first()
            setupSortSpinner()

            availableTags = runCatching { OnlineRepository.getCatalogTags(sourceId) }
                .getOrDefault(emptyList())
            binding.btnFilters.visibility = if (availableTags.isEmpty()) View.GONE else View.VISIBLE

            reload()
        }
    }

    /** Rellena el Spinner de orden con nombres legibles y recarga al cambiar. */
    private fun setupSortSpinner() {
        val labels = sortOrders.map { sortOrderLabel(it) }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.sortSpinner.adapter = spinnerAdapter
        binding.sortSpinner.setSelection(sortOrders.indexOf(currentOrder).coerceAtLeast(0))
        binding.sortSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val newOrder = sortOrders.getOrNull(position) ?: return
                if (newOrder != currentOrder) {
                    currentOrder = newOrder
                    query = ""
                    binding.searchBox.text.clear()
                    reload()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    /** Diálogo de etiquetas (multi-selección), como el filtro del catálogo Kotatsu. */
    private fun showTagFilter() {
        if (availableTags.isEmpty()) {
            Toast.makeText(this, "Esta fuente no tiene etiquetas", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = availableTags.map { it.title }.toTypedArray()
        val checked = BooleanArray(availableTags.size) { i ->
            selectedTags.contains(availableTags[i])
        }
        AlertDialog.Builder(this)
            .setTitle("Filtrar por etiquetas")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Aplicar") { _, _ ->
                selectedTags = availableTags.filterIndexed { i, _ -> checked[i] }.toSet()
                reload()
            }
            .setNeutralButton("Limpiar") { _, _ ->
                selectedTags = emptySet()
                reload()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
            val result = OnlineRepository.getCatalog(
                sourceId = sourceId,
                page = page,
                order = currentOrder,
                query = query,
                tags = selectedTags,
            )

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

    /** Nombre legible del orden de catálogo (como Kotatsu). */
    private fun sortOrderLabel(order: SortOrder): String {
        return when (order) {
            SortOrder.POPULARITY -> "Populares"
            SortOrder.POPULARITY_HOUR -> "Populares (hora)"
            SortOrder.POPULARITY_TODAY -> "Populares (hoy)"
            SortOrder.POPULARITY_WEEK -> "Populares (semana)"
            SortOrder.POPULARITY_MONTH -> "Populares (mes)"
            SortOrder.POPULARITY_YEAR -> "Populares (año)"
            SortOrder.UPDATED -> "Recientes"
            SortOrder.NEWEST -> "Nuevos"
            SortOrder.ALPHABETICAL -> "Alfabético"
            SortOrder.ALPHABETICAL_DESC -> "Alfabético (Z-A)"
            SortOrder.RATING -> "Valoración"
            SortOrder.ADDED -> "Añadidos"
            SortOrder.RELEVANCE -> "Relevancia"
            else -> order.name
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
