package com.recovereasy.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private lateinit var adapter: ItemAdapter
    private lateinit var engine: RecoverEasyEngine

    private val items = mutableListOf<RecoverEasyEngine.Item>()
    private var allSelected = false

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
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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

        engine = RecoverEasyEngine(this)
        adapter = ItemAdapter(this, items)
        listView.adapter = adapter

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

        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { v ->
            allSelected = !allSelected
            for (i in 0 until adapter.count) listView.setItemChecked(i, allSelected)
            (v as Button).text = if (allSelected) "CLEAR ALL" else "SELECT ALL"
        }

        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            val i = firstCheckedIndex() ?: return@setOnClickListener
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
            val idx = checkedIndices() ?: return@setOnClickListener Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
            pendingCopyIndices = idx
            pickDest.launch(null)
        }
    }

    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        items.clear(); items.addAll(list)
        adapter.notifyDataSetChanged()
        tvStatus.text = "Found: ${list.size} items"
        allSelected = false
        findViewById<Button>(R.id.btnSelectAll).text = "SELECT ALL"
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

    // -----------------------------
    // Adapter + thumbnail cache
    // -----------------------------
    private class ItemAdapter(
        val ctx: Context,
        val data: MutableList<RecoverEasyEngine.Item>
    ) : BaseAdapter() {

        // cache รูปเล็ก
        private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(ctx)
                .inflate(R.layout.row_item_thumb, parent, false)

            val iv = view.findViewById<ImageView>(R.id.thumb)
            val tv = view.findViewById<CheckedTextView>(android.R.id.text1)

            val it = data[position]
            val label = buildString {
                if (it.isTrashed) append("[TRASH] ")
                if (it.damaged == true) append("[DAMAGED] ")
                append(it.name)
            }
            tv.text = label

            // ตั้ง placeholder ก่อน
            iv.setImageResource(android.R.drawable.ic_menu_report_image)

            val key = it.uri.toString()
            iv.tag = key

            // ถ้ามีใน cache แล้ว
            cache.get(key)?.let {
                if (iv.tag == key) iv.setImageBitmap(it)
                return view
            }

            // โหลด thumbnail แบบเบาเครื่อง (Android 10+ มี loadThumbnail)
            (ctx as? AppCompatActivity)?.lifecycleScope?.launch {
                val bmp = loadThumbSafe(ctx, it.uri, it.mime)
                if (bmp != null) {
                    cache.put(key, bmp)
                    if (iv.tag == key) iv.setImageBitmap(bmp)
                }
            }

            return view
        }

        private suspend fun loadThumbSafe(ctx: Context, uri: Uri, mime: String?): Bitmap? =
            withContext(Dispatchers.IO) {
                try {
                    // ถ้า provider รองรับ ใช้ API นี้คมและเร็ว
                    ctx.contentResolver.loadThumbnail(uri, Size(192, 192), null)
                } catch (_: Throwable) {
                    try {
                        // เผื่อบางแอป/ไฟล์ไม่รองรับ thumbnail ― ค่อย decode แบบย่อ
                        android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
                        ) { decoder, _, _ ->
                            decoder.isMutableRequired = false
                            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetSize(192, 192)
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
    }
}
