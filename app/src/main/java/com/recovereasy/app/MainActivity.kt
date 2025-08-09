package com.recovereasy.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar
    private lateinit var btnCancel: Button
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var engine: RecoverEasyEngine

    private val items = mutableListOf<RecoverEasyEngine.Item>()
    private val visibleIndices = mutableListOf<Int>()
    private val damagedSet = mutableSetOf<Int>()

    // ยกเลิก + เวลา/ETA + ให้ progress แสดงอย่างน้อยช่วงสั้น ๆ
    @Volatile private var cancelRequested = false
    private var scanStartMs: Long = 0L
    private var progressShownAtMs: Long = 0L
    private val minProgressVisibleMs = 700L

    private enum class Action { COPY, REPAIR }
    private var pendingAction: Action? = null
    private var pendingIndices: IntArray? = null

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val pickDest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val act = pendingAction
        val idx = pendingIndices
        pendingAction = null; pendingIndices = null
        if (uri == null || act == null || idx == null) return@registerForActivityResult

        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        lifecycleScope.launch {
            var ok = 0
            try {
                when (act) {
                    Action.COPY -> for (i in idx) engine.safeCopyWithDigest(items[i].uri, uri, items[i].name)?.let { ok++ }
                    Action.REPAIR -> for (i in idx) engine.repairBestEffort(items[i], uri)?.let { ok++ }
                }
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, t.message ?: "Error", Toast.LENGTH_LONG).show()
            }
            tvStatus.text = "${act.name.lowercase().replaceFirstChar { it.uppercase() }}: $ok / ${idx.size}"
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        lifecycleScope.launch {
            startProgress("Scanning folder...")
            // เรียกเอนจินเวอร์ชันที่ส่ง percent, processed, expected, note
            val list = engine.scanByFolderAllTypes(
                treeUri = uri,
                progress = { pct, processed, expected, note -> updateProgress(pct, processed, expected, note) },
                cancelled = { cancelRequested }
            )
            stopProgress(cancelRequested)
            setItems(list)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listResults)
        progress = findViewById(R.id.progress)
        btnCancel = findViewById(R.id.btnCancel)

        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
        listView.adapter = adapter
        engine = RecoverEasyEngine(this)

        btnCancel.setOnClickListener {
            cancelRequested = true
            tvStatus.text = "Cancelling..."
        }

        findViewById<Button>(R.id.btnScanPhone).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                startProgress("Scanning phone...")
                val found = engine.scanPhoneAll(
                    includeTrash = true,
                    progress = { pct, processed, expected, note -> updateProgress(pct, processed, expected, note) },
                    cancelled = { cancelRequested }
                )
                stopProgress(cancelRequested)
                setItems(found)
            }
        }

        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                startProgress("Scanning SD/OTG...")
                val found = engine.scanRemovableAll(
                    includeTrash = true,
                    progress = { pct, processed, expected, note -> updateProgress(pct, processed, expected, note) },
                    cancelled = { cancelRequested }
                )
                stopProgress(cancelRequested)
                setItems(found)
            }
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { pickFolder.launch(null) }

        // SELECT ALL: ใช้สถานะแถวจริงจาก listView.count (ไม่อิงค่าสะสม)
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { toggleSelectAll() }

        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            val globalIndex = firstCheckedGlobalIndex() ?: return@setOnClickListener
            val it = items[globalIndex]
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it.uri, it.mime ?: "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(view) } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app to open", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val globalIdx = checkedGlobalIndices()
                ?: return@setOnClickListener Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
            pendingAction = Action.COPY; pendingIndices = globalIdx; pickDest.launch(null)
        }

        findViewById<Button>(R.id.btnCheckDamaged).setOnClickListener {
            lifecycleScope.launch {
                tvStatus.text = "Checking..."
                damagedSet.clear()
                for (pos in items.indices) engine.detectCorruption(items[pos])?.let { damagedSet += pos }
                refreshList(); updateStatus("Checked")
            }
        }

        findViewById<Button>(R.id.btnRepairSelected).setOnClickListener {
            val globalIdx = checkedGlobalIndices()
                ?: return@setOnClickListener Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
            pendingAction = Action.REPAIR; pendingIndices = globalIdx; pickDest.launch(null)
        }

        findViewById<CheckBox>(R.id.cbShowDamagedOnly).setOnCheckedChangeListener { _, _ -> refreshList() }
        findViewById<CheckBox>(R.id.cbShowTrashOnly).setOnCheckedChangeListener { _, _ -> refreshList() }
    }

    // ===== Progress + ETA =====
    private fun startProgress(status: String) {
        cancelRequested = false
        scanStartMs = System.currentTimeMillis()
        progressShownAtMs = scanStartMs
        progress.visibility = View.VISIBLE
        btnCancel.visibility = View.VISIBLE
        progress.progress = 0
        tvStatus.text = "$status 0% • ~--:-- left"
        setScanButtonsEnabled(false)
    }

    private fun updateProgress(pct: Int, processed: Int, expected: Int, note: String) {
        val now = System.currentTimeMillis()
        val elapsedSec = ((now - scanStartMs).coerceAtLeast(1)).toDouble() / 1000.0
        val rate = if (elapsedSec > 0) processed / elapsedSec else 0.0 // items/sec
        val remain = (expected - processed).coerceAtLeast(0)
        val etaSec = if (rate > 0) (remain / rate) else 0.0
        val etaText = formatEta(etaSec)
        runOnUiThread {
            progress.progress = pct.coerceIn(0, 100)
            tvStatus.text = "$note ${progress.progress}% • ~$etaText left"
        }
    }

    private fun formatEta(etaSec: Double): String {
        val s = etaSec.roundToInt().coerceAtLeast(0)
        val m = s / 60
        val ss = s % 60
        return "%d:%02d".format(m, ss)
    }

    private fun setScanButtonsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.btnScanPhone).isEnabled = enabled
        findViewById<Button>(R.id.btnScanRemovable).isEnabled = enabled
        findViewById<Button>(R.id.btnPickFolder).isEnabled = enabled
    }

    private fun stopProgress(wasCancelled: Boolean) {
        lifecycleScope.launch {
            // บังคับให้ progress โชว์อย่างน้อย minProgressVisibleMs (กันกรณีสแกนเร็วมากจนไม่เห็น)
            val shownFor = System.currentTimeMillis() - progressShownAtMs
            if (shownFor < minProgressVisibleMs) delay(minProgressVisibleMs - shownFor)

            progress.visibility = View.GONE
            btnCancel.visibility = View.GONE
            if (wasCancelled) tvStatus.text = "Cancelled."
            setScanButtonsEnabled(true)
        }
    }

    // ===== สแกน & แสดงรายการ =====
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
        refreshList()
        updateStatus("Found")
    }

    private fun updateStatus(prefix: String) {
        val phone = items.count { it.volumeId?.equals("external_primary", true) == true }
        val sd = items.count { it.volumeId?.matches(Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")) == true }
        val trash = items.count { it.isTrashed }
        val damaged = damagedSet.size
        tvStatus.text = "$prefix: ${items.size} (Phone:$phone  SD/OTG:$sd  Trash:$trash  Damaged:$damaged)"
    }

    private fun refreshList() {
        val showDamagedOnly = findViewById<CheckBox>(R.id.cbShowDamagedOnly).isChecked
        val showTrashOnly = findViewById<CheckBox>(R.id.cbShowTrashOnly).isChecked

        visibleIndices.clear()
        for (i in items.indices) {
            val keepDamaged = !showDamagedOnly || damagedSet.contains(i)
            val keepTrash = !showTrashOnly || items[i].isTrashed
            if (keepDamaged && keepTrash) visibleIndices += i
        }

        val names = visibleIndices.map { idx ->
            val it = items[idx]
            val src = sourceLabel(it)
            val tags = buildList {
                add(src)
                if (it.isTrashed) add("TRASH")
                if (damagedSet.contains(idx)) add("DAMAGED")
            }.joinToString("") { "[$it]" }
            "$tags ${it.name}"
        }

        adapter.clear(); adapter.addAll(names)
        // เมื่อรีเฟรชรายการ เคลียร์สถานะเลือกทิ้งเพื่อกัน mismatch
        for (pos in 0 until listView.count) listView.setItemChecked(pos, false)
        listView.invalidateViews()
    }

    private fun sourceLabel(it: RecoverEasyEngine.Item): String {
        val v = it.volumeId?.lowercase()
        return when {
            v == null -> "FOLDER"
            v == "external_primary" -> "PHONE"
            v.matches(Regex("^[0-9a-f]{4}-[0-9a-f]{4}$")) -> "SD/OTG ${it.volumeId}"
            else -> "VOL ${it.volumeId}"
        }
    }

    // ===== เลือกทั้งหมด/ยกเลิกทั้งหมด (แก้ให้ชัวร์) =====
    private fun toggleSelectAll() {
        val count = listView.count
        if (count <= 0) return
        var anyUnchecked = false
        for (pos in 0 until count) {
            if (!listView.isItemChecked(pos)) { anyUnchecked = true; break }
        }
        val check = anyUnchecked // ถ้ามีรายการไหนยังไม่ติ๊ก -> เลือกทั้งหมด, ไม่งั้นยกเลิกทั้งหมด
        for (pos in 0 until count) listView.setItemChecked(pos, check)
        listView.invalidateViews()
    }

    // ===== Mapping helpers =====
    private fun firstCheckedGlobalIndex(): Int? {
        val sparse = listView.checkedItemPositions
        for (i in 0 until sparse.size()) {
            val pos = sparse.keyAt(i)
            if (sparse.valueAt(i)) return visibleIndices.getOrNull(pos)
        }
        return null
    }

    private fun checkedGlobalIndices(): IntArray? {
        val out = mutableListOf<Int>()
        val sparse = listView.checkedItemPositions
        for (i in 0 until sparse.size()) {
            val pos = sparse.keyAt(i)
            if (sparse.valueAt(i)) visibleIndices.getOrNull(pos)?.let { out += it }
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }

    private fun allVisibleSelected(): Boolean {
        if (listView.count == 0) return false
        for (pos in 0 until listView.count) if (!listView.isItemChecked(pos)) return false
        return true
    }
}
