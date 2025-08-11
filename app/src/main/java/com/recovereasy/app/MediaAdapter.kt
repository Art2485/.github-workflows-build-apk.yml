package com.recovereasy.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class MediaAdapter(
    private val cr: ContentResolver
) : ListAdapter<RecoverEasyEngine.Item, MediaAdapter.VH>(DIFF) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val selected = linkedSetOf<String>() // เก็บ URI เป็น String

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecoverEasyEngine.Item>() {
            override fun areItemsTheSame(
                oldItem: RecoverEasyEngine.Item, newItem: RecoverEasyEngine.Item
            ) = oldItem.uri == newItem.uri
            override fun areContentsTheSame(
                oldItem: RecoverEasyEngine.Item, newItem: RecoverEasyEngine.Item
            ) = oldItem == newItem
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgThumb)
        val name: TextView = v.findViewById(R.id.tvName)
        val cb: CheckBox = v.findViewById(R.id.cb)
        var job: Job? = null
        fun bind(item: RecoverEasyEngine.Item) {
            val uriStr = item.uri.toString()
            name.text = (if (item.isTrashed) "[TRASH] " else "") + item.name
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = selected.contains(uriStr)
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected.add(uriStr) else selected.remove(uriStr)
            }
            itemView.setOnClickListener { cb.isChecked = !cb.isChecked }

            img.setImageResource(android.R.drawable.ic_menu_report_image)
            img.tag = uriStr
            job?.cancel()
            job = scope.launch {
                val bmp = withContext(Dispatchers.IO) { loadThumbSafe(item.uri, item.mime) }
                if (img.tag == uriStr && bmp != null) img.setImageBitmap(bmp)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.row_item_thumb, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    fun clearSelection() { selected.clear(); notifyDataSetChanged() }

    fun setAllSelected(select: Boolean) {
        if (select) currentList.forEach { selected.add(it.uri.toString()) }
        else selected.clear()
        notifyDataSetChanged()
    }

    fun isAllSelected(): Boolean =
        currentList.isNotEmpty() && currentList.all { selected.contains(it.uri.toString()) }

    fun selectedItems(): List<RecoverEasyEngine.Item> =
        currentList.filter { selected.contains(it.uri.toString()) }

    private fun loadThumbSafe(uri: Uri, mime: String?): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                cr.loadThumbnail(uri, Size(128, 128), null)
            } else {
                cr.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opts)
                    val sample = calcSample(opts.outWidth, opts.outHeight, 128, 128)
                    cr.openInputStream(uri)?.use { input2 ->
                        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeStream(input2, null, opts2)
                    }
                }
            }
        } catch (_: Throwable) { null }
    }

    private fun calcSample(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var inSample = 1
        var halfW = w / 2
        var halfH = h / 2
        while (halfW / inSample >= reqW && halfH / inSample >= reqH) {
            inSample *= 2
        }
        return inSample
    }
}
