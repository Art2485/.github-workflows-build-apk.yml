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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MediaAdapter
    private lateinit var spinner: Spinner
    private lateinit var engine: RecoverEasyEngine

    private var allItems: List<RecoverEasyEngine.Item> = emptyList()

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ผู้ใช้ปฏิเสธก็ให้กดซ้ำได้ */ }

    private var pendingCopyUris: List<RecoverEasyEngine.Item>? = null
    private val pickDest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val list = pendingCopyUris
        pendingCopyUris = null
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
            launch(Dispatchers.Main) { tvStatus.text = "Copied: $ok / ${list.size}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        spinner = findViewById(R.id.spinnerFilter)
        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MediaAdapter(contentResolver)
        recycler.adapter = adapter

        engine = RecoverEasyEngine(this)

        // ตั้งค่า Filter ให้ "กดได้ชัวร์"
        val options = arrayOf(
            "All", "Images", "Videos", "Audio", "Documents", "Archives",
            "Trashed only", "Not trashed"
        )
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, options
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long
            ) { applyFilter(options[pos]) }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ปุ่มสแกน
        findViewById<Button>(R.id.btnScanPhone).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                tvStatus.text = "Scanning phone..."
                val results = engine.scanPhoneAll(includeTrash = true)
                setItems(results)
            }
        }
        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                tvStatus.text = "Scanning SD/OTG..."
                val results = engine.scanRemovableAll(includeTrash = true)
                setItems(results)
            }
        }
        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            pickFolder.launch(null)
        }

        // ปุ่ม Select All แบบสลับสถานะ
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { btn ->
            val b = btn as Button
            val select = !adapter.isAllSelected()
            adapter.setAllSelected(select)
            b.text = if (select) "CLEAR ALL" else "SELECT ALL"
            tvStatus.text = "Selected ${adapter.selectedItems().size} / ${adapter.itemCount}"
        }

        // Preview รายการแรกที่เลือก
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
            try { startActivity(view) } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app to open", Toast.LENGTH_SHORT).show()
            }
        }

        // Copy
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val selected = adapter.selectedItems()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingCopyUris = selected
            pickDest.launch(null)
        }

        // ปุ่ม Repair (ยังคงที่, ถ้าไม่มีฟังก์ชันจริงก็ไม่ทำอะไร)
        findViewById<Button>(R.id.btnRepair).setOnClickListener {
            Toast.makeText(this, "Repair not implemented", Toast.LENGTH_SHORT).show()
        }

        tvStatus.text = "Ready."
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        lifecycleScope.launch {
            tvStatus.text = "Scanning folder..."
            val list = engine.scanByFolderAllTypes(uri)
            setItems(list)
        }
    }

    private fun ensureMediaPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val perms = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        val need = perms.any {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need) reqPerms.launch(perms)
        return !need
    }

    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        allItems = list
        applyFilter(spinner.selectedItem?.toString() ?: "All")
        findViewById<Button>(R.id.btnSelectAll).text = "SELECT ALL"
        tvStatus.text = "Found: ${list.size} items"
    }

    private fun applyFilter(choice: String) {
        val filtered = when (choice) {
            "Images" -> allItems.filter { it.mime?.startsWith("image/") == true }
            "Videos" -> allItems.filter { it.mime?.startsWith("video/") == true }
            "Audio" -> allItems.filter { it.mime?.startsWith("audio/") == true }
            "Documents" -> allItems.filter {
                val m = it.mime ?: ""
                !m.startsWith("image/") && !m.startsWith("video/") && !m.startsWith("audio/")
                        && !(m.contains("zip") || it.name.endsWith(".zip", true)
                        || it.name.endsWith(".rar", true) || it.name.endsWith(".7z", true)
                        || it.name.endsWith(".tar", true) || it.name.endsWith(".gz", true))
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
        tvStatus.text = "Showing ${filtered.size} / ${allItems.size}"
    }
}
