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
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var spinner: Spinner

    private lateinit var engine: RecoverEasyEngine
    private val allItems = mutableListOf<RecoverEasyEngine.Item>()  // แหล่งจริง
    private val viewItems = mutableListOf<RecoverEasyEngine.Item>() // หลังกรอง

    // adapter แสดงชื่อ + thumbnail
    private lateinit var adapter: ArrayAdapter<RecoverEasyEngine.Item>

    private var selectAllState = false

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ถ้าไม่ได้สิทธิ์ ผู้ใช้กดซ้ำอีกครั้งได้ */ }

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
                    val it = viewItems[i]
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
            val list = withContext(Dispatchers.IO) { engine.scanByFolderAllTypes(uri) }
            setItems(list)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = RecoverEasyEngine(this)

        tvStatus = findViewById(R.id.tvStatus)
        spinner = findViewById(R.id.spinnerFilter)
        listView = findViewById(R.id.listResults)
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        // ทำ adapter ที่ใช้ layout แถวมีรูปย่อ (row_item_thumb.xml ต้องมี ImageView @id/imgThumb และ TextView @id/txtName)
        adapter = object : ArrayAdapter<RecoverEasyEngine.Item>(this, R.layout.row_item_thumb, viewItems) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = convertView ?: layoutInflater.inflate(R.layout.row_item_thumb, parent, false)
                val img = v.findViewById<ImageView>(R.id.imgThumb)
                val txt = v.findViewById<TextView>(R.id.txtName)
                val it = viewItems[position]

                txt.text = (if (it.isTrashed) "[TRASH] " else "") + it.name
                img.load(it.uri) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_report_image)
                    error(android.R.drawable.ic_menu_report_image)
                    size(128, 128)
                }
                return v
            }
        }
        listView.adapter = adapter

        // Filter choices
        val choices = arrayOf("All", "Images", "Videos", "Audio", "Documents", "Archives", "Trashed only", "Not trashed")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
                applyFilter(choices[i])
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnScanPhone).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                tvStatus.text = "Scanning phone..."
                val found = withContext(Dispatchers.IO) { engine.scanPhoneAll(includeTrash = true) }
                setItems(found)
            }
        }

        findViewById<Button>(R.id.btnScanRemovable).setOnClickListener {
            if (!ensureMediaPermission()) return@setOnClickListener
            lifecycleScope.launch {
                tvStatus.text = "Scanning SD/OTG..."
                val found = withContext(Dispatchers.IO) { engine.scanRemovableAll(includeTrash = true) }
                setItems(found)
            }
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            pickFolder.launch(null)
        }

        findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            selectAllState = !selectAllState
            for (i in 0 until adapter.count) listView.setItemChecked(i, selectAllState)
        }

        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            val i = firstCheckedIndex() ?: return@setOnClickListener Toast.makeText(this, "Select an item", Toast.LENGTH_SHORT).show()
            val it = viewItems[i]
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it.uri, it.mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(view) } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app to open", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val idx = checkedIndices() ?: return@setOnClickListener Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
            pendingCopyIndices = idx
            pickDest.launch(null)
        }

        // แสดงเวอร์ชัน build ด้านล่าง (ถ้ามี BuildConfig ฟิลด์)
        val buildInfo = "build ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) #${BuildConfig.BUILD_RUN}"
        tvStatus.text = "Ready. $buildInfo"
    }

    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        allItems.clear(); allItems.addAll(list)
        applyFilter(spinner.selectedItem?.toString() ?: "All")
    }

    private fun applyFilter(choice: String) {
        viewItems.clear()
        viewItems += when (choice) {
            "Images" -> allItems.filter { it.mime?.startsWith("image/") == true }
            "Videos" -> allItems.filter { it.mime?.startsWith("video/") == true }
            "Audio" -> allItems.filter { it.mime?.startsWith("audio/") == true }
            "Documents" -> allItems.filter { it.mime?.startsWith("application/") == true || it.mime?.startsWith("text/") == true }
            "Archives" -> allItems.filter { it.name.endsWith(".zip", true) || it.name.endsWith(".rar", true) }
            "Trashed only" -> allItems.filter { it.isTrashed }
            "Not trashed" -> allItems.filter { !it.isTrashed }
            else -> allItems
        }
        adapter.notifyDataSetChanged()
        tvStatus.text = "Showing ${viewItems.size} / ${allItems.size}"
        // รีเซ็ตการเลือก
        listView.clearChoices()
        selectAllState = false
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
