package com.recovereasy.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ItemAdapter
    private lateinit var engine: RecoverEasyEngine
    private val items = mutableListOf<RecoverEasyEngine.Item>()

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user can tap again if denied */ }

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

        adapter = ItemAdapter(items, listView)
        listView.adapter = adapter

        engine = RecoverEasyEngine(this)

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
            val allChecked = (0 until adapter.count).all { listView.isItemChecked(it) }
            for (i in 0 until adapter.count) listView.setItemChecked(i, !allChecked)
            adapter.notifyDataSetChanged()
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
            val idx = checkedIndices() ?: return@setOnClickListener
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
        val s = listView.checkedItemPositions
        for (i in 0 until s.size()) {
            val key = s.keyAt(i)
            if (s.valueAt(i)) return key
        }
        return null
    }

    private fun checkedIndices(): IntArray? {
        val s = listView.checkedItemPositions
        val out = mutableListOf<Int>()
        for (i in 0 until s.size()) {
            val key = s.keyAt(i)
            if (s.valueAt(i)) out += key
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }

    // ---------- Adapter แสดง thumbnail อย่างประหยัด ----------
    private class ItemAdapter(
        private val data: List<RecoverEasyEngine.Item>,
        private val listView: ListView
    ) : BaseAdapter() {

        override fun getCount() = data.size
        override fun getItem(position: Int) = data[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: VH
            val view = if (convertView == null) {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_item_thumb, parent, false)
                holder = VH(
                    v.findViewById(R.id.thumb),
                    v.findViewById(R.id.title),
                    v.findViewById(R.id.chk)
                )
                v.tag = holder
                v
            } else {
                holder = convertView.tag as VH
                convertView
            }

            val item = getItem(position)
            holder.title.text =
                (if (item.isTrashed) "[TRASH] " else "") + item.name

            // โหลดรูปแบบพื้นหลัง + บีบอัดให้เล็ก (ประมาณ 96px)
            holder.thumb.load(item.uri) {
                size(96)
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_report_image)
                error(android.R.drawable.ic_menu_report_image)
                allowHardware(false) // กันบางเครื่องที่มีปัญหา hardware bitmap
            }

            // sync checkbox กับสถานะของ ListView
            holder.chk.setOnCheckedChangeListener(null)
            holder.chk.isChecked = listView.isItemChecked(position)
            holder.chk.setOnCheckedChangeListener { _, isChecked ->
                listView.setItemChecked(position, isChecked)
            }

            return view
        }

        private data class VH(
            val thumb: ImageView,
            val title: TextView,
            val chk: CheckBox
        )
    }
}
