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
import com.recovereasy.app.R
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var engine: RecoverEasyEngine

    // Progress UI
    private lateinit var progressGroup: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnCancel: Button

    // Select-all toggle
    private lateinit var btnSelectAll: Button
    private var allSelected = false

    // Filter
    private lateinit var spinnerFilter: Spinner
    private val allItems = mutableListOf<RecoverEasyEngine.Item>()  // เก็บทั้งหมด
    private val shownItems = mutableListOf<RecoverEasyEngine.Item>() // หลังกรอง

    // Jobs
    private var scanJob: Job? = null
    private var animateJob: Job? = null
    @Volatile private var cancelRequested = false

    // Copy/Repair
    private var pendingCopyIndices: IntArray? = null
    private var pendingRepairIndices: IntArray? = null

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val pickDest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val copyIdx = pendingCopyIndices
        val repairIdx = pendingRepairIndices
        pendingCopyIndices = null
        pendingRepairIndices = null
        if (uri == null) return@registerForActivityResult

        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var ok = 0
                when {
                    copyIdx != null -> {
                        for (i in copyIdx) {
                            val it = shownItems[i]
                            val out = engine.safeCopyWithDigest(it.uri, uri, it.name)
                            if (out != null) ok++
                        }
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Copied: $ok / ${copyIdx.size}"
                        }
                    }
                    repairIdx != null -> {
                        for (i in repairIdx) {
                            val it = shownItems[i]
                            // ถ้าเอ็นจินมี repairBestEffort ให้ใช้เลย; ถ้าไม่มีจะ fallback เป็น safeCopy
                            val out = runCatching { 
                                engine.repairBestEffort(it.uri, uri) 
                            }.getOrNull() ?: engine.safeCopyWithDigest(it.uri, uri, it.name)
                            if (out != null) ok++
                        }
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Repaired: $ok / ${repairIdx.size}"
                        }
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, t.message ?: "Operation error", Toast.LENGTH_LONG).show()
                }
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
        // สแกนโฟลเดอร์
        launchScan(
            title = "Scanning folder...",
            block = { engine.scanByFolderAllTypes(uri) }
        )
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

        progressGroup = findViewById(R.id.progressGroup)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        btnCancel = findViewById(R.id.btnCancel)

        spinnerFilter = findViewById(R.id.spinnerFilter)
        setupFilter()

        // ready line
        val versionName = runCatching {
            val pm = packageManager
            if (Build.VERSION.SDK_INT >= 33)
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            else
                @Suppress("DEPRECATION") pm.getPackageInfo(packageName, 0).versionName
        }.getOrElse { "1.0" } ?: "1.0"
        val sha = runCatching { getString(R.string.git_sha) }.getOrElse { "local" }
        val runNo = runCatching { getString(R.string.build_run) }.getOrElse { "-" }
        tvStatus.text = "Ready. build $versionName ($sha) #$runNo"

        findViewById<Button>(R.id.btnScanPhone).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            launchScan(
                title = "Scanning phone...",
                block = { engine.scanPhoneAll(includeTrash = true) }
            )
        }

        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            launchScan(
                title = "Scanning SD/OTG...",
                block = { engine.scanRemovableAll(includeTrash = true) }
            )
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { pickFolder.launch(null) }

        // select all toggle
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnSelectAll.setOnClickListener {
            if (allSelected) {
                for (i in 0 until adapter.count) listView.setItemChecked(i, false)
                btnSelectAll.text = "SELECT ALL"
                allSelected = false
            } else {
                for (i in 0 until adapter.count) listView.setItemChecked(i, true)
                btnSelectAll.text = "CLEAR ALL"
                allSelected = true
            }
        }

        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            val i = firstCheckedIndex()
                ?: return@setOnClickListener Toast.makeText(this, "Select an item", Toast.LENGTH_SHORT).show()
            val it = shownItems[i]
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it.uri, it.mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(view) } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app to open", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val idx = checkedIndices()
                ?: return@setOnClickListener Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
            pendingCopyIndices = idx
            pickDest.launch(null)
        }

        findViewById<Button>(R.id.btnRepair).setOnClickListener {
            val idx = checkedIndices()
                ?: return@setOnClickListener Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
            pendingRepairIndices = idx
            pickDest.launch(null)
        }

        btnCancel.setOnClickListener {
            cancelRequested = true
            scanJob?.cancel()
        }
    }

    // ----------------- Scan with animated percent & ETA -----------------
    private fun launchScan(title: String, block: suspend () -> List<RecoverEasyEngine.Item>) {
        // ยกเลิกงานเก่า
        cancelRequested = false
        scanJob?.cancel()
        animateJob?.cancel()

        // เตรียม UI
        progressGroup.visibility = LinearLayout.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        tvProgress.text = title

        // จำลองเปอร์เซ็นอย่างนุ่ม ๆ (ขึ้นช้า ๆ ถึง ~95% แล้วค่อย 100% ตอนจบ)
        val startMs = System.currentTimeMillis()
        animateJob = lifecycleScope.launch(Dispatchers.Main) {
            var p = 0f
            while (isActive) {
                // ความเร็วขึ้นต่อวินาที (ช้าลงเรื่อย ๆ)
                val elapsed = max(1f, (System.currentTimeMillis() - startMs) / 1000f)
                val target = min(95f, 40f * (1f - (1f / (1f + elapsed / 3f))) + 30f * (1f - (1f / (1f + elapsed / 10f))))
                p = min(target, p + 0.7f)
                progressBar.progress = p.roundToInt()

                // ETA แบบประมาณจากความเร็วเฉลี่ย (เฉพาะเมื่อ p>3)
                val eta = if (p > 3f) {
                    val speed = p / elapsed // % ต่อวินาที
                    val remain = (100f - p) / max(0.1f, speed)
                    remain.roundToInt()
                } else -1
                tvProgress.text = if (eta >= 0) "$title  •  ${p.roundToInt()}%  •  ETA ~${formatSec(eta)}"
                                   else "$title  •  ${p.roundToInt()}%"

                delay(400)
            }
        }

        // งานสแกนจริง
        scanJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val list = block()
                withContext(Dispatchers.Main) {
                    setItems(list)
                    progressBar.progress = 100
                    tvProgress.text = "$title  •  100%  •  done"
                    progressGroup.visibility = LinearLayout.GONE
                }
            } catch (ex: CancellationException) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Canceled."
                    progressGroup.visibility = LinearLayout.GONE
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, t.message ?: "Scan error", Toast.LENGTH_LONG).show()
                    tvStatus.text = "Error: ${t.message ?: "Scan error"}"
                    progressGroup.visibility = LinearLayout.GONE
                }
            } finally {
                animateJob?.cancel()
            }
        }
    }

    private fun formatSec(s: Int): String {
        val m = s / 60
        val ss = s % 60
        return "%d:%02d".format(m, ss)
    }
    // -------------------------------------------------------------------

    private fun setupFilter() {
        val choices = arrayOf(
            "All",
            "Images",
            "Videos",
            "Audio",
            "Documents",
            "Archives",
            "Trashed only",
            "Not trashed",
            "Damaged only",
            "Readable only"
        )
        spinnerFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.asList())
        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                applyFilter(choices[pos])
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun applyFilter(mode: String) {
        shownItems.clear()
        shownItems += when (mode) {
            "Images"       -> allItems.filter { it.kind == RecoverEasyEngine.Kind.IMAGE }
            "Videos"       -> allItems.filter { it.kind == RecoverEasyEngine.Kind.VIDEO }
            "Audio"        -> allItems.filter { it.kind == RecoverEasyEngine.Kind.AUDIO }
            "Documents"    -> allItems.filter { it.kind == RecoverEasyEngine.Kind.DOC }
            "Archives"     -> allItems.filter { it.kind == RecoverEasyEngine.Kind.ARCHIVE }
            "Trashed only" -> allItems.filter { it.isTrashed }
            "Not trashed"  -> allItems.filter { !it.isTrashed }
            "Damaged only" -> allItems.filter { it.damaged == true }
            "Readable only"-> allItems.filter { it.damaged != true }
            else           -> allItems
        }
        val names = shownItems.map { (if (it.isTrashed) "[TRASH] " else "") + it.name }
        adapter.clear(); adapter.addAll(names)
        allSelected = false
        btnSelectAll.text = "SELECT ALL"
        tvStatus.text = "Showing ${shownItems.size} / ${allItems.size}"
    }

    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        allItems.clear(); allItems.addAll(list)
        applyFilter(spinnerFilter.selectedItem?.toString() ?: "All")
    }

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

    private fun firstCheckedIndex(): Int? {
        val sparse = listView.checkedItemPositions
        for (i in 0 until sparse.size()) {
            val key = sparse.keyAt(i)
            if (sparse.valueAt(i)) return key
        }
        return null
    }

    private fun checkedIndices(): IntArray? {
        val sparse = listView.checkedItemPositions
        val out = mutableListOf<Int>()
        for (i in 0 until sparse.size()) {
            val key = sparse.keyAt(i)
            if (sparse.valueAt(i)) out += key
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }
}
