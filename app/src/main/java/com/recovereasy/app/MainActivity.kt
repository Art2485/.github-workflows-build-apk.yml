package com.recovereasy.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ItemAdapter
    private lateinit var engine: RecoverEasyEngine

    private val items = mutableListOf<RecoverEasyEngine.Item>()
    private val selected = mutableSetOf<Int>()
    private var selectAllMode = false

    // ยกเลิกงานสแกน/คัดลอกได้
    @Volatile private var cancelled = false
    private var scanJob: Job? = null

    // ---------- Activity results ----------
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        startFolderScan(uri)
    }

    private var pendingCopyIndices: IntArray? = null
    private val pickDest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val idx = pendingCopyIndices
        pendingCopyIndices = null
        if (uri == null || idx == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        doCopySelected(uri, idx)
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = RecoverEasyEngine(this)
        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listResults)

        adapter = ItemAdapter(this, items, selected) { pos ->
            toggleSelect(pos)
        }
        listView.adapter = adapter

        // ปุ่มสแกนโทรศัพท์
        findViewById<Button>(R.id.btnScanPhone).setOnClickListener {
            doScan(kind = "phone")
        }

        // ปุ่มสแกน SD/OTG
        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            doScan(kind = "removable")
        }

        // ปุ่มเลือกโฟลเดอร์
        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            pickFolder.launch(null)
        }

        // ปุ่มเลือกทั้งหมด (กดซ้ำ = ยกเลิกทั้งหมด)
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            selectAllMode = !selectAllMode
            if (selectAllMode) {
                selected.clear()
                repeat(items.size) { selected += it }
                tvStatus.text = "Selected ${selected.size}/${items.size}"
            } else {
                selected.clear()
                tvStatus.text = "Selected 0/${items.size}"
            }
            adapter.notifyDataSetChanged()
        }

        // ปุ่มพรีวิว (เปิดดูรายการแรกที่เลือก)
        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            val first = selected.firstOrNull()
            if (first == null) {
                Toast.makeText(this, "Select an item", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val it = items[first]
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it.uri, it.mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(view)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show()
            }
        }

        // ปุ่มคัดลอกที่เลือก
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select items to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingCopyIndices = selected.sorted().toIntArray()
            pickDest.launch(null)
        }

        // ปุ่ม REPAIR (ยังไม่ทำ – กัน compile error / UI ยังใช้งานได้)
        findViewById<Button>(R.id.btnRepair)?.setOnClickListener {
            Toast.makeText(this, "Repair is not implemented yet.", Toast.LENGTH_SHORT).show()
        }

        tvStatus.text = "Ready."
    }

    // ---------- scanning ----------
    private fun doScan(kind: String) {
        // ยกเลิกงานก่อนหน้า (ถ้ามี)
        cancelled = true
        scanJob?.cancel()

        cancelled = false
        items.clear()
        selected.clear()
        adapter.notifyDataSetChanged()
        tvStatus.text = "Preparing scan..."

        scanJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (kind) {
                        "phone" -> engine.scanPhoneAll(
                            includeTrash = true,
                            onProgress = { done, total ->
                                updateProgress(done, total, prefix = "Scanning phone")
                            },
                            isCancelled = { cancelled }
                        )
                        "removable" -> engine.scanRemovableAll(
                            includeTrash = true,
                            onProgress = { done, total ->
                                updateProgress(done, total, prefix = "Scanning SD/OTG")
                            },
                            isCancelled = { cancelled }
                        )
                        else -> emptyList()
                    }
                }
                items.clear()
                items.addAll(result)
                tvStatus.text = "Found: ${items.size} items"
                adapter.notifyDataSetChanged()
            } catch (t: CancellationException) {
                tvStatus.text = "Scan cancelled."
            } catch (t: Throwable) {
                tvStatus.text = "Scan error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun startFolderScan(uri: Uri) {
        cancelled = true
        scanJob?.cancel()

        cancelled = false
        items.clear()
        selected.clear()
        adapter.notifyDataSetChanged()
        tvStatus.text = "Scanning folder..."

        scanJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                val result = withContext(Dispatchers.IO) {
                    engine.scanByFolderAllTypes(
                        tree = uri,
                        onProgress = { done, total ->
                            updateProgress(done, total, prefix = "Scanning folder")
                        },
                        isCancelled = { cancelled }
                    )
                }
                items.clear()
                items.addAll(result)
                tvStatus.text = "Found: ${items.size} items"
                adapter.notifyDataSetChanged()
            } catch (t: CancellationException) {
                tvStatus.text = "Scan cancelled."
            } catch (t: Throwable) {
                tvStatus.text = "Scan error: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun updateProgress(done: Int, total: Int, prefix: String) {
        val pct = if (total <= 0) 0 else (done * 100 / total)
        runOnUiThread {
            tvStatus.text = "$prefix... $pct% ($done/$total)"
        }
    }

    // ---------- copy ----------
    private fun doCopySelected(destTree: Uri, indices: IntArray) {
        lifecycleScope.launch(Dispatchers.Main) {
            var ok = 0
            var fail = 0
            tvStatus.text = "Copying ${indices.size} items..."
            withContext(Dispatchers.IO) {
                for ((i, idx) in indices.withIndex()) {
                    val it = items.getOrNull(idx) ?: continue
                    try {
                        val newUri = engine.safeCopyWithDigest(it.uri, destTree, it.name)
                        if (newUri != null) ok++ else fail++
                    } catch (_: Throwable) {
                        fail++
                    }
                    val pct = (i + 1) * 100 / indices.size
                    runOnUiThread { tvStatus.text = "Copying... $pct% ($ok ok, $fail fail)" }
                }
            }
            tvStatus.text = "Copied: $ok / ${indices.size} (fail $fail)"
        }
    }

    // ---------- selection helpers ----------
    private fun toggleSelect(position: Int) {
        if (selected.contains(position)) selected.remove(position) else selected.add(position)
        tvStatus.text = "Selected ${selected.size}/${items.size}"
    }

    // ---------- Adapter (thumbnail + checkbox) ----------
    private class ItemAdapter(
        activity: MainActivity,
        private val data: List<RecoverEasyEngine.Item>,
        private val selected: Set<Int>,
        private val onRowClick: (Int) -> Unit
    ) : BaseAdapter() {

        private val actRef = WeakReference(activity)
        private val scope = activity.lifecycleScope
        private val inflater = LayoutInflater.from(activity)

        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v: View
            val h: VH
            if (convertView == null) {
                v = inflater.inflate(R.layout.row_item_thumb, parent, false)
                h = VH(
                    v.findViewById(R.id.imgThumb),
                    v.findViewById(R.id.txtName),
                    v.findViewById(R.id.chk)
                )
                v.tag = h
            } else {
                v = convertView
                h = v.tag as VH
            }

            val item = data[position]
            h.txt.text = (if (item.isTrashed) "[TRASH] " else "") + item.name
            h.chk.isChecked = selected.contains(position)

            v.setOnClickListener { onRowClick(position); h.chk.isChecked = !h.chk.isChecked }
            h.chk.setOnClickListener { onRowClick(position) }

            // โหลดรูปตัวอย่างแบบเบา ๆ
            h.img.setImageDrawable(null)
            val act = actRef.get()
            if (act != null) {
                scope.launch(Dispatchers.IO) {
                    val bmp = try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            act.contentResolver.loadThumbnail(item.uri, Size(128, 128), null)
                        } else {
                            // fallback (ไม่ค่อยได้ใช้บน S25)
                            act.contentResolver.openInputStream(item.uri)?.use {
                                android.graphics.BitmapFactory.decodeStream(it)
                            }
                        }
                    } catch (_: Throwable) { null }

                    withContext(Dispatchers.Main) {
                        if (act.isFinishing || act.isDestroyed) return@withContext
                        if (bmp != null) h.img.setImageBitmap(bmp) else h.img.setImageResource(android.R.color.transparent)
                    }
                }
            }

            return v
        }

        private data class VH(
            val img: ImageView,
            val txt: TextView,
            val chk: CheckBox
        )
    }
}
