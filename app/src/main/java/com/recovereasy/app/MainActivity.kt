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

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var engine: RecoverEasyEngine
    private val items = mutableListOf<RecoverEasyEngine.Item>()

    // Progress UI
    private lateinit var progressGroup: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnCancel: Button

    // Select-all toggle
    private lateinit var btnSelectAll: Button
    private var allSelected = false

    // Scan jobs
    private var scanJob: Job? = null
    private var tickerJob: Job? = null
    @Volatile private var cancelRequested = false

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

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

        lifecycleScope.launch {
            try {
                var ok = 0
                for (i in idx) {
                    val it = items[i]
                    val out = engine.safeCopyWithDigest(it.uri, uri, it.name)
                    if (out != null) ok++
                }
                tvStatus.text = "Copied: $ok / ${idx.size}"
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, t.message ?: "Copy error", Toast.LENGTH_LONG).show()
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
        launchScan(
            title = "Scanning folder...",
            task = { engine.scanByFolderAllTypes(uri) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        progressGroup = findViewById(R.id.progressGroup)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        btnCancel = findViewById(R.id.btnCancel)

        listView = findViewById(R.id.listResults)
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
        listView.adapter = adapter
        engine = RecoverEasyEngine(this)

        // Ready line
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
                task = { engine.scanPhoneAll(includeTrash = true) }
            )
        }

        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            launchScan(
                title = "Scanning SD/OTG...",
                task = { engine.scanRemovableAll(includeTrash = true) }
            )
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            pickFolder.launch(null)
        }

        // Select All toggle
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
            val it = items[i]
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

        // Cancel button
        btnCancel.setOnClickListener { cancelRequested = true; scanJob?.cancel() }
    }

    // -------------------------
    // Scan helper with progress
    // -------------------------
    private fun launchScan(
        title: String,
        task: suspend () -> List<RecoverEasyEngine.Item>
    ) {
        // ยกเลิกงานเก่าถ้ามี
        cancelRequested = false
        scanJob?.cancel()
        tickerJob?.cancel()

        // UI เริ่มแสดง progress
        showProgress(true, title)
        val start = System.currentTimeMillis()

        // ตัวนับเวลา/ข้อความ โดยไม่มีเปอร์เซ็นต์ที่แน่นอน (indeterminate)
        tickerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
                tvProgress.text = "$title  •  elapsed ${formatSec(elapsed)}"
                delay(500)
            }
        }

        // งานสแกนจริง
        scanJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val list = task() // ถ้า engine รองรับ cancel, ตรงนี้จะโดน cancel ได้
                withContext(Dispatchers.Main) {
                    setItems(list)
                    showProgress(false)
                }
            } catch (ex: CancellationException) {
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    tvStatus.text = "Canceled."
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    Toast.makeText(this@MainActivity, t.message ?: "Scan error", Toast.LENGTH_LONG).show()
                    tvStatus.text = "Error: ${t.message ?: "Scan error"}"
                }
            } finally {
                tickerJob?.cancel()
            }
        }
    }

    private fun showProgress(show: Boolean, title: String? = null) {
        progressGroup.visibility = if (show) LinearLayout.VISIBLE else LinearLayout.GONE
        progressBar.isIndeterminate = true
        if (show) tvProgress.text = title ?: "Working..."
    }

    private fun formatSec(s: Int): String {
        val m = s / 60
        val ss = s % 60
        return "%d:%02d".format(m, ss)
    }

    // -------------------------
    // Utility / selection logic
    // -------------------------
    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        items.clear(); items.addAll(list)
        val names = list.map { (if (it.isTrashed) "[TRASH] " else "") + it.name }
        adapter.clear(); adapter.addAll(names)
        allSelected = false
        btnSelectAll.text = "SELECT ALL"
        tvStatus.text = "Found: ${list.size} items"
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
