package net.spin.tachiyomi.legacy

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.launch
import net.spin.tachiyomi.legacy.databinding.ActivityExtensionsBinding
import net.spin.tachiyomi.legacy.databinding.ItemExtensionBinding

/**
 * Manages extension repositories and installed/untrusted extensions.
 * Available extensions are fetched from the configured repos and can be
 * privately installed (downloaded into filesDir/exts, no PackageInstaller).
 */
class ExtensionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExtensionsBinding
    private val app: App get() = application as App

    private val adapter = ExtAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnAddRepo.setOnClickListener { promptAddRepo() }

        BottomNavHelper.setup(this, binding.bottomNav.root, BottomNavHelper.TAB_EXTENSIONS)

        refresh()
    }

    private fun refresh() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE

        lifecycleScope.launch {
            val manager = app.extensionManager
            manager.findAvailableExtensions()
            manager.reloadInstalled()

            val installed = manager.installedExtensions.map { it.pkgName }.toSet()
            val untrusted = manager.untrustedExtensions.map { it.pkgName }.toSet()
            val items = manager.availableExtensions.map { ext ->
                when {
                    ext.pkgName in installed -> Item.Installed(ext)
                    ext.pkgName in untrusted -> {
                        Item.Untrusted(manager.untrustedExtensions.first { it.pkgName == ext.pkgName })
                    }
                    else -> Item.Available(ext)
                }
            }

            adapter.submit(items)
            binding.progressBar.visibility = View.GONE

            val error = manager.repoFetchError
            if (items.isEmpty() && error != null) {
                binding.emptyText.text = "No se pudieron cargar extensiones:\n$error"
                binding.emptyText.visibility = View.VISIBLE
            } else {
                binding.emptyText.text = "Sin extensiones disponibles"
                binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun promptAddRepo() {
        val input = android.widget.EditText(this).apply {
            hint = "https://raw.githubusercontent.com/tachiyomiorg/extensions/repo/index.json"
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addRepo(this.text.toString())
                    true
                } else {
                    false
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Añadir repositorio")
            .setMessage("Pega la URL del índice del repo (index.json, index.min.json o la base del repo). El repo por defecto es el archivado original (tachiyomiorg, minSdk 21), el único compatible con Android 6.")
            .setView(input)
            .setPositiveButton("Añadir") { _, _ -> addRepo(input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()

        // Evita que el teclado tape los botones al abrir el diálogo.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
    }

    private fun addRepo(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val manager = app.extensionManager
        manager.repoBaseUrls = manager.repoBaseUrls + trimmed
        Toast.makeText(this, "Repo añadido", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun installExtension(ext: Extension.Available) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = app.extensionManager.installExtension(ext)
            Toast.makeText(
                this@ExtensionsActivity,
                if (ok) "Extensiones instalada" else "Error instalando extensión",
                Toast.LENGTH_SHORT,
            ).show()
            binding.progressBar.visibility = View.GONE
            refresh()
        }
    }

    private fun trustExtension(ext: Extension.Untrusted) {
        app.extensionManager.trust(ext)
        Toast.makeText(this, "Extensión confiada", Toast.LENGTH_SHORT).show()
        refresh()
    }

    inner class ExtAdapter : RecyclerView.Adapter<ExtAdapter.VH>() {

        private val items = mutableListOf<Item>()

        fun submit(newItems: List<Item>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemExtensionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class VH(private val b: ItemExtensionBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: Item) {
                val ext = item.extension
                b.extName.text = ext.name
                b.extMeta.text = "${ext.versionName} · ${ext.lang ?: ""}"
                b.btnInstall.visibility = View.VISIBLE
                when (item) {
                    is Item.Available -> {
                        b.btnInstall.text = "Instalar"
                        b.btnInstall.isEnabled = true
                        b.btnInstall.setOnClickListener {
                            installExtension(item.extension as Extension.Available)
                        }
                    }
                    is Item.Untrusted -> {
                        b.btnInstall.text = "Confiar"
                        b.btnInstall.isEnabled = true
                        b.btnInstall.setOnClickListener {
                            trustExtension(item.extension as Extension.Untrusted)
                        }
                    }
                    is Item.Installed -> {
                        b.btnInstall.text = "Instalada"
                        b.btnInstall.isEnabled = false
                    }
                }
            }
        }
    }

    sealed class Item(val extension: Extension) {
        class Available(ext: Extension.Available) : Item(ext)
        class Untrusted(ext: Extension.Untrusted) : Item(ext)
        class Installed(ext: Extension.Available) : Item(ext)
    }
}