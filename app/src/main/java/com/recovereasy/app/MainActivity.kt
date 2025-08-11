package com.recovereasy.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnCancel: Button
    private lateinit var recycler: RecyclerView
    private lateinit var spinner: Spinner
    private lateinit var adapter: MediaAdapter
    private lateinit var engine: RecoverEasyEngine

    private var allItems: List<RecoverEasyEngine.Item> = emptyList()
    private var scanJob: Job? = null

    // permissions
    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user can tap again if denied */ }

    // copy
    private var pendingCopy: List<RecoverEasyEngine.Item>? = null
    private val pickDest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val list = pendingCopy
        pendingCopy = null
        if (uri == null || list == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0
            list.forEach {
                val out = engine.safeCopyWithDigest(it.uri, uri, it.name)
                if (out != null) ok++
            }
            withContext(Dispatchers.Main) {
                tvStatus.text = "Copied: $ok / ${list.size}"
            }
        }
    }

    // pick folder
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        startScan(kind = "folder", root = uri)
    }

    // pick Android/media root
    private val pickMediaRoot = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        startScan(kind = "appcaches", root = uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        progress = findViewById(R.id.progress)
        btnCancel = findViewById(R.id.btnCancel)
        spinner = findViewById(R.id.spinnerFilter)
        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MediaAdapter(contentResolver)
        recycler.adapter = adapter

        engine = RecoverEasyEngine(this)

        // filter
        val choices = arrayOf(
            "All","Images","Videos","Audio","Documents","Archives","Trashed only","Not trashed"
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                applyFilter(choices[pos])
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnScanPhone).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            startScan(kind = "phone")
        }
        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            startScan(kind = "removable")
        }
        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.btnScanAppCaches).setOnClickListener { pickMediaRoot.launch(null) }
        findViewById<Button>(R.id.btnOpenGalleryTrash).setOnClickListener { openSamsungGallery() }

        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { btn ->
            val select = !adapter.isAllSelected()
            adapter.setAllSelected(select)
            (btn as Button).text = if (select) "CLEAR ALL" else "SELECT ALL"
            tvStatus.text = "Selected ${adapter.selectedItems().size} / ${adapter.itemCount}"
        }

        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            val first = adapter.selectedItems().firstOrNull()
            if (first == null) {
                Toast.makeText(this, "Select an item", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(first.uri, first.mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(view) }
            catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app to open", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val sel = adapter.selectedItems()
            if (sel.isEmpty()) {
                Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingCopy = sel
            pickDest.launch(null)
        }

        // cancel scan
        btnCancel.setOnClickListener { scanJob?.cancel() }

        tvStatus.text = "Ready."
    }

    // ------------------------------------------------------------
    // Scan orchestrator with progress + cancel
    // ------------------------------------------------------------
    private fun startScan(kind: String, root: Uri? = null) {
        scanJob?.cancel()
        adapter.submitList(emptyList())
        progress.isIndeterminate = true
        progress.progress = 0
        tvStatus.text = "Scanning..."

        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            var lastPct = -1
            val items = when (kind) {
                "phone" -> engine.scanPhoneAll(includeTrash = true,
                    onProgress = { p, t -> updateProgress(p, t) },
                    isCancelled = { !isActive })
                "removable" -> engine.scanRemovableAll(includeTrash = true,
                    onProgress = { p, t -> updateProgress(p, t) },
                    isCancelled = { !isActive })
                "folder" -> engine.scanByFolderAllTypes(root!!,
                    onProgress = { p, t -> updateProgress(p, t) },
                    isCancelled = { !isActive })
                "appcaches" -> engine.scanAppCaches(root!!,
                    onProgress = { p, t -> updateProgress(p, t) },
                    isCancelled = { !isActive })
                else -> emptyList()
            }
            withContext(Dispatchers.Main) {
                allItems = items
                applyFilter(spinner.selectedItem?.toString() ?: "All")
                progress.isIndeterminate = false
                progress.progress = 100
                tvStatus.text = "Found: ${items.size} items"
            }
        }.also { job ->
            job.invokeOnCompletion { e ->
                runOnUiThread {
                    if (e is CancellationException) {
                        adapter.submitList(emptyList())
                        tvStatus.text = "Canceled."
                        progress.isIndeterminate = false
                        progress.progress = 0
                    }
                }
            }
        }
    }

    private fun updateProgress(processed: Int, total: Int) {
        val pct = (processed * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
        runOnUiThread {
            progress.isIndeterminate = false
            progress.progress = pct
            tvStatus.text = "Scanning… $processed / $total ($pct%)"
        }
    }

    // ------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------
    private fun applyFilter(choice: String) {
        val filtered = when (choice) {
            "Images" -> allItems.filter { it.mime?.startsWith("image/") == true }
            "Videos" -> allItems.filter { it.mime?.startsWith("video/") == true }
            "Audio" -> allItems.filter { it.mime?.startsWith("audio/") == true }
            "Documents" -> allItems.filter {
                val m = it.mime ?: ""
                (m.startsWith("application/") || m.startsWith("text/")) &&
                        !m.contains("zip") && !m.contains("rar") && !m.contains("7z")
            }
            "Archives" -> allItems.filter {
                val n = it.name.lowercase()
                val m = it.mime?.lowercase() ?: ""
                n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
                        || n.endsWith(".tar") || n.endsWith(".gz")
                        || m.contains("zip") || m.contains("x-rar") || m.contains("x-7z")
            }
            "Trashed only" -> allItems.filter { it.isTrashed }
            "Not trashed" -> allItems.filter { !it.isTrashed }
            else -> allItems
        }
        adapter.submitList(filtered)
        adapter.clearSelection()
        findViewById<Button>(R.id.btnSelectAll).text = "SELECT ALL"
        tvStatus.text = "Showing ${filtered.size} / ${allItems.size}"
    }

    // ------------------------------------------------------------
    // Permissions & helpers
    // ------------------------------------------------------------
    private fun ensureMediaPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val perms = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        val need = perms.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need) reqPerms.launch(perms)
        return !need
    }

    private fun openSamsungGallery() {
        try {
            // พยายามเปิดแอปแกลเลอรีซัมซุง
            val launch = packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Samsung Gallery not found", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Unable to open Gallery", Toast.LENGTH_SHORT).show()
        }
    }
}
