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
import net.spin.tachiyomi.legacy.databinding.ActivityLibraryBinding
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
            onClick = ::openReader,
            onLongClick = ::onMangaLongClick
        )

        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.itemAnimator = null
        binding.recycler.adapter = adapter

        setupSearchToggle()
        setupSort()
        setupRefreshButton()

        BottomNavHelper.setup(this, binding.bottomNav, BottomNavHelper.TAB_LIBRARY)

        currentSort = Prefs.getSortMode()
        binding.sortSpinner.setSelection(currentSort)

        checkPermissionAndLoad()
    }

    private fun setupRefreshButton() {
        binding.btnRefresh.setOnClickListener {
            refreshLibrary()
        }
    }

    private fun refreshLibrary() {
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
        val sourceList = if (isPrivateMode) privateMangas else allMangas

        var list = sourceList

        if (currentQuery.isNotBlank() && !isPrivateMode) {
            val q = currentQuery.lowercase()
            list = list.filter { it.title.lowercase().contains(q) }
        }

        list = when (currentSort) {
            0 -> list.sortedBy { it.title.lowercase() }
            1 -> list.sortedByDescending { it.file.lastModified() }
            2 -> list.sortedByDescending { it.file.length() }
            3 -> list.sortedByDescending { manga ->
                val last = Prefs.getLastPage(manga.file.absolutePath)
                val total = Prefs.getTotalPages(manga.file.absolutePath)
                if (total > 0) last.toFloat() / total else 0f
            }
            else -> list
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

    private class MangaAdapter(
        private val onClick: (MangaFile) -> Unit,
        private val onLongClick: (MangaFile) -> Unit
    ) : RecyclerView.Adapter<MangaAdapter.VH>() {

        private var items = listOf<MangaFile>()

        fun submit(list: List<MangaFile>) {
            items = list
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
            val cover: ImageView = view.findViewById(R.id.coverImage)
            val coverLoader: ProgressBar = view.findViewById(R.id.coverLoader)
            val progressBar: ProgressBar = view.findViewById(R.id.progressBar)

            fun bind(manga: MangaFile) {
                val expectedKey = manga.file.absolutePath + ":" + manga.file.lastModified()

                title.text = manga.title
                cover.tag = expectedKey

                itemView.setOnClickListener { onClick(manga) }

                itemView.setOnLongClickListener {
                    onLongClick(manga)
                    true
                }

                val last = Prefs.getLastPage(manga.file.absolutePath)
                val total = Prefs.getTotalPages(manga.file.absolutePath)

                if (total > 0) {
                    progressBar.progress = ((last.toFloat() / total) * 100).toInt()
                } else {
                    progressBar.progress = 0
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
}
