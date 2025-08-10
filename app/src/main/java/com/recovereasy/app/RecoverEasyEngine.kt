package com.recovereasy.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.Locale

class RecoverEasyEngine(private val context: Context) {

    data class Item(
        val uri: Uri,
        val name: String,
        val mime: String?,
        val size: Long,
        val isTrashed: Boolean
    )

    private val ext = arrayOf(
        "image/", "video/", "audio/", "application/", "text/"
    )

    /** สแกนสตอเรจเครื่อง (รวม Trash) */
    suspend fun scanPhoneAll(includeTrash: Boolean = true): List<Item> {
        val vols = if (Build.VERSION.SDK_INT >= 30) {
            listOf(
                MediaStore.VOLUME_EXTERNAL_PRIMARY,
                MediaStore.VOLUME_EXTERNAL
            )
        } else listOf("external")

        val out = mutableListOf<Item>()
        for (v in vols) {
            out += queryVolume(v, includeTrash)
        }
        // sort ใหม่ล่าสุดก่อน
        return out.distinctBy { it.uri }.sortedByDescending { it.uri.lastPathSegment }
    }

    /** สแกนสื่อใน SD/OTG แบบรวม ๆ (Android มองเป็น external เหมือนกัน) */
    suspend fun scanRemovableAll(includeTrash: Boolean = true): List<Item> {
        // ใช้วิธีเดียวกับ phone เพราะ MediaStore map รวมอยู่แล้ว
        return scanPhoneAll(includeTrash)
    }

    /** scan SAF folder (ไม่รวม Trash เพราะ SAF มองไม่เห็นโฟลเดอร์ Trash) */
    suspend fun scanByFolderAllTypes(treeUri: Uri): List<Item> {
        // ตรงนี้เวอร์ชัน SAF คุณมีใช้งานอยู่แล้ว — ปล่อยใช้ของเดิมก็ได้
        // เพื่อความสั้น ผมให้คืนลิสต์ว่าง (หากคุณมีเวอร์ชันก่อนหน้าที่ทำงานแล้ว ให้คงของเดิม)
        return emptyList()
    }

    private fun queryVolume(volume: String, includeTrash: Boolean): List<Item> {
        val cr = context.contentResolver
        val base = MediaStore.Files.getContentUri(volume)

        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            // Android 11+ มี IS_TRASHED
            if (Build.VERSION.SDK_INT >= 30)
                MediaStore.Files.FileColumns.IS_TRASHED else "0 AS is_trashed"
        )

        // เราเอา "ทุกอย่างที่เป็นไฟล์สื่อ" แล้วค่อยกรองภายหลัง
        val sel = if (Build.VERSION.SDK_INT >= 30) {
            if (includeTrash) null else "(is_trashed=0 OR is_trashed IS NULL)"
        } else null
        val args: Array<String>? = null
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val out = mutableListOf<Item>()
        cr.query(base, proj, sel, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val trashCol = c.getColumnIndex("is_trashed")

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val mime = c.getString(mimeCol)
                val size = c.getLong(sizeCol)
                val isTrashed = if (trashCol >= 0) c.getInt(trashCol) == 1 else false

                // กรองชนิดที่เป็นสื่อ/เอกสารทั่วไป (กันไฟล์ระบบ)
                if (!acceptMime(mime, name)) continue

                val uri = ContentUris.withAppendedId(base, id)
                out += Item(uri, name, mime, size, isTrashed)
            }
        }
        return out
    }

    private fun acceptMime(mime: String?, name: String): Boolean {
        val m = mime?.lowercase(Locale.US) ?: ""
        if (ext.any { m.startsWith(it) }) return true
        // เผื่อบางไฟล์ไม่มี mime ให้เดาด้วยนามสกุล
        val n = name.lowercase(Locale.US)
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".heic") || n.endsWith(".mp4") || n.endsWith(".mp3")
                || n.endsWith(".pdf") || n.endsWith(".zip")
    }

    /** คัดลอกไฟล์พร้อม checksum (ของเดิมคุณมีแล้ว — คงไว้) */
    suspend fun safeCopyWithDigest(src: Uri, toTree: Uri, fileName: String): Uri? {
        // …ใช้เวอร์ชันเดิมของคุณได้เลย…
        return null
    }
}
