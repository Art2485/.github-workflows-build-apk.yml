@file:Suppress("DEPRECATION", "unused")
package com.recovereasy.app

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RecoverEasyEngine(private val context: Context) {

    data class Item(
        val uri: Uri,
        val name: String,
        val mime: String?,
        val size: Long,
        val volumeId: String?,
        val kind: Kind,
        val isTrashed: Boolean
    )
    enum class Kind { IMAGE, VIDEO, AUDIO, DOC, ARCHIVE, OTHER }

    suspend fun scanPhoneAll(includeTrash: Boolean = true): List<Item> =
        queryByVolume(MediaStore.VOLUME_EXTERNAL_PRIMARY, includeTrash)

    suspend fun scanRemovableAll(includeTrash: Boolean = true): List<Item> = withContext(Dispatchers.IO) {
        val names = MediaStore.getExternalVolumeNames(context)
        val removable = names.filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
        val out = mutableListOf<Item>()
        for (v in removable) out += queryByVolume(v, includeTrash)
        out
    }

    suspend fun scanByFolderAllTypes(treeUri: Uri): List<Item> = listFromTree(treeUri)

    suspend fun repairBestEffort(src: Item, destDir: Uri): Uri? = when (src.kind) {
        Kind.IMAGE -> repairImageToJpeg(src.uri, destDir)
        Kind.VIDEO -> remuxVideoMp4(src.uri, destDir)
        Kind.AUDIO -> remuxAudioContainer(src.uri, destDir)
        Kind.DOC -> if (src.name.endsWith(".pdf", true)) repairPdfBasic(src.uri, destDir) else safeCopyWithDigest(src.uri, destDir, src.name)
        Kind.ARCHIVE -> salvageZipLike(src.uri, destDir)
        Kind.OTHER -> safeCopyWithDigest(src.uri, destDir, src.name)
    }

    @SuppressLint("InlinedApi")
    fun requestUntrash(uris: List<Uri>) {
        val req = MediaStore.createTrashRequest(context.contentResolver, uris, false)
        if (Build.VERSION.SDK_INT >= 26) {
            (context as? android.app.Activity)?.startIntentSenderForResult(
                req.intentSender, 2001, null, 0, 0, 0
            )
        }
    }

    @SuppressLint("InlinedApi")
    private suspend fun queryByVolume(volume: String, includeTrash: Boolean): List<Item> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Item>()
        val resolver = context.contentResolver
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(volume),
            MediaStore.Video.Media.getContentUri(volume),
            MediaStore.Audio.Media.getContentUri(volume)
        )
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_TRASHED
        )
        val sel = if (includeTrash) null else "${MediaStore.MediaColumns.IS_TRASHED}=0"
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        for (base in collections) {
            resolver.query(base, proj, sel, null, order)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val trash = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
                while (c.moveToNext()) {
                    val u = ContentUris.withAppendedId(base, c.getLong(id))
                    val nm = c.getString(name) ?: ""
                    val mm = c.getString(mime)
                    val sz = c.getLong(size)
                    val it = Item(u, nm, mm, sz, volume, guessKind(nm, mm), c.getInt(trash) == 1)
                    out += it
                }
            }
        }
        out
    }

    private suspend fun listFromTree(treeUri: Uri): List<Item> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val acc = mutableListOf<Item>()
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { f ->
                if (f.isDirectory) walk(f) else acc += Item(
                    uri = f.uri,
                    name = f.name ?: "",
                    mime = f.type,
                    size = f.length(),
                    volumeId = extractVolumeId(f.uri),
                    kind = guessKind(f.name ?: "", f.type),
                    isTrashed = false
                )
            }
        }
        walk(root)
        acc
    }

    suspend fun detectCorruption(item: Item): CorruptReport? = withContext(Dispatchers.IO) {
        try {
            when (item.kind) {
                Kind.IMAGE -> if (!probeImage(item.uri)) CorruptReport(item, "อ่านรูปไม่ได้/ข้อมูลไม่ครบ", true) else null
                Kind.VIDEO -> if (!probeVideo(item.uri)) CorruptReport(item, "วิดีโออ่าน track ไม่ได้หรือข้อมูลผิดรูป", true) else null
                Kind.AUDIO -> if (!probeAudio(item.uri)) CorruptReport(item, "เสียงอ่าน metadata/สตรีมไม่ได้", true) else null
                Kind.DOC -> if (!probeDocument(item)) CorruptReport(item, "เอกสารเสียหรือเปิดไม่ได้", false) else null
                Kind.ARCHIVE -> if (!probeZipLike(item.uri)) CorruptReport(item, "ไฟล์บีบอัดเสีย/entry เสียหาย", true) else null
                Kind.OTHER -> if (!probeGeneric(item.uri)) CorruptReport(item, "ไฟล์อาจเสีย (อ่านไม่ได้)", false) else null
            }
        } catch (_: Throwable) { CorruptReport(item, "ข้อผิดพลาดขณะตรวจสอบ", false) }
    }

    data class CorruptReport(
        val item: Item,
        val reason: String,
        val fixable: Boolean,
        val volumeId: String? = item.volumeId
    )

    private fun probeImage(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(ins, null, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } ?: false
    }.getOrDefault(false)

    private fun probeVideo(uri: Uri) = runCatching {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(context, uri, null)
        var ok = ex.trackCount > 0
        if (ok) {
            ex.selectTrack(0)
            val buf = ByteBuffer.allocate(1 shl 16)
            ok = ex.readSampleData(buf, 0) >= 0
        }
        ex.release(); ok
    }.getOrDefault(false)

    private fun probeAudio(uri: Uri) = runCatching {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(context, uri)
        val has = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) != null
        r.release(); has
    }.getOrDefault(false)

    private fun probeDocument(item: Item): Boolean {
        val n = item.name.lowercase()
        return when {
            n.endsWith(".pdf") -> probePdf(item.uri)
            n.endsWith(".txt") || n.endsWith(".csv") -> probeText(item.uri)
            n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".pptx") -> probeZipLike(item.uri)
            else -> probeGeneric(item.uri)
        }
    }

    private fun probePdf(uri: Uri) = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount > 0 }
        } ?: false
    }.getOrDefault(false)

    private fun probeText(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read(ByteArray(1024)) >= 0 } ?: false
    }.getOrDefault(false)

    private fun probeZipLike(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(BufferedInputStream(ins)).use { zis ->
                var entries = 0; val buf = ByteArray(8192)
                while (true) { val e = zis.nextEntry ?: break; entries++; while (zis.read(buf) > 0) {}; zis.closeEntry() }
                entries >= 0
            }
        } ?: false
    }.getOrDefault(false)

    private fun probeGeneric(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read(ByteArray(1024)) >= 0 } ?: false
    }.getOrDefault(false)

    suspend fun repairImageToJpeg(src: Uri, destDir: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val name = (queryName(src) ?: "image").substringBeforeLast(".") + "_fixed.jpg"
            val outDoc = createDestFile(destDir, "image/jpeg", name) ?: return@withContext null
            val bmp = context.contentResolver.openInputStream(src)?.use { ins -> BitmapFactory.decodeStream(ins) } ?: return@withContext null
            context.contentResolver.openOutputStream(outDoc.uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            context.contentResolver.openFileDescriptor(outDoc.uri, "rw")?.use { p -> Os.fsync(p.fileDescriptor) }
            outDoc.uri
        } catch (_: Exception) { null }
    }

    suspend fun remuxVideoMp4(src: Uri, destDir: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val name = (queryName(src)?.substringBeforeLast(".") ?: "video") + "_fixed.mp4"
            val outDoc = createDestFile(destDir, "video/mp4", name) ?: return@withContext null
            val ex = android.media.MediaExtractor(); ex.setDataSource(context, src, null)
            val pfd = context.contentResolver.openFileDescriptor(outDoc.uri, "rw") ?: return@withContext null
            val mux = android.media.MediaMuxer(pfd.fileDescriptor, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = mutableMapOf<Int, Int>()
            for (i in 0 until ex.trackCount) map[i] = mux.addTrack(ex.getTrackFormat(i))
            mux.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            for ((inIdx, outIdx) in map) {
                ex.unselectTrack(inIdx); ex.selectTrack(inIdx)
                while (true) {
                    info.offset = 0
                    info.size = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = ex.sampleFlags
                    mux.writeSampleData(outIdx, buf, info)
                    ex.advance()
                }
            }
            mux.stop(); mux.release(); ex.release()
            context.contentResolver.openFileDescriptor(outDoc.uri, "rw")?.use { p -> Os.fsync(p.fileDescriptor) }
            outDoc.uri
        } catch (_: Exception) { null }
    }

    suspend fun remuxAudioContainer(src: Uri, destDir: Uri): Uri? = withContext(Dispatchers.IO) {
        if (!probeAudio(src)) return@withContext null
        val base = (queryName(src)?.substringBeforeLast(".") ?: "audio")
        safeCopyWithDigest(src, destDir, "${base}_fixed.m4a")
    }

    suspend fun repairPdfBasic(src: Uri, destDir: Uri): Uri? = withContext(Dispatchers.IO) {
        if (!probePdf(src)) return@withContext null
        safeCopyWithDigest(src, destDir, (queryName(src)?.substringBeforeLast(".") ?: "doc") + "_fixed.pdf")
    }

    suspend fun salvageZipLike(src: Uri, destDir: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val base = (queryName(src)?.substringBeforeLast(".") ?: "archive")
            val out = createDestFile(destDir, "application/zip", "${base}_fixed.zip") ?: return@withContext null
            context.contentResolver.openInputStream(src)?.use { ins ->
                ZipInputStream(BufferedInputStream(ins)).use { zis ->
                    context.contentResolver.openOutputStream(out.uri)?.use { os ->
                        ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                            var e: ZipEntry?; val buf = ByteArray(8192)
                            while (true) {
                                e = zis.nextEntry ?: break
                                try {
                                    zos.putNextEntry(ZipEntry(e!!.name))
                                    while (true) { val n = zis.read(buf); if (n <= 0) break; zos.write(buf, 0, n) }
                                    zos.closeEntry()
                                } catch (_: Exception) { } finally { zis.closeEntry() }
                            }
                        }
                    }
                }
            }
            context.contentResolver.openFileDescriptor(out.uri, "rw")?.use { p -> Os.fsync(p.fileDescriptor) }
            out.uri
        } catch (_: Exception) { null }
    }

    suspend fun safeCopyWithDigest(src: Uri, destDir: Uri, outName: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val tmp = createDestFile(destDir, "application/octet-stream", "$outName.part") ?: return@withContext null
            val md = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(src)?.use { `in` ->
                context.contentResolver.openOutputStream(tmp.uri, "w")?.use { out ->
                    copyStreamDigest(`in`, out, md)
                    out.flush()
                    context.contentResolver.openFileDescriptor(tmp.uri, "rw")?.use { pfd -> Os.fsync(pfd.fileDescriptor) }
                }
            }
            DocumentsContract.renameDocument(context.contentResolver, tmp.uri, outName) ?: tmp.uri
        } catch (_: Exception) { null }
    }

    private fun copyStreamDigest(`in`: InputStream, out: OutputStream, md: MessageDigest) {
        val buf = ByteArray(1 shl 16)
        while (true) { val n = `in`.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
    }

    private fun queryName(uri: Uri): String? = context.contentResolver.query(
        uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun createDestFile(destDir: Uri, mime: String, name: String): DocumentFile? {
        val dir = DocumentFile.fromTreeUri(context, destDir) ?: return null
        dir.findFile(name)?.delete()
        return dir.createFile(mime, name)
    }

    private fun extractVolumeId(uri: Uri): String? {
        val s = uri.toString()
        val idx = s.indexOf("documents/tree/")
        if (idx >= 0) {
            val tail = s.substring(idx + "documents/tree/".length)
            val vol = tail.substringBefore("%3A").substringBefore(":")
            return if (vol.contains("-")) vol else null
        }
        return null
    }

    private fun guessKind(name: String, mime: String?): Kind {
        val n = name.lowercase()
        return when {
            mime?.startsWith("image/") == true || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic") || n.endsWith(".heif") || n.endsWith(".avif") || n.endsWith(".gif") -> Kind.IMAGE
            mime?.startsWith("video/") == true || n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".m4v") || n.endsWith(".3gp") || n.endsWith(".mkv") -> Kind.VIDEO
            mime?.startsWith("audio/") == true || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".opus") -> Kind.AUDIO
            n.endsWith(".pdf") || n.endsWith(".txt") || n.endsWith(".csv") || n.endsWith(".doc") || n.endsWith(".docx") || n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".ppt") || n.endsWith(".pptx") -> Kind.DOC
            n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".tar") || n.endsWith(".gz") -> Kind.ARCHIVE
            else -> Kind.OTHER
        }
    }
}
