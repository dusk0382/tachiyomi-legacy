package net.spin.tachiyomi.legacy

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.source.SourceManager
import net.spin.tachiyomi.legacy.data.model.ChapterRef
import net.spin.tachiyomi.legacy.databinding.ActivityLibraryBinding
import net.spin.tachiyomi.legacy.kotatsu.KotatsuSourceManager
import net.spin.tachiyomi.legacy.util.ImageLoader
import net.spin.tachiyomi.legacy.util.TimeUtil
import java.io.File
import java.util.concurrent.Executors

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var adapter: MangaAdapter

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "manga-scanner").apply { isDaemon = true }
    }

    private var allMangas: List<MangaFile> = emptyList()
    private var privateMangas: List<MangaFile> = emptyList()
    private var currentQuery: String = ""
    private var currentSort: Int = 0
    private var isSearchVisible = false
    private var isPrivateMode = false
    private var isMoveToPrivateEnabled = false

    private var currentTab = TAB_LOCAL

    private var pendingPrivateKey: String? = null
    private var pendingOriginalFile: File? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadLibrary() else showPermissionMessage()
    }

    private val writePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableMoveToPrivate()
        } else {
            Toast.makeText(
                this,
                "Sin permiso de escritura. El archivo original puede quedar sin borrar.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            PrivateLibraryManager.saveGrantedUri(uri)

            val key = pendingPrivateKey
            val file = pendingOriginalFile

            pendingPrivateKey = null
            pendingOriginalFile = null

            if (key != null && file != null) {
                executor.execute {
                    val ok = PrivateLibraryManager.retryDeleteOriginal(key, file)

                    runOnUiThread {
                        if (!isDestroyed) {
                            if (ok) {
                                Toast.makeText(
                                    this,
                                    "Original borrado correctamente",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "No se pudo borrar el original",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(
                    this,
                    "Permiso concedido.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Prefs.init(applicationContext)
        ThumbnailManager.init(applicationContext)
        LibraryCache.init(applicationContext)
        PrivateLibraryManager.init(applicationContext)

        adapter = MangaAdapter(
            onClick = ::onItemClick,
            onLongClick = ::onItemLongClick
        )

        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.itemAnimator = null
        binding.recycler.adapter = adapter

        setupSearchToggle()
        setupSort()
        setupRefreshButton()
        setupTabs()

        BottomNavHelper.setup(this, binding.bottomNav.root, BottomNavHelper.TAB_LIBRARY)

        currentSort = Prefs.getSortMode()
        binding.sortSpinner.setSelection(currentSort)

        checkPermissionAndLoad()
    }

    private fun setupTabs() {
        binding.tabLocal.setOnClickListener { switchTab(TAB_LOCAL) }
        binding.tabFavorites.setOnClickListener { switchTab(TAB_FAVORITES) }
        binding.tabHistory.setOnClickListener { switchTab(TAB_HISTORY) }
        binding.tabRecent.setOnClickListener { switchTab(TAB_RECENT) }
        updateTabStyles()
    }

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab
        isPrivateMode = false
        isMoveToPrivateEnabled = false
        updateTabStyles()
        loadTab()
    }

    private fun updateTabStyles() {
        binding.tabLocal.setTextColor(
            ContextCompat.getColor(this, if (currentTab == TAB_LOCAL) R.color.text_primary else R.color.text_secondary)
        )
        binding.tabLocal.setTypeface(null, if (currentTab == TAB_LOCAL) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.tabFavorites.setTextColor(
            ContextCompat.getColor(this, if (currentTab == TAB_FAVORITES) R.color.text_primary else R.color.text_secondary)
        )
        binding.tabFavorites.setTypeface(null, if (currentTab == TAB_FAVORITES) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.tabHistory.setTextColor(
            ContextCompat.getColor(this, if (currentTab == TAB_HISTORY) R.color.text_primary else R.color.text_secondary)
        )
        binding.tabHistory.setTypeface(null, if (currentTab == TAB_HISTORY) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.tabRecent.setTextColor(
            ContextCompat.getColor(this, if (currentTab == TAB_RECENT) R.color.text_primary else R.color.text_secondary)
        )
        binding.tabRecent.setTypeface(null, if (currentTab == TAB_RECENT) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun loadTab() {
        when (currentTab) {
            TAB_LOCAL -> loadLibrary()
            TAB_FAVORITES -> {
                adapter.clear()
                val favorites = (application as App).libraryRepository.getFavorites()
                allMangas = emptyList()
                renderOnlineItems(favorites.map { it.toOnlineItem() })
            }
            TAB_HISTORY -> {
                adapter.clear()
                val history = (application as App).libraryRepository.getHistory()
                allMangas = emptyList()
                renderOnlineItems(history.map { it.toOnlineItem() })
            }
            TAB_RECENT -> renderRecent()
        }
    }

    /**
     * Recientes (como Kotatsu): favoritos + historial unidos y ordenados por el
     * último capítulo conocido en la BD (fecha de subida), con badge "Nuevo" si
     * hay capítulos sin leer desde la última lectura.
     */
    private fun renderRecent() {
        adapter.clear()
        allMangas = emptyList()
        binding.progressBar.visibility = View.VISIBLE

        val repo = (application as App).libraryRepository
        executor.execute {
            val favorites = repo.getFavorites()
            val history = repo.getHistory()

            // Unir por (sourceId, url), priorizando el historial (tiene progreso).
            val byKey = LinkedHashMap<String, LibraryItem.Online>()
            favorites.forEach { f ->
                byKey[f.key] = LibraryItem.Online(
                    sourceId = f.sourceId,
                    url = f.url,
                    title = f.title,
                    thumbnailUrl = f.thumbnailUrl,
                    subtitle = "Favorito",
                )
            }
            history.forEach { h ->
                val prev = byKey[h.key]
                byKey[h.key] = LibraryItem.Online(
                    sourceId = h.sourceId,
                    url = h.url,
                    title = h.title,
                    thumbnailUrl = h.thumbnailUrl,
                    subtitle = prev?.subtitle ?: "Historial",
                    readPercent = if (h.lastTotalPages > 0) {
                        (h.lastPageIndex.toFloat() / h.lastTotalPages).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    isHistory = true,
                    lastChapterUrl = h.lastChapterUrl,
                    lastChapterName = h.lastChapterName,
                    lastPageIndex = h.lastPageIndex,
                )
            }

            // Para cada manga, el último capítulo conocido (BD) y si hay novedades.
            // Se calcula UNA sola vez por manga (no en cada paso).
            data class Entry(val item: LibraryItem.Online, val latestDate: Long)

            val entries = byKey.values.map { item ->
                val latest = repo.getChapters(item.sourceId, item.url)
                    .maxByOrNull { it.uploadDate }
                val isNew = latest != null &&
                    latest.url != item.lastChapterUrl &&
                    latest.uploadDate > 0L

                val whenStr = if (latest != null && latest.uploadDate > 0L) {
                    formatRelativeDate(latest.uploadDate)
                } else {
                    null
                }

                val label = buildString {
                    if (isNew) append("● Nuevo · ")
                    item.lastChapterName?.takeIf { it.isNotBlank() }?.let {
                        append("Último leído: $it")
                    }
                    if (whenStr != null) {
                        if (isNotEmpty()) append(" · ")
                        append("Cap. reciente: $whenStr")
                    }
                }

                Entry(
                    item = item.copy(
                        subtitle = label.ifBlank { item.subtitle },
                        isHistory = true,
                    ),
                    latestDate = latest?.uploadDate ?: 0L,
                )
            }.sortedByDescending { it.latestDate }.map { it.item }

            runOnUiThread {
                if (!isDestroyed) {
                    binding.progressBar.visibility = View.GONE
                    renderOnlineItems(entries)
                    binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    if (entries.isEmpty()) {
                        binding.emptyText.text =
                            "Aún no hay nada reciente.\nLos mangas que leas o marques como favorito aparecerán aquí."
                    }
                }
            }
        }
    }

    private fun renderOnlineItems(items: List<LibraryItem.Online>) {
        binding.progressBar.visibility = View.GONE
        val filtered = if (currentQuery.isBlank()) {
            items
        } else {
            val q = currentQuery.lowercase()
            items.filter { it.title.lowercase().contains(q) }
        }
        adapter.submit(filtered)
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = if (items.isEmpty()) {
            if (currentTab == TAB_FAVORITES) "Aún no hay favoritos.\nAbre un manga y toca la estrella." else "Sin historial todavía."
        } else {
            getString(R.string.no_results, currentQuery)
        }
    }

    private fun setupRefreshButton() {
        binding.btnRefresh.setOnClickListener {
            refreshLibrary()
        }
    }

    private fun refreshLibrary() {
        if (currentTab == TAB_FAVORITES || currentTab == TAB_HISTORY || currentTab == TAB_RECENT) {
            loadTab()
            val msg = when (currentTab) {
                TAB_FAVORITES -> "Favoritos actualizados"
                TAB_HISTORY -> "Historial actualizado"
                else -> "Recientes actualizados"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        if (isPrivateMode) {
            privateMangas = PrivateLibraryManager.getPrivateMangas()
            applyFilters()
            Toast.makeText(this, "Biblioteca privada actualizada", Toast.LENGTH_SHORT).show()
        } else {
            binding.progressBar.visibility = View.VISIBLE
            binding.emptyText.visibility = View.GONE

            LibraryCache.clear()

            executor.execute {
                val list = MangaScanner.scan(applicationContext)

                allMangas = list
                LibraryCache.saveLibrary(list)

                runOnUiThread {
                    if (!isDestroyed) {
                        binding.progressBar.visibility = View.GONE

                        if (list.isEmpty()) {
                            binding.emptyText.visibility = View.VISIBLE
                            binding.emptyText.text = getString(R.string.empty_library)
                        } else {
                            applyFilters()
                            Toast.makeText(
                                this,
                                "Biblioteca actualizada (${list.size} mangas)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                countTotalPages(list)
            }
        }
    }

    private fun setupSearchToggle() {
        binding.btnSearchToggle.setOnClickListener {
            isSearchVisible = !isSearchVisible
            binding.searchContainer.visibility = if (isSearchVisible) View.VISIBLE else View.GONE

            if (isSearchVisible) {
                binding.searchBox.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.searchBox, InputMethodManager.SHOW_IMPLICIT)
            } else {
                currentQuery = ""
                binding.searchBox.text.clear()

                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.searchBox.windowToken, 0)

                applyFilters()
            }
        }

        binding.btnBrowseOnline.setOnClickListener {
            startActivity(Intent(this, BrowseActivity::class.java))
        }

        binding.btnClearSearch.setOnClickListener {
            currentQuery = ""
            binding.searchBox.text.clear()
            isSearchVisible = false
            binding.searchContainer.visibility = View.GONE

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.searchBox.windowToken, 0)

            applyFilters()
        }

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString() ?: ""

                if (currentQuery == "NSFWActivate") {
                    binding.searchBox.text.clear()
                    currentQuery = ""
                    isSearchVisible = false
                    binding.searchContainer.visibility = View.GONE

                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(binding.searchBox.windowToken, 0)

                    toggleNsfw()
                    return
                }

                if (currentQuery == "SecureFolderActivate") {
                    binding.searchBox.text.clear()
                    currentQuery = ""
                    isSearchVisible = false
                    binding.searchContainer.visibility = View.GONE

                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(binding.searchBox.windowToken, 0)

                    handlePrivateFolderAccess()
                    return
                }

                if (currentQuery == "SecureAddActivate") {
                    binding.searchBox.text.clear()
                    currentQuery = ""
                    isSearchVisible = false
                    binding.searchContainer.visibility = View.GONE

                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(binding.searchBox.windowToken, 0)

                    requestEnableMoveToPrivate()
                    return
                }

                applyFilters()
            }
        })
    }

    /** Activa/desactiva las fuentes NSFW (solo en memoria, se pierde al cerrar). */
    private fun toggleNsfw() {
        val app = application as App
        val enabled = !KotatsuSourceManager.nsfwEnabled

        try {
            KotatsuSourceManager.applyNsfw(
                app.networkHelper,
                app.extensionManager.installedExtensions,
                enabled,
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(
            this,
            if (enabled) {
                "Fuentes NSFW activadas (solo esta sesión)"
            } else {
                "Fuentes NSFW desactivadas"
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun handlePrivateFolderAccess() {
        if (!PrivateLibraryManager.isPinSetup()) {
            PinDialog(this)
                .setSetupMode(true)
                .setListener(object : PinDialog.OnPinEnteredListener {
                    override fun onPinCorrect(pin: String) {}
                    override fun onPinIncorrect() {}
                    override fun onSetupComplete(pin: String) {
                        PrivateLibraryManager.setupPin(pin)
                        enterPrivateMode()
                    }
                })
                .show()
        } else {
            PinDialog(this)
                .setSetupMode(false)
                .setListener(object : PinDialog.OnPinEnteredListener {
                    override fun onPinCorrect(pin: String) {
                        enterPrivateMode()
                    }

                    override fun onPinIncorrect() {}
                    override fun onSetupComplete(pin: String) {}
                })
                .show()
        }
    }

    private fun requestEnableMoveToPrivate() {
        if (!PrivateLibraryManager.isPinSetup()) {
            Toast.makeText(this, "Primero configura la carpeta privada", Toast.LENGTH_SHORT).show()
            return
        }

        PinDialog(this)
            .setSetupMode(false)
            .setListener(object : PinDialog.OnPinEnteredListener {
                override fun onPinCorrect(pin: String) {
                    enableMoveToPrivate()
                }

                override fun onPinIncorrect() {}
                override fun onSetupComplete(pin: String) {}
            })
            .show()
    }

    private fun enableMoveToPrivate() {
        if (!PrivateLibraryManager.isPinSetup()) {
            Toast.makeText(this, "Primero configura la carpeta privada", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        isMoveToPrivateEnabled = true

        val storageInfo = if (PrivateLibraryManager.isStorageOnSdCard()) {
            "Almacenamiento: Tarjeta SD"
        } else {
            "Almacenamiento: Interno"
        }

        Toast.makeText(
            this,
            "Modo activado\n$storageInfo",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun enterPrivateMode() {
        isPrivateMode = true
        privateMangas = PrivateLibraryManager.getPrivateMangas()
        applyFilters()
    }

    private fun exitPrivateMode() {
        isPrivateMode = false
        privateMangas = emptyList()
        applyFilters()
    }

    private fun setupSort() {
        val sortOptions = resources.getStringArray(R.array.sort_options)

        val arrayAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sortOptions
        )

        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.sortSpinner.adapter = arrayAdapter

        binding.sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentSort = position
                Prefs.setSortMode(position)
                applyFilters()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyFilters() {
        if (currentTab != TAB_LOCAL) {
            loadTab()
            return
        }

        val sourceList: List<LibraryItem> = if (isPrivateMode) {
            // Modo privado: CBZ privados + mangos online privados.
            val cbz = privateMangas.map { LibraryItem.Local(it) }
            val online = (application as App).libraryRepository.getPrivateOnline().map { ref ->
                LibraryItem.Online(
                    sourceId = ref.sourceId,
                    url = ref.url,
                    title = ref.title,
                    thumbnailUrl = ref.thumbnailUrl,
                    subtitle = "🔒 Carpeta privada",
                    isPrivate = true,
                )
            }
            cbz + online
        } else {
            allMangas.map { LibraryItem.Local(it) }
        }

        var list = sourceList

        if (currentQuery.isNotBlank()) {
            val q = currentQuery.lowercase()
            list = list.filter { it.title.lowercase().contains(q) }
        }

        val allLocal = list.all { it is LibraryItem.Local }
        list = if (allLocal) {
            when (currentSort) {
                0 -> list.sortedBy { it.title.lowercase() }
                1 -> list.sortedByDescending { (it as LibraryItem.Local).manga.file.lastModified() }
                2 -> list.sortedByDescending { (it as LibraryItem.Local).manga.file.length() }
                3 -> list.sortedByDescending { manga ->
                    val f = (manga as LibraryItem.Local).manga.file
                    val last = Prefs.getLastPage(f.absolutePath)
                    val total = Prefs.getTotalPages(f.absolutePath)
                    if (total > 0) last.toFloat() / total else 0f
                }
                else -> list
            }
        } else {
            list.sortedBy { it.title.lowercase() }
        }

        adapter.submit(list)

        if (list.isEmpty() && sourceList.isNotEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.text = getString(R.string.no_results, currentQuery)
        } else if (list.isEmpty() && sourceList.isEmpty() && isPrivateMode) {
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.text = "La carpeta privada está vacía"
        } else {
            binding.emptyText.visibility = View.GONE
        }
    }

    private fun onItemClick(item: LibraryItem) {
        when (item) {
            is LibraryItem.Local -> openReader(item.manga)
            is LibraryItem.Online -> openOnlineManga(item)
        }
    }

    private fun onItemLongClick(item: LibraryItem): Boolean {
        when (item) {
            is LibraryItem.Local -> onMangaLongClick(item.manga)
            is LibraryItem.Online -> onOnlineLongClick(item)
        }
        return true
    }

    private fun onOnlineLongClick(item: LibraryItem.Online) {
        when {
            item.isPrivate -> {
                AlertDialog.Builder(this)
                    .setTitle("¿Eliminar de la carpeta privada?")
                    .setMessage("'${item.title}' se quitará de la carpeta privada.")
                    .setPositiveButton("Eliminar") { _, _ ->
                        (application as App).libraryRepository
                            .removePrivateOnline(item.sourceId, item.url)
                        enterPrivateMode()
                        Toast.makeText(this, "Eliminado de la carpeta privada", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            currentTab == TAB_HISTORY -> {
                // Del historial: mover a carpeta privada o eliminar del historial.
                AlertDialog.Builder(this)
                    .setTitle(item.title)
                    .setItems(arrayOf("🔒 Mover a carpeta privada", "Eliminar del historial")) { _, which ->
                        when (which) {
                            0 -> moveOnlineToPrivate(item)
                            1 -> {
                                (application as App).libraryRepository
                                    .removeHistory(item.sourceId, item.url)
                                loadTab()
                                Toast.makeText(this, "Eliminado del historial", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }

            else -> {
                AlertDialog.Builder(this)
                    .setTitle(item.title)
                    .setItems(arrayOf("🔒 Mover a carpeta privada", "Quitar de favoritos")) { _, which ->
                        when (which) {
                            0 -> moveOnlineToPrivate(item)
                            1 -> {
                                (application as App).libraryRepository
                                    .removeFavorite(item.sourceId, item.url)
                                loadTab()
                                Toast.makeText(this, "Quitado de favoritos", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
        }
    }

    /** Mueve un manga online (historial/favoritos) a la carpeta privada. */
    private fun moveOnlineToPrivate(item: LibraryItem.Online) {
        (application as App).libraryRepository.addPrivateOnline(
            net.spin.tachiyomi.legacy.data.model.PrivateRef(
                sourceId = item.sourceId,
                url = item.url,
                title = item.title,
                thumbnailUrl = item.thumbnailUrl,
            ),
        )
        (application as App).libraryRepository.removeHistory(item.sourceId, item.url)
        (application as App).libraryRepository.removeFavorite(item.sourceId, item.url)
        loadTab()
        Toast.makeText(this, "Movido a carpeta privada", Toast.LENGTH_SHORT).show()
    }

    private fun openOnlineManga(item: LibraryItem.Online) {
        // Del historial: abrir DIRECTAMENTE el lector en el capitulo guardado
        // (como Kotatsu), usando la lista de capitulos ya persistida en la BD.
        // Solo si la lista local existe; si no, se abre el detalle como fallback.
        if (item.isHistory && !item.lastChapterUrl.isNullOrBlank()) {
            val saved = (application as App).libraryRepository
                .getChapters(item.sourceId, item.url)
            val target = saved.firstOrNull { it.url == item.lastChapterUrl }
            if (target != null) {
                openReaderDirect(item, saved, target)
                return
            }
        }

        val intent = Intent(this, MangaDetailActivity::class.java).apply {
            putExtra("source_id", item.sourceId)
            putExtra("manga_url", item.url)
            putExtra("manga_title", item.title)
            item.thumbnailUrl?.let { putExtra("manga_thumb", it) }
            // Del historial: abrir directo el capitulo donde se dejo (y su pagina).
            if (item.isHistory && !item.lastChapterUrl.isNullOrBlank()) {
                putExtra("open_chapter_url", item.lastChapterUrl)
                if (item.lastPageIndex > 0) putExtra("open_chapter_page", item.lastPageIndex)
            }
        }
        startActivity(intent)
    }

    /** Abre el lector directamente en el capitulo guardado del historial. */
    private fun openReaderDirect(
        item: LibraryItem.Online,
        chapters: List<ChapterRef>,
        target: ChapterRef,
    ) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_SOURCE_ID, item.sourceId)
            putExtra(ReaderActivity.EXTRA_CHAPTER_URL, target.url)
            putExtra(ReaderActivity.EXTRA_CHAPTER_NAME, target.name)
            putExtra(ReaderActivity.EXTRA_MANGA_URL, item.url)
            putExtra(ReaderActivity.EXTRA_MANGA_TITLE, item.title)
            if (item.lastPageIndex > 0) putExtra(ReaderActivity.EXTRA_LAST_PAGE, item.lastPageIndex)
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

    private fun onMangaLongClick(manga: MangaFile) {
        if (isPrivateMode) {
            AlertDialog.Builder(this)
                .setTitle(manga.title)
                .setItems(arrayOf("Sacar de la carpeta privada")) { _, which ->
                    if (which == 0) {
                        removeFromPrivate(manga)
                    }
                }
                .show()
        } else {
            if (!isMoveToPrivateEnabled) return

            if (PrivateLibraryManager.isAlreadyPrivate(manga.file)) {
                if (manga.file.exists()) {
                    AlertDialog.Builder(this)
                        .setTitle("Archivo ya privado")
                        .setMessage(
                            "Este archivo ya está en la carpeta privada.\n" +
                                    "¿Quieres borrar el original público?"
                        )
                        .setPositiveButton("Borrar") { _, _ ->
                            deletePublicOriginal(manga)
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    allMangas = allMangas.filter { it.file.absolutePath != manga.file.absolutePath }
                    LibraryCache.saveLibrary(allMangas)
                    applyFilters()
                }
                return
            }

            AlertDialog.Builder(this)
                .setTitle("¿Mover a carpeta privada?")
                .setMessage("'${manga.title}' desaparecerá de la biblioteca.")
                .setPositiveButton("Mover") { _, _ ->
                    performMoveToPrivate(manga)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun performMoveToPrivate(manga: MangaFile) {
        val result = PrivateLibraryManager.addPrivateManga(manga.file)

        if (result.success) {
            allMangas = allMangas.filter { it.file.absolutePath != manga.file.absolutePath }

            LibraryCache.saveLibrary(allMangas)
            ThumbnailManager.clearThumb(manga.file)

            applyFilters()

            if (result.originalFileDeleted) {
                Toast.makeText(this, "Movido correctamente", Toast.LENGTH_SHORT).show()
            } else if (result.needsSafPermission) {
                pendingPrivateKey = result.key
                pendingOriginalFile = manga.file
                showSafPermissionDialog(manga.file)
            } else {
                Toast.makeText(this, "Movido (archivo original permanece)", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Error al mover el archivo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePublicOriginal(manga: MangaFile) {
        val file = manga.file

        binding.progressBar.visibility = View.VISIBLE

        executor.execute {
            val ok = PrivateLibraryManager.deleteOriginal(file)

            runOnUiThread {
                if (!isDestroyed) {
                    binding.progressBar.visibility = View.GONE

                    if (ok) {
                        allMangas = allMangas.filter { it.file.absolutePath != file.absolutePath }
                        LibraryCache.saveLibrary(allMangas)
                        ThumbnailManager.clearThumb(file)
                        applyFilters()

                        Toast.makeText(this, "Original borrado", Toast.LENGTH_SHORT).show()
                    } else {
                        val key = PrivateLibraryManager.getKeyForFile(file)

                        if (key != null) {
                            pendingPrivateKey = key
                            pendingOriginalFile = file
                            showSafPermissionDialog(file)
                        } else {
                            Toast.makeText(
                                this,
                                "No se pudo borrar el original",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun showSafPermissionDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Permiso necesario")
            .setMessage(
                "El archivo está en la tarjeta SD y Android requiere permiso especial para borrarlo.\n" +
                        "En la siguiente pantalla, selecciona la carpeta que contiene el archivo:\n" +
                        "${file.parentFile?.absolutePath}\n" +
                        "Este permiso solo se pide una vez por carpeta."
            )
            .setPositiveButton("Conceder permiso") { _, _ ->
                safLauncher.launch(null)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun removeFromPrivate(manga: MangaFile) {
        val key = manga.privateKey

        if (key == null) {
            Toast.makeText(this, "Error: clave privada no encontrada", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Confirmar")
            .setMessage("¿Sacar '${manga.title}' de la carpeta privada?\nSe restaurará a su ubicación original.")
            .setPositiveButton("Sacar") { _, _ ->
                binding.progressBar.visibility = View.VISIBLE

                executor.execute {
                    val result = PrivateLibraryManager.removePrivateManga(key)

                    runOnUiThread {
                        if (!isDestroyed) {
                            binding.progressBar.visibility = View.GONE

                            privateMangas = PrivateLibraryManager.getPrivateMangas()
                            applyFilters()

                            val msg = when {
                                !result.success ->
                                    result.message ?: "Error al restaurar"

                                result.restoredFile != null ->
                                    "Restaurado a: ${result.restoredFile.absolutePath}"

                                result.message != null ->
                                    result.message

                                else ->
                                    "Restaurado"
                            }

                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkPermissionAndLoad() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) loadLibrary() else requestPermission()
    }

    private fun requestPermission() {
        binding.emptyText.text = getString(R.string.permission_needed)
        permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun showPermissionMessage() {
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = getString(R.string.permission_needed)
    }

    private fun loadLibrary() {
        if (currentTab != TAB_LOCAL) {
            loadTab()
            return
        }
        if (isPrivateMode) return

        val cached = LibraryCache.loadLibrary()
        val needsRescan = LibraryCache.needsRescan()

        if (cached != null && cached.isNotEmpty()) {
            allMangas = cached
            applyFilters()

            if (!needsRescan) {
                executor.execute {
                    countTotalPages(cached)
                }
            }
        }

        if (allMangas.isEmpty() || needsRescan) {
            binding.progressBar.visibility = View.VISIBLE
            binding.emptyText.visibility = View.GONE

            executor.execute {
                val list = MangaScanner.scan(applicationContext)

                allMangas = list
                LibraryCache.saveLibrary(list)

                runOnUiThread {
                    if (!isDestroyed) {
                        binding.progressBar.visibility = View.GONE

                        if (list.isEmpty()) {
                            binding.emptyText.visibility = View.VISIBLE
                            binding.emptyText.text = getString(R.string.empty_library)
                        } else {
                            applyFilters()
                        }
                    }
                }

                countTotalPages(list)
            }
        }
    }

    private fun countTotalPages(list: List<MangaFile>) {
        val app = applicationContext
        val prevPriority = Thread.currentThread().priority
        Thread.currentThread().priority = Thread.MIN_PRIORITY

        try {
            for (manga in list) {
                if (Thread.currentThread().isInterrupted) return

                val path = manga.file.absolutePath

                // Solo contar lo que aún no se ha intentado (evita reintentar corruptos en cada launch).
                if (Prefs.getTotalPages(path) != 0 || Prefs.isPageCounted(path)) {
                    continue
                }

                try {
                    CBZReader(manga.file, app).use { reader ->
                        Prefs.setTotalPages(path, reader.countPagesFast())
                    }
                } catch (_: Exception) {
                    // Corrupto o ilegible: marcarlo para no re-scanear a cada lanzamiento.
                }
                Prefs.setPageCounted(path, true)
            }
        } finally {
            Thread.currentThread().priority = prevPriority
        }

        runOnUiThread {
            if (!isDestroyed) {
                applyFilters()
            }
        }
    }

    private fun openReader(manga: MangaFile) {
        val lastPage = Prefs.getLastPage(manga.file.absolutePath)

        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_PATH, manga.file.absolutePath)
            putExtra(ReaderActivity.EXTRA_TITLE, manga.title)
            putExtra(ReaderActivity.EXTRA_LAST_PAGE, lastPage)
        }

        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // Al volver de un manga (favorito añadido, lectura) refrescar la pestaña.
        if (currentTab == TAB_FAVORITES || currentTab == TAB_HISTORY || currentTab == TAB_RECENT) {
            loadTab()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isPrivateMode) {
            exitPrivateMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun net.spin.tachiyomi.legacy.data.model.MangaRef.toOnlineItem(): LibraryItem.Online {
        return LibraryItem.Online(
            sourceId = sourceId,
            url = url,
            title = title,
            thumbnailUrl = thumbnailUrl,
            subtitle = sourceName(sourceId),
        )
    }

    private fun net.spin.tachiyomi.legacy.data.model.HistoryRef.toOnlineItem(): LibraryItem.Online {
        val progress = if (lastTotalPages > 0) {
            (lastPageIndex.toFloat() / lastTotalPages).coerceIn(0f, 1f)
        } else {
            0f
        }
        val chapterLabel = lastChapterName?.takeIf { it.isNotBlank() } ?: ""
        val subtitle = buildString {
            if (chapterLabel.isNotBlank()) {
                append(chapterLabel)
                if (lastTotalPages > 0) append(" · ${"%.0f".format(progress * 100)}%")
                append(" · ")
            }
            append(formatRelativeDate(lastReadAt))
        }
        return LibraryItem.Online(
            sourceId = sourceId,
            url = url,
            title = title,
            thumbnailUrl = thumbnailUrl,
            subtitle = subtitle,
            readPercent = progress,
            isHistory = true,
            lastChapterUrl = lastChapterUrl,
            lastChapterName = lastChapterName,
            lastPageIndex = lastPageIndex,
        )
    }

    private fun sourceName(sourceId: Long): String {
        return SourceManager.getByIdOrNull(sourceId)?.name ?: "Fuente $sourceId"
    }

    private fun formatRelativeDate(dateMillis: Long): String = TimeUtil.formatRelative(dateMillis)

    private class MangaAdapter(
        private val onClick: (LibraryItem) -> Unit,
        private val onLongClick: (LibraryItem) -> Boolean
    ) : RecyclerView.Adapter<MangaAdapter.VH>() {

        private var items = listOf<LibraryItem>()

        fun submit(list: List<LibraryItem>) {
            items = list
            notifyDataSetChanged()
        }

        fun clear() {
            items = emptyList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_manga, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.bind(item)
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {

            val title: TextView = view.findViewById(R.id.mangaTitle)
            val subtitle: TextView = view.findViewById(R.id.mangaSubtitle)
            val cover: ImageView = view.findViewById(R.id.coverImage)
            val coverLoader: ProgressBar = view.findViewById(R.id.coverLoader)
            val progressBar: ProgressBar = view.findViewById(R.id.progressBar)

            fun bind(item: LibraryItem) {
                title.text = item.title

                itemView.setOnClickListener { onClick(item) }
                itemView.setOnLongClickListener { onLongClick(item) }

                when (item) {
                    is LibraryItem.Local -> bindLocal(item.manga)
                    is LibraryItem.Online -> bindOnline(item)
                }
            }

            private fun bindLocal(manga: MangaFile) {
                val expectedKey = manga.file.absolutePath + ":" + manga.file.lastModified()

                subtitle.visibility = View.GONE
                cover.tag = expectedKey

                val last = Prefs.getLastPage(manga.file.absolutePath)
                val total = Prefs.getTotalPages(manga.file.absolutePath)

                if (total > 0) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = ((last.toFloat() / total) * 100).toInt()
                } else {
                    progressBar.visibility = View.GONE
                }

                val cached = ThumbnailManager.getCachedThumb(manga.file)

                if (cached != null) {
                    loadThumb(cached)
                } else {
                    cover.setImageDrawable(null)
                    coverLoader.visibility = View.VISIBLE

                    ThumbnailManager.generateThumbAsync(manga.file) { thumbFile ->
                        cover.post {
                            if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@post
                            if (cover.tag != expectedKey) return@post

                            if (thumbFile != null) {
                                loadThumb(thumbFile)
                            } else {
                                coverLoader.visibility = View.GONE
                            }
                        }
                    }
                }
            }

            private fun bindOnline(item: LibraryItem.Online) {
                cover.tag = item.url
                coverLoader.visibility = View.VISIBLE

                if (item.subtitle != null) {
                    subtitle.text = item.subtitle
                    subtitle.visibility = View.VISIBLE
                } else {
                    subtitle.visibility = View.GONE
                }

                if (item.readPercent > 0f) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = (item.readPercent * 100).toInt()
                } else {
                    progressBar.visibility = View.GONE
                }

                item.thumbnailUrl?.let { url ->
                    ImageLoader.load(url) { bmp ->
                        cover.post {
                            if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@post
                            if (cover.tag != item.url) return@post
                            coverLoader.visibility = View.GONE
                            if (bmp != null && !bmp.isRecycled) {
                                cover.setImageBitmap(bmp)
                            } else {
                                cover.setImageDrawable(null)
                            }
                        }
                    }
                } ?: run {
                    coverLoader.visibility = View.GONE
                    cover.setImageDrawable(null)
                }
            }

            private fun loadThumb(file: File) {
                coverLoader.visibility = View.GONE

                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inMutable = false
                }

                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)

                if (bmp != null && !bmp.isRecycled) {
                    cover.setImageBitmap(bmp)
                }
            }
        }
    }

    companion object {
        const val TAB_LOCAL = 0
        const val TAB_FAVORITES = 1
        const val TAB_HISTORY = 2
        const val TAB_RECENT = 3
    }
}
