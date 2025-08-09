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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var engine: RecoverEasyEngine

    // ข้อมูลทั้งหมด + mapping ของแถวที่มองเห็น (สำหรับโหมด filter)
    private val items = mutableListOf<RecoverEasyEngine.Item>()
    private val visibleIndices = mutableListOf<Int>()          // positionในลิสต์ -> indexจริงใน items
    private val damagedSet = mutableSetOf<Int>()               // indexจริงของ items ที่ตรวจพบว่าเสีย

    // ปุ่ม SELECT ALL แบบ toggle
    private var lastSelectAllWasAll = false

    // action แบบ deferred หลังผู้ใช้เลือกปลายทาง
    private enum class Action { COPY, REPAIR }
    private var pendingAction: Action? = null
    private var pendingIndices: IntArray? = null

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ถ้ายังไม่ให้สิทธิ์ ผู้ใช้สามารถกดสแกนซ้ำได้ */ }

    private val pickDest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val act = pendingAction
        val idx = pendingIndices
        pendingAction = null
        pendingIndices = null
        if (uri == null || act == null || idx == null) return@registerForActivityResult

        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        lifecycleScope.launch {
            try {
                var ok = 0
                when (act) {
                    Action.COPY -> {
                        for (i in idx) {
                            val it = items[i]
                            val out = engine.safeCopyWithDigest(it.uri, uri, it.name)
                            if (out != null) ok++
                        }
                    }
                    Action.REPAIR -> {
                        for (i in idx) {
                            val it = items[i]
                            val out = engine.repairBestEffort(it, uri)
                            if (out != null) ok++
                        }
                    }
                }
                tvStatus.text = "${act.name.lowercase().replaceFirstChar { it.uppercase() }}: $ok / ${idx.size}"
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, t.message ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        lifecycleScope.launch {
            tvStatus.text = "Scanning folder..."
            val list = engine.scanByFolderAllTypes(uri)
            setItems(list)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listResults)
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
        listView.adapter = adapter
        engine = RecoverEasyEngine(this)

        // สแกนเครื่อง
        findViewById<Button>(R.id.btnScanPhone).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                tvStatus.text = "Scanning phone..."
                val found = engine.scanPhoneAll(includeTrash = true)
                setItems(found)
            }
        }

        // สแกน SD/OTG
        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                tvStatus.text = "Scanning SD/OTG..."
                val found = engine.scanRemovableAll(includeTrash = true)
                setItems(found)
            }
        }

        // สแกนแบบเลือกโฟลเดอร์
        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            pickFolder.launch(null)
        }

        // SELECT ALL: Toggle เลือกทั้งหมด/ยกเลิกทั้งหมด ในรายการที่ "มองเห็น"
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            val visibleCount = visibleIndices.size
            val currentSelected = checkedVisiblePositions().size
            val shouldSelectAll = currentSelected < visibleCount || !lastSelectAllWasAll
            if (shouldSelectAll) {
                for (pos in 0 until visibleCount) listView.setItemChecked(pos, true)
                lastSelectAllWasAll = true
            } else {
                for (pos in 0 until visibleCount) listView.setItemChecked(pos, false)
                lastSelectAllWasAll = false
            }
        }

        // PREVIEW รายการแรกที่เลือก
        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            val globalIndex = firstCheckedGlobalIndex() ?: return@setOnClickListener
            val it = items[globalIndex]
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it.uri, it.mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(view)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app to open", Toast.LENGTH_SHORT).show()
            }
        }

        // COPY SELECTED
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val globalIdx = checkedGlobalIndices()
                ?: return@setOnClickListener Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
            pendingAction = Action.COPY
            pendingIndices = globalIdx
            pickDest.launch(null)
        }

        // CHECK DAMAGED: ตรวจไฟล์เสีย (ทั้งหมดที่แสดงอยู่)
        findViewById<Button>(R.id.btnCheckDamaged).setOnClickListener {
            lifecycleScope.launch {
                tvStatus.text = "Checking..."
                damagedSet.clear()
                // ตรวจเฉพาะรายการที่ "มองเห็น" เพื่อเร็วขึ้นเมื่อเปิด filter
                for (pos in visibleIndices) {
                    val report = engine.detectCorruption(items[pos])
                    if (report != null) damagedSet += pos
                }
                refreshList()
                tvStatus.text = "Damaged: ${damagedSet.size} / ${items.size}"
            }
        }

        // REPAIR SELECTED
        findViewById<Button>(R.id.btnRepairSelected).setOnClickListener {
            val globalIdx = checkedGlobalIndices()
                ?: return@setOnClickListener Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
            pendingAction = Action.REPAIR
            pendingIndices = globalIdx
            pickDest.launch(null)
        }

        // Filter: แสดงเฉพาะไฟล์เสีย
        findViewById<CheckBox>(R.id.cbShowDamagedOnly).setOnCheckedChangeListener { _, _ ->
            refreshList()
        }
    }

    // =============== Helpers ===============

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

    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        items.clear(); items.addAll(list)
        damagedSet.clear()
        lastSelectAllWasAll = false
        refreshList()
        tvStatus.text = "Found: ${items.size} items"
    }

    private fun refreshList() {
        // อัปเดต visibleIndices ตาม filter
        visibleIndices.clear()
        val showDamagedOnly = findViewById<CheckBox>(R.id.cbShowDamagedOnly).isChecked
        for (i in items.indices) {
            if (!showDamagedOnly || damagedSet.contains(i)) visibleIndices += i
        }

        // รีเฟรชข้อความในลิสต์
        val names = visibleIndices.map { idx ->
            (if (damagedSet.contains(idx)) "[DAMAGED] " else "") + items[idx].name
        }
        adapter.clear(); adapter.addAll(names)

        // เคลียร์การเลือก เพราะ mapping เปลี่ยน
        for (pos in 0 until listView.count) listView.setItemChecked(pos, false)
    }

    /** คืนตำแหน่ง global index แถวแรกที่ถูกเลือก (หรือ null) */
    private fun firstCheckedGlobalIndex(): Int? {
        val sparse = listView.checkedItemPositions
        for (i in 0 until sparse.size()) {
            val pos = sparse.keyAt(i)
            if (sparse.valueAt(i)) return visibleIndices.getOrNull(pos)
        }
        return null
    }

    /** คืนรายการ global indices ทั้งหมดที่ถูกเลือก (หรือ null หากว่าง) */
    private fun checkedGlobalIndices(): IntArray? {
        val posList = checkedVisiblePositions()
        if (posList.isEmpty()) return null
        return posList.mapNotNull { visibleIndices.getOrNull(it) }.toIntArray()
    }

    /** คืนตำแหน่งในลิสต์ (ที่มองเห็น) ที่ถูกเลือกทั้งหมด */
    private fun checkedVisiblePositions(): List<Int> {
        val out = mutableListOf<Int>()
        val sparse = listView.checkedItemPositions
        for (i in 0 until sparse.size()) {
            val key = sparse.keyAt(i)
            if (sparse.valueAt(i)) out += key
        }
        return out
    }
}
