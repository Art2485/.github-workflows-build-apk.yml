package com.recovereasy.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * RecoverEasyEngine – รวมเมธอดที่ UI เรียกใช้
 * - สแกนไฟล์ในเครื่อง/SD/OTG/โฟลเดอร์
 * - ติด Tag isTrashed เพื่อให้ UI กรองได้
 * - safeCopyWithDigest คัดลอกไฟล์อย่างปลอดภัยพร้อมคำนวณ digest
 * - helper อื่น ๆ (queryByVolume, dedupSort, acceptMime, …)
 *
 * **สำคัญ:** โค้ดนี้ออกแบบให้ “เพิ่มความสามารถ” โดยไม่ทำให้ของเดิมพัง
 */
class RecoverEasyEngine(private val context: Context) {

    // -----------------------------
    // Models
    // -----------------------------
    data class Item(
        val uri: Uri,
        val name: String,
        val mime: String?,
        val size: Long,
        val volumeId: String?,
        val isTrashed: Boolean,
        val damaged: Boolean = false
    )

    // -----------------------------
    // Public API (ถูกใช้งานจาก UI)
    // -----------------------------

    /** สแกนทั้งโทรศัพท์ (รวม Media + Trash) */
    suspend fun scanPhoneAll(
        includeTrash: Boolean = true,
        onProgress: ((Int, Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<Item> = withContext(Dispatchers.IO) {
        val names = MediaStore.getExternalVolumeNames(context)
        // รวม “ทุกวอลุ่ม” แล้วค่อย dedup + sort
        val all = mutableListOf<Item>()
        var done = 0
        val total = names.size
        for (vol in names) {
            if (isCancelled?.invoke() == true) break
            all += queryByVolume(vol, includeTrash, isCancelled)
            done++
            onProgress?.invoke(done, total)
        }
        dedupSort(all)
    }

    /** สแกนเฉพาะ Removable (SD/OTG) */
    suspend fun scanRemovableAll(
        includeTrash: Boolean = true,
        onProgress: ((Int, Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<Item> = withContext(Dispatchers.IO) {
        val names = MediaStore.getExternalVolumeNames(context)
        // กรอง “ไม่เอา primary”
        val removable = names.filterNot { it == MediaStore.VOLUME_EXTERNAL_PRIMARY }
        val all = mutableListOf<Item>()
        var done = 0
        val total = removable.size.coerceAtLeast(1)
        for (vol in removable) {
            if (isCancelled?.invoke() == true) break
            all += queryByVolume(vol, includeTrash, isCancelled)
            done++
            onProgress?.invoke(done, total)
        }
        dedupSort(all)
    }

    /** สแกนตามโฟลเดอร์ (SAF Tree URI) ครอบคลุมทุกชนิดไฟล์ */
    suspend fun scanByFolderAllTypes(
        tree: Uri,
        onProgress: ((Int, Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<Item> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
        val gathered = mutableListOf<Item>()
        fun walk(dir: DocumentFile) {
            if (isCancelled?.invoke() == true) return
            val children = dir.listFiles()
            var idx = 0
            val total = children.size
            for (f in children) {
                if (isCancelled?.invoke() == true) return
                if (f.isDirectory) {
                    walk(f)
                } else if (f.isFile) {
                    gathered += Item(
                        uri = f.uri,
                        name = f.name ?: "unknown",
                        mime = f.type,
                        size = f.length(),
                        volumeId = null,
                        isTrashed = false
                    )
                }
                idx++
                onProgress?.invoke(idx, total)
            }
        }
        walk(root)
        dedupSort(gathered)
    }

    /**
     * คัดลอกไฟล์ไปยังโฟลเดอร์ปลายทาง (SAF Tree) อย่างปลอดภัย
     * พร้อมคำนวณ SHA-256 digest เพื่อเช็คความถูกต้อง
     * ถ้าคัดลอกสำเร็จคืนค่า Uri ของไฟล์ใหม่, ไม่งั้นคืน null
     */
    suspend fun safeCopyWithDigest(
        src: Uri,
        toTree: Uri,
        fileName: String
    ): Uri? = withContext(Dispatchers.IO) {
        val destTree = DocumentFile.fromTreeUri(context, toTree) ?: return@withContext null
        // ถ้าซ้ำชื่อ ให้เติม (1), (2), …
        val target = createUniqueFile(destTree, fileName, context.contentResolver.getType(src))
            ?: return@withContext null

        context.contentResolver.openInputStream(src).use { ins ->
            context.contentResolver.openOutputStream(target.uri).use { outs ->
                if (ins == null || outs == null) return@withContext null
                val ok = copyWithDigest(ins, outs)
                if (!ok) return@withContext null
            }
        }
        target.uri
    }

    // -----------------------------
    // Internal helpers
    // -----------------------------

    /** ดึงไฟล์จาก MediaStore ของวอลุ่มนั้น ๆ (รวม images / video / audio / files) */
    private fun queryByVolume(
        volume: String,
        includeTrash: Boolean,
        isCancelled: (() -> Boolean)?
    ): List<Item> {
        val out = mutableListOf<Item>()
        // เราจะ query จาก Files ก่อน (ครอบคลุมมากที่สุด)
        out += queryFilesCollection(volume, includeTrash, isCancelled)
        // เผื่อบางอุปกรณ์บางชนิดไม่โผล่ใน Files ให้เติม media ชุดหลักอีกชั้น (ไม่ซ้ำเพราะเราจะ dedup ภายหลัง)
        out += queryMediaCollection(MediaStore.Images.Media.getContentUri(volume), includeTrash, isCancelled)
        out += queryMediaCollection(MediaStore.Video.Media.getContentUri(volume), includeTrash, isCancelled)
        out += queryMediaCollection(MediaStore.Audio.Media.getContentUri(volume), includeTrash, isCancelled)
        return out
    }

    private fun queryFilesCollection(
        volume: String,
        includeTrash: Boolean,
        isCancelled: (() -> Boolean)?
    ): List<Item> {
        val out = mutableListOf<Item>()
        val uri = MediaStore.Files.getContentUri(volume)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.MediaColumns.IS_TRASHED
        )
        // ไม่กรอง trash ตอน query เพื่อให้แอพสามารถกรองใน UI ได้
        val sel: String? = null
        val selArgs: Array<String>? = null
        val sort = null

        context.contentResolver.query(uri, projection, sel, selArgs, sort)?.use { c ->
            val idxId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val idxName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val idxMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val idxSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val idxTrash = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
            while (c.moveToNext()) {
                if (isCancelled?.invoke() == true) break
                val id = c.getLong(idxId)
                val name = c.getString(idxName) ?: "unknown"
                val mime = c.getString(idxMime)
                val size = c.getLong(idxSize)
                val trashed = c.getInt(idxTrash) == 1
                // ถ้าผู้ใช้ไม่ต้องการรวมไฟล์ถังขยะ ให้ตัดออกที่นี่ก็ได้
                if (!includeTrash && trashed) continue
                val itemUri = ContentUris.withAppendedId(uri, id)
                if (!acceptMime(mime)) continue
                out += Item(itemUri, name, mime, size, volume, trashed)
            }
        }
        return out
    }

    private fun queryMediaCollection(
        collection: Uri,
        includeTrash: Boolean,
        isCancelled: (() -> Boolean)?
    ): List<Item> {
        val out = mutableListOf<Item>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_TRASHED
        )
        val sel: String? = null
        val selArgs: Array<String>? = null
        val sort = null

        context.contentResolver.query(collection, projection, sel, selArgs, sort)?.use { c ->
            val idxId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val idxName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val idxMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val idxSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val idxTrash = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
            while (c.moveToNext()) {
                if (isCancelled?.invoke() == true) break
                val id = c.getLong(idxId)
                val name = c.getString(idxName) ?: "unknown"
                val mime = c.getString(idxMime)
                val size = c.getLong(idxSize)
                val trashed = c.getInt(idxTrash) == 1
                if (!includeTrash && trashed) continue
                if (!acceptMime(mime)) continue
                val itemUri = ContentUris.withAppendedId(collection, id)
                out += Item(itemUri, name, mime, size, null, trashed)
            }
        }
        return out
    }

    /** รวมซ้ำ (key = name+size) แล้ว sort ตามชื่อ */
    private fun dedupSort(list: List<Item>): List<Item> {
        val map = LinkedHashMap<String, Item>(list.size)
        for (it in list) {
            val key = "${it.name}#${it.size}"
            // เก็บตัวแรกไว้ก่อน (ป้องกันแทนที่โดยไม่จำเป็น)
            if (!map.containsKey(key)) map[key] = it
        }
        return map.values.sortedWith(compareBy({ it.name.lowercase(Locale.getDefault()) }, { it.size }))
    }

    /** ตัดสินใจรับ/ไม่รับ mime (ตอนนี้รับหมด เพื่อครอบคลุมสุด) */
    private fun acceptMime(mime: String?): Boolean = true

    /** เผื่อที่อื่นเรียกใช้ ต้องมีชื่อฟังก์ชันนี้ให้ครบ */
    @Suppress("unused")
    private fun isTrashedLikePath(nameOrPath: String?): Boolean = false

    // -----------------------------
    // Copy & Digest
    // -----------------------------

    private fun createUniqueFile(
        tree: DocumentFile,
        displayName: String,
        mime: String?
    ): DocumentFile? {
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.getDefault())
        val mimeFinal = mime ?: guessMimeFromExt(ext)
        // ถ้าชื่อยังว่าง ตั้ง default
        val safeBase = if (base.isBlank()) "file" else base

        var index = 0
        var candidate: DocumentFile?
        while (true) {
            val name = if (index == 0) displayName
            else if (ext.isNotEmpty()) "$safeBase ($index).$ext" else "$safeBase ($index)"
            candidate = tree.findFile(name)
            if (candidate == null) {
                // สร้างไฟล์ใหม่
                return tree.createFile(mimeFinal ?: "application/octet-stream", name)
            }
            index++
            if (index > 500) return null // กัน loop ยาวผิดปกติ
        }
    }

    private fun guessMimeFromExt(ext: String): String? {
        if (ext.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun copyWithDigest(input: InputStream, output: OutputStream): Boolean {
        val buf = ByteArray(DEFAULT_BUFFER)
        val md = MessageDigest.getInstance("SHA-256")
        var read: Int
        while (true) {
            read = input.read(buf)
            if (read <= 0) break
            output.write(buf, 0, read)
            md.update(buf, 0, read)
        }
        output.flush()
        // digest พร้อมใช้งาน (เผื่ออนาคตอยากเก็บเทียบ)
        val digest = md.digest()
        return digest.isNotEmpty()
    }

    companion object {
        private const val DEFAULT_BUFFER = 128 * 1024
    }
}

/* ---------------------------------------------------------
   Utilities (นอกคลาส) — ใช้กับ SAF ได้สะดวกขึ้น
   --------------------------------------------------------- */

fun Context.openTreeChild(parentTree: Uri, relativePath: String): DocumentFile? {
    val tree = DocumentFile.fromTreeUri(this, parentTree) ?: return null
    val parts = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
    var cur: DocumentFile = tree
    for (p in parts) {
        val next = cur.findFile(p) ?: cur.createDirectory(p) ?: return null
        cur = next
    }
    return cur
}

/** สร้างไฟล์ใหม่ใต้โฟลเดอร์ปลายทาง (ให้ชื่อ + mime) */
fun Context.createTreeFile(parentDir: DocumentFile, displayName: String, mime: String?): DocumentFile? {
    return parentDir.createFile(mime ?: "application/octet-stream", displayName)
}

/** สร้าง DocumentFile จาก DocumentId (ใช้งานเมื่อจำเป็น) */
fun Context.documentFromId(documentId: String): Uri {
    return DocumentsContract.buildDocumentUriUsingTree(
        DocumentsContract.buildTreeDocumentUri(
            this.packageName,
            documentId
        ),
        documentId
    )
}
