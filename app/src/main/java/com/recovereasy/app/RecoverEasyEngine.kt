package com.recovereasy.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

class RecoverEasyEngine(private val context: Context) {

    data class Item(
        val uri: Uri,
        val name: String,
        val mime: String?,
        val size: Long,
        val isTrashed: Boolean
    )

    // ------------------------------------------------------------
    // Public APIs
    // ------------------------------------------------------------
    suspend fun scanPhoneAll(
        includeTrash: Boolean = true,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<Item> {
        val vols = if (Build.VERSION.SDK_INT >= 30)
            listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaStore.VOLUME_EXTERNAL)
        else listOf("external")
        val out = mutableListOf<Item>()
        for (v in vols) {
            out += queryVolume(v, includeTrash, onProgress, isCancelled)
            if (isCancelled?.invoke() == true) break
        }
        return dedupSort(out)
    }

    suspend fun scanRemovableAll(
        includeTrash: Boolean = true,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<Item> = scanPhoneAll(includeTrash, onProgress, isCancelled)

    suspend fun scanByFolderAllTypes(
        treeUri: Uri,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<Item> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val stack = ArrayDeque<DocumentFile>()
        root.listFiles().forEach { stack.add(it) }
        val out = mutableListOf<Item>()
        var processed = 0
        var total = max(1, stack.size)

        while (stack.isNotEmpty()) {
            if (isCancelled?.invoke() == true) break
            val f = stack.removeFirst()
            if (f.isDirectory) {
                f.listFiles().forEach { stack.add(it) }
                total++
                continue
            }
            val name = f.name ?: "file"
            val mime = context.contentResolver.getType(f.uri)
            val size = try { f.length() } catch (_: Throwable) { -1L }
            val trashedGuess = isTrashLikePath(name, f.uri)
            if (acceptMime(mime, name)) {
                out += Item(f.uri, name, mime, size, trashedGuess)
            }
            processed++
            onProgress?.invoke(processed, total)
        }
        return dedupSort(out)
    }

    /** สแกนแคช/สื่อของแอปยอดนิยมที่ Android/media/* (ผู้ใช้ต้องเลือก root ด้วย SAF) */
    suspend fun scanAppCaches(
        root: Uri,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<Item> = scanByFolderAllTypes(root, onProgress, isCancelled)

    /** คัดลอกไฟล์พร้อมตรวจสอบแฮช (SHA-256) คืน Uri ถ้าสำเร็จ */
    suspend fun safeCopyWithDigest(src: Uri, toTree: Uri, fileName: String): Uri? {
        val cr = context.contentResolver
        val tree = DocumentFile.fromTreeUri(context, toTree) ?: return null
        val outFile = tree.createFile(cr.getType(src) ?: "application/octet-stream", fileName)
            ?: return null

        val sha256 = MessageDigest.getInstance("SHA-256")
        cr.openInputStream(src)?.use { input ->
            cr.openOutputStream(outFile.uri, "w")?.use { output ->
                copyDigest(input, output, sha256)
            } ?: return null
        } ?: return null

        val srcHash = sha256.digest().toHex()
        // verify
        val verify = MessageDigest.getInstance("SHA-256")
        cr.openInputStream(outFile.uri)?.use { input ->
            drainDigest(input, verify)
        } ?: return null
        val dstHash = verify.digest().toHex()
        return if (srcHash == dstHash) outFile.uri else { outFile.delete(); null }
    }

    // ------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------
    private fun queryVolume(
        volume: String,
        includeTrash: Boolean,
        onProgress: ((processed: Int, total: Int) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): List<Item> {
        val cr = context.contentResolver
        val base = MediaStore.Files.getContentUri(volume)
        val proj = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE
        )
        val hasTrash = Build.VERSION.SDK_INT >= 30
        if (hasTrash) proj += MediaStore.Files.FileColumns.IS_TRASHED

        val sel = if (hasTrash && !includeTrash) "(is_trashed=0 OR is_trashed IS NULL)" else null
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val out = mutableListOf<Item>()
        cr.query(base, proj.toTypedArray(), sel, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val trashCol = if (hasTrash) c.getColumnIndex("is_trashed") else -1

            val total = max(1, c.count)
            var processed = 0
            while (c.moveToNext()) {
                if (isCancelled?.invoke() == true) break
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val mime = c.getString(mimeCol)
                val size = c.getLong(sizeCol)
                val isTrashed = if (trashCol >= 0) c.getInt(trashCol) == 1 else false
                if (!acceptMime(mime, name)) { processed++; continue }

                val uri = ContentUris.withAppendedId(base, id)
                out += Item(uri, name, mime, size, isTrashed)
                processed++
                if (processed % 50 == 0) onProgress?.invoke(processed, total)
            }
            onProgress?.invoke(processed, total)
        }
        return out
    }

    private fun acceptMime(mime: String?, name: String): Boolean {
        val m = mime?.lowercase(Locale.US) ?: ""
        if (m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/")
            || m.startsWith("application/") || m.startsWith("text/")) return true
        val n = name.lowercase(Locale.US)
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".heic") || n.endsWith(".mp4") || n.endsWith(".mp3")
                || n.endsWith(".pdf") || n.endsWith(".zip") || n.endsWith(".rar")
                || n.endsWith(".7z") || n.endsWith(".tar") || n.endsWith(".gz")
    }

    private fun isTrashLikePath(name: String, uri: Uri): Boolean {
        val p = uri.toString().lowercase(Locale.US)
        return name.startsWith(".trash", true) || p.contains("/.trash")
                || p.contains("/.recycle") || p.contains("/recyclebin")
                || p.contains("/.thumbnails")
    }

    private fun dedupSort(list: List<Item>): List<Item> =
        list.distinctBy { it.uri }.sortedByDescending { it.uri.toString() }

    private fun copyDigest(input: InputStream, output: OutputStream, md: MessageDigest) {
        val buf = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            md.update(buf, 0, n)
        }
        output.flush()
    }
    private fun drainDigest(input: InputStream, md: MessageDigest) {
        val buf = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
