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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.spin.tachiyomi.legacy.databinding.ActivityCatalogBinding
import net.spin.tachiyomi.legacy.databinding.ItemMangaGridBinding
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
        // Rejilla estilo Kotatsu: 3 columnas (más mangas por pantalla, scroll fluido).
        val gridLayout = GridLayoutManager(this, GRID_COLUMNS)
        // El footer de carga ocupa el ancho completo (span de 3 columnas).
        gridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == TYPE_FOOTER) GRID_COLUMNS else 1
            }
        }
        binding.recycler.layoutManager = gridLayout
        binding.recycler.adapter = adapter
        // El RecyclerView nunca cambia de tamaño (solo cambia el nº de items),
        // asi que evita re-layouts innecesarios durante el scroll.
        binding.recycler.setHasFixedSize(true)
        binding.recycler.itemAnimator = null

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
                // Precarga anticipada: pedir la siguiente página cuando quedan
                // pocos items visibles (no esperar a tocar el fondo exacto).
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = lm.itemCount
                if (hasNext && !isLoading && lastVisible >= total - PREFETCH_DISTANCE) {
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
        binding.emptyText.visibility = View.GONE
        // Spinner central solo en la primera carga; footer para las siguientes.
        if (page == 1) {
            binding.progressBar.visibility = View.VISIBLE
        } else {
            binding.progressBar.visibility = View.GONE
            adapter.setFooterVisible(true)
        }

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
                // No bloquear el scroll: si una página falla, reintentar en el
                // siguiente scroll (se resetea hasNext para poder volver a pedir).
                hasNext = true
                Toast.makeText(this@CatalogActivity, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
            }

            isLoading = false
            adapter.setFooterVisible(false)
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
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<SManga>()
        private var footerVisible = false

        fun setItems(newItems: List<SManga>) {
            items.clear()
            items.addAll(newItems)
            footerVisible = false
            notifyDataSetChanged()
        }

        fun addItems(newItems: List<SManga>) {
            val start = items.size
            items.addAll(newItems)
            // El footer se desplaza solo: los items nuevos se insertan ANTES de él
            // (notifyItemRangeInserted mueve el footer hacia abajo correctamente).
            notifyItemRangeInserted(start, newItems.size)
        }

        fun clear() {
            items.clear()
            footerVisible = false
            notifyDataSetChanged()
        }

        fun setFooterVisible(visible: Boolean) {
            if (footerVisible == visible) return
            val hadFooter = footerVisible
            footerVisible = visible
            if (hadFooter && !visible) {
                notifyItemRemoved(items.size)
            } else {
                notifyItemInserted(items.size)
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (footerVisible && position == items.size) TYPE_FOOTER else TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            if (viewType == TYPE_FOOTER) {
                return FooterVH(inflater.inflate(R.layout.item_loading_footer, parent, false))
            }
            val b = ItemMangaGridBinding.inflate(inflater, parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is VH) {
                holder.bind(items[position])
            }
        }

        override fun getItemCount() = items.size + if (footerVisible) 1 else 0

        inner class VH(private val b: ItemMangaGridBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(manga: SManga) {
                b.title.text = manga.title
                // maxEdgePx=384: la portada se muestra a ~185px (columna de 600/3),
                // decodificar a 384px es 2x nítido y 4-16x más ligero que el original.
                ImageLoader.load(manga.thumbnail_url, b.cover, maxEdgePx = GRID_COVER_MAX_EDGE)
                b.root.setOnClickListener { onClick(manga) }
            }
        }

        inner class FooterVH(view: View) : RecyclerView.ViewHolder(view)
    }

    companion object {
        private const val GRID_COLUMNS = 3
        private const val PREFETCH_DISTANCE = 8
        private const val TYPE_ITEM = 0
        private const val TYPE_FOOTER = 1
        /** Tamaño de decodificación de portadas en la rejilla (ver ImageLoader). */
        private const val GRID_COVER_MAX_EDGE = 384
    }
}
