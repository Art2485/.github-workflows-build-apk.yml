package com.recovereasy.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ItemAdapter
    private lateinit var engine: RecoverEasyEngine
    private val items = mutableListOf<RecoverEasyEngine.Item>()

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ผู้ใช้ไม่ให้สิทธิ์ก็ลองกดใหม่ */ }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listResults)
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        engine = RecoverEasyEngine(this)
        adapter = ItemAdapter(this, contentResolver, items)
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

        findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            // toggle select all
            val total = adapter.count
            if (total == 0) return@setOnClickListener
            val anyUnchecked = (0 until total).any { !listView.isItemChecked(it) }
            for (i in 0 until total) listView.setItemChecked(i, anyUnchecked)
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

    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        items.clear(); items.addAll(list)
        adapter.notifyDataSetChanged()
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

/* ================== Adapter + Thumb Cache ================== */

private class ItemAdapter(
    private val activity: AppCompatActivity,
    private val resolver: ContentResolver,
    private val data: List<RecoverEasyEngine.Item>
) : BaseAdapter() {

    // cache bitmap ตามคีย์ uri+ขนาด ป้องกันโหลดซ้ำ
    private val cache = object : LruCache<String, Bitmap>(32) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            // ประมาณคร่าว ๆ: KB
            return value.byteCount / 1024
        }
    }

    override fun getCount() = data.size
    override fun getItem(position: Int) = data[position]
    override fun getItemId(position: Int) = position.toLong()

    data class Holder(
        val thumb: ImageView,
        val title: CheckedTextView,
        var job: Job? = null
    )

    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
        val row = convertView ?: android.view.LayoutInflater.from(parent?.context)
            .inflate(R.layout.row_item_thumb, parent, false)

        val holder = (row.tag as? Holder) ?: Holder(
            thumb = row.findViewById(R.id.imgThumb),
            title = row.findViewById(android.R.id.text1)
        ).also { row.tag = it }

        val item = getItem(position)
        holder.title.text = item.name

        // ยกเลิกงานเก่าถ้า view ถูกนำกลับมาใช้ซ้ำ
        holder.job?.cancel()
        holder.thumb.setImageDrawable(null)

        val key = "${item.uri}#96"
        val cached = cache.get(key)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
        } else {
            holder.job = activity.lifecycleScope.launch {
                val bmp = loadThumb(resolver, item.uri, 96)
                if (bmp != null) {
                    cache.put(key, bmp)
                    holder.thumb.setImageBitmap(bmp)
                } else {
                    holder.thumb.setImageDrawable(null)
                }
            }
        }
        return row
    }

    private suspend fun loadThumb(resolver: ContentResolver, uri: Uri, size: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.loadThumbnail(uri, Size(size, size), null)
                } else {
                    // 26–28: decode แบบย่ออย่างปลอดภัย
                    resolver.openInputStream(uri)?.use { ins ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(ins, null, opts)
                        val req = size
                        var sample = 1
                        while ((opts.outWidth / sample) > req || (opts.outHeight / sample) > req) {
                            sample *= 2
                        }
                        resolver.openInputStream(uri)?.use { ins2 ->
                            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                            BitmapFactory.decodeStream(ins2, null, o2)
                        }
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }
}
