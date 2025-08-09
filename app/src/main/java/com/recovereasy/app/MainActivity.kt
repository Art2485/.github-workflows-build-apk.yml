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
import kotlinx.coroutines.launch
import com.recovereasy.app.BuildConfig // <- สำคัญ: ให้ BuildConfig ใช้งานได้

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var engine: RecoverEasyEngine
    private val items = mutableListOf<RecoverEasyEngine.Item>()

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ผู้ใช้ปฏิเสธสิทธิ์ได้ ให้กดซ้ำเพื่อขอใหม่ */ }

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

        // --- บรรทัดยืนยันเวอร์ชัน/sha/เลขรอบ build ---
        val sha = runCatching { BuildConfig.GIT_SHA }.getOrElse { "local" }
        val runNo = runCatching { BuildConfig.BUILD_RUN }.getOrElse { "-" }
        tvStatus.text = "Ready. build ${BuildConfig.VERSION_NAME} ($sha) #$runNo"
        // ------------------------------------------------

        findViewById<Button>(R.id.btnScanPhone).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                tvStatus.text = "Scanning phone..."
                val found = engine.scanPhoneAll(includeTrash = true)
                setItems(found)
            }
        }

        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                tvStatus.text = "Scanning SD/OTG..."
                val found = engine.scanRemovableAll(includeTrash = true)
                setItems(found)
            }
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            pickFolder.launch(null)
        }

        findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            // เลือกทั้งหมด
            for (i in 0 until adapter.count) listView.setItemChecked(i, true)
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
    }

    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        items.clear(); items.addAll(list)
        val names = list.map { (if (it.isTrashed) "[TRASH] " else "") + it.name }
        adapter.clear(); adapter.addAll(names)
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
