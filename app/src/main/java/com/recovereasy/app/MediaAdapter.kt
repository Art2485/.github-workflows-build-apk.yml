package com.recovereasy.app

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView

/**
 * อะแดปเตอร์แสดงรายการไฟล์พร้อมรูปย่อ (thumbnail) + เช็กบ็อกซ์
 * ใช้กับ layout: row_item_thumb.xml (imgThumb, txtName, chk)
 */
class MediaAdapter(
    context: Context,
    private val items: List<RecoverEasyEngine.Item>,
    private val checked: BooleanArray
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private val resolver = context.contentResolver

    private data class VH(
        val imgThumb: ImageView,
        val txtName: TextView,
        val chk: CheckBox
    )

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View
        val holder: VH

        if (convertView == null) {
            view = inflater.inflate(R.layout.row_item_thumb, parent, false)
            holder = VH(
                imgThumb = view.findViewById(R.id.imgThumb),
                txtName  = view.findViewById(R.id.txtName),
                chk      = view.findViewById(R.id.chk)
            )
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as VH
        }

        val it = items[position]

        // ชื่อไฟล์ (ใส่ [TRASH] ถ้าอยู่ถังขยะ)
        holder.txtName.text = buildString {
            if (it.isTrashed) append("[TRASH] ")
            append(it.name)
        }

        // เช็กบ็อกซ์ตามสถานะในอาร์เรย์
        holder.chk.setOnCheckedChangeListener(null)
        holder.chk.isChecked = checked.getOrNull(position) == true
        holder.chk.setOnCheckedChangeListener { _, isChecked ->
            if (position in checked.indices) checked[position] = isChecked
        }

        // โหลด thumbnail เฉพาะไฟล์รูปภาพ ลดขนาดเพื่อไม่ให้หน่วง/กินแรม
        loadThumbnailIfImage(it.uri, holder.imgThumb)

        return view
    }

    private fun loadThumbnailIfImage(uri: Uri, target: ImageView) {
        // ถ้าไม่ใช่รูปภาพ ก็ไม่บังคับต้องโชว์รูปย่อ (คงภาพเดิมไว้)
        val type = resolver.getType(uri) ?: return
        if (!type.startsWith("image/")) return

        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(resolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize(160, 160) // เบา ๆ เร็ว
                }
                target.setImageBitmap(bmp)
            } else {
                // Android < 28 ข้าม (อุปกรณ์คุณคือ Android 15 จึงเข้าทางบนอยู่แล้ว)
            }
        } catch (_: Throwable) {
            // ถ้าอ่านรูปไม่ได้ ไม่ต้อง crash ปล่อยรูปเดิมไว้
        }
    }
}
