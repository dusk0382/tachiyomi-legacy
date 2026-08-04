package net.spin.tachiyomi.legacy

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun promptAddRepo() {
        val input = android.widget.EditText(this).apply {
            hint = "https://example.com/extensions"
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        AlertDialog.Builder(this)
            .setTitle("Añadir repositorio")
            .setView(input)
            .setPositiveButton("Añadir") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    val manager = app.extensionManager
                    manager.repoBaseUrls = manager.repoBaseUrls + url
                    Toast.makeText(this, "Repo añadido", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
                        b.btnInstall.setOnClickListener { installExtension(item.extension) }
                    }
                    is Item.Untrusted -> {
                        b.btnInstall.text = "Confiar"
                        b.btnInstall.isEnabled = true
                        b.btnInstall.setOnClickListener { trustExtension(item.extension) }
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