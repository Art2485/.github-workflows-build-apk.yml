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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView

    private lateinit var engine: RecoverEasyEngine
    private val items = mutableListOf<RecoverEasyEngine.Item>()

    // เซ็ตตำแหน่งที่ถูกเลือก (สำหรับปุ่ม Select All toggle)
    private val selected = mutableSetOf<Int>()

    // ===== Permissions (Android 13+) =====
    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ถ้ายังไม่ได้สิทธิ์ ผู้ใช้กดซ้ำปุ่มสแกนได้ */ }

    // ===== Pick destination for copy =====
    private var pendingCopyIndices: IntArray? = null
    private val pickDest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val idx = pendingCopyIndices
        pendingCopyIndices = null
        if (uri == null || idx == null) return@registerForActivityResult

        // keep permission
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        lifecycleScope.launch {
            try {
                var ok = 0
                for (i in idx) {
                    if (i !in items.indices) continue
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

        engine = RecoverEasyEngine(this)

        // ใช้ Adapter แสดงรูปตัวอย่างเล็ก ๆ
        val adapter = ThumbAdapter()
        listView.adapter = adapter

        // -------- Buttons --------
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

        // Toggle เลือกทั้งหมด / ยกเลิกทั้งหมด
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            if (selected.size == items.size && items.isNotEmpty()) {
                selected.clear()
            } else {
                selected.clear()
                selected.addAll(items.indices)
            }
            (listView.adapter as BaseAdapter).notifyDataSetChanged()
            tvStatus.text = "Selected ${selected.size} / ${items.size}"
        }

        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            val i = selected.minOrNull()
            if (i == null) {
                Toast.makeText(this, "Select an item", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select items", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingCopyIndices = selected.sorted().toIntArray()
            pickDest.launch(null)
        }
    }

    // ===== Folder picker for Scan by folder =====
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

    // ===== Helpers =====
    private fun setItems(list: List<RecoverEasyEngine.Item>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        tvStatus.text = "Found: ${items.size} items"
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

    /** ป้ายชื่อแถว (ไม่ใช้ damaged อีก) */
    private fun labelOf(it: RecoverEasyEngine.Item): String {
        val tags = mutableListOf<String>()
        if (it.isTrashed) tags += "TRASH"
        val prefix = if (tags.isEmpty()) "" else "[${tags.joinToString("|")}] "
        return prefix + it.name
    }

    /** Adapter แสดงรูปย่อ + เช็กบ็อกซ์ในแต่ละแถว */
    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.row_item_thumb, parent, false)

            val img = row.findViewById<ImageView>(R.id.thumb)
            val title = row.findViewById<TextView>(R.id.title)
            val chk = row.findViewById<CheckBox>(R.id.chk)

            val item = getItem(position)

            // ชื่อไฟล์ + ป้าย TRASH (ถ้ามี)
            title.text = labelOf(item)

            // รูปย่อแบบง่าย ๆ (พอให้เห็นหน้าไฟล์)
            // ถ้าเป็นไฟล์ไม่รองรับรูป ก็ไม่เป็นไร จะว่าง ๆ
            try {
                img.setImageURI(item.uri)
            } catch (_: Throwable) {
                img.setImageDrawable(null)
            }

            // สถานะเลือก
            chk.isChecked = selected.contains(position)

            // คลิกทั้งแถวหรือเช็กบ็อกซ์ = toggle
            val toggle: (View) -> Unit = {
                if (selected.contains(position)) selected.remove(position)
                else selected.add(position)
                notifyDataSetChanged()
                tvStatus.text = "Selected ${selected.size} / ${items.size}"
            }
            row.setOnClickListener(toggle)
            chk.setOnClickListener(toggle)

            return row
        }
    }
}
