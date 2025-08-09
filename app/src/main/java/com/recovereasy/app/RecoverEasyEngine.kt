@file:Suppress("DEPRECATION", "unused")
package com.recovereasy.app

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
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
import kotlin.math.max

// เพิ่ม processed/expected เพื่อให้ UI คำนวณ ETA ได้แม่นยำ
typealias ProgressCallback = (percent: Int, processed: Int, expected: Int, note: String) -> Unit

class RecoverEasyEngine(private val context: Context) {

    data class Item(
        val uri: Uri, val name: String, val mime: String?, val size: Long,
        val volumeId: String?, val kind: Kind, val isTrashed: Boolean
    )
    enum class Kind { IMAGE, VIDEO, AUDIO, DOC, ARCHIVE, OTHER }

    // ---------- Scans with progress + cancel ----------
    suspend fun scanPhoneAll(
        includeTrash: Boolean = true,
        progress: ProgressCallback? = null,
        cancelled: (() -> Boolean)? = null
    ): List<Item> = queryByVolumes(
        volumes = listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        includeTrash = includeTrash,
        includeDocs = true,
        progress = progress,
        cancelled = cancelled
    )

    suspend fun scanRemovableAll(
        includeTrash: Boolean = true,
        progress: ProgressCallback? = null,
        cancelled: (() -> Boolean)? = null
    ): List<Item> = withContext(Dispatchers.IO) {
        val names = MediaStore.getExternalVolumeNames(context).filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
        queryByVolumes(
            volumes = names,
            includeTrash = includeTrash,
            includeDocs = true,
            progress = progress,
            cancelled = cancelled
        )
    }

    suspend fun scanByFolderAllTypes(
        treeUri: Uri,
        progress: ProgressCallback? = null,
        cancelled: (() -> Boolean)? = null
    ): List<Item> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val expected = countFiles(root, cancelled)
        var processed = 0
        val acc = mutableListOf<Item>()
        fun walk(dir: DocumentFile) {
            if (cancelled?.invoke() == true) return
            dir.listFiles().forEach { f ->
                if (cancelled?.invoke() == true) return
                if (f.isDirectory) {
                    walk(f)
                } else {
                    acc += Item(
                        uri = f.uri,
                        name = f.name ?: "",
                        mime = f.type,
                        size = f.length(),
                        volumeId = extractVolumeId(f.uri),
                        kind = guessKind(f.name ?: "", f.type),
                        isTrashed = false
                    )
                    processed++
                    report(progress, processed, expected, "Scanning folder")
                }
            }
        }
        walk(root); acc
    }

    // ---------- Corruption / Repairs / Copy ----------
    suspend fun repairBestEffort(src: Item, destDir: Uri): Uri? = when (src.kind) {
        Kind.IMAGE -> repairImageToJpeg(src.uri, destDir)
        Kind.VIDEO -> remuxVideoMp4(src.uri, destDir)
        Kind.AUDIO -> remuxAudioContainer(src.uri, destDir)
        Kind.DOC -> if ((src.name).endsWith(".pdf", true)) repairPdfBasic(src.uri, destDir)
                     else safeCopyWithDigest(src.uri, destDir, src.name)
        Kind.ARCHIVE -> salvageZipLike(src.uri, destDir)
        Kind.OTHER -> safeCopyWithDigest(src.uri, destDir, src.name)
    }

    data class CorruptReport(val item: Item, val reason: String, val fixable: Boolean, val volumeId: String? = item.volumeId)

    // ---------- Internal: MediaStore multi-volume (media + docs) with progress + cancel ----------
    @SuppressLint("InlinedApi")
    private suspend fun queryByVolumes(
        volumes: List<String>,
        includeTrash: Boolean,
        includeDocs: Boolean,
        progress: ProgressCallback?,
        cancelled: (() -> Boolean)?
    ): List<Item> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mediaCollectionsOf = { v: String ->
            listOf(
                MediaStore.Images.Media.getContentUri(v),
                MediaStore.Video.Media.getContentUri(v),
                MediaStore.Audio.Media.getContentUri(v)
            )
        }
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_TRASHED
        )
        val sel = if (includeTrash) null else "${MediaStore.MediaColumns.IS_TRASHED}=0"

        // 1) Count expected (รวมไฟล์เอกสารถ้าเปิดใช้งาน)
        var expected = 0
        loop@ for (v in volumes) {
            for (base in mediaCollectionsOf(v)) {
                if (cancelled?.invoke() == true) break@loop
                resolver.query(base, arrayOf(MediaStore.MediaColumns._ID), sel, null, null)?.use { c ->
                    expected += c.count
                }
            }
            if (includeDocs) {
                if (cancelled?.invoke() == true) break@loop
                expected += countFilesTableRough(v, includeTrash)
            }
        }
        expected = max(expected, 1)

        // 2) Iterate media
        var processed = 0
        val out = mutableListOf<Item>()
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        outer@ for (v in volumes) {
            // images/videos/audios
            for (base in mediaCollectionsOf(v)) {
                if (cancelled?.invoke() == true) break@outer
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
                        out += Item(u, nm, mm, sz, v, guessKind(nm, mm), c.getInt(trash) == 1)
                        processed++
                        report(progress, processed, expected, "Scanning media")
                        if (cancelled?.invoke() == true) break
                    }
                }
            }
            // docs/archives via Files table (ถ้าอนุญาตได้)
            if (includeDocs) {
                if (cancelled?.invoke() == true) break@outer
                try {
                    val filesUri = MediaStore.Files.getContentUri(v)
                    val selDocs = buildString {
                        append("(")
                        append("${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/%'")
                        append(" OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'text/%'")
                        append(")")
                        if (!includeTrash) append(" AND ${MediaStore.MediaColumns.IS_TRASHED}=0")
                        // ตัด MIME ของรูป/วิดีโอ/เสียงออกกันซ้ำ
                        append(" AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'image/%'")
                        append(" AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'video/%'")
                        append(" AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'audio/%'")
                    }
                    resolver.query(filesUri, proj, selDocs, null, order)?.use { c ->
                        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val trash = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
                        while (c.moveToNext()) {
                            val u = ContentUris.withAppendedId(filesUri, c.getLong(id))
                            val nm = c.getString(name) ?: ""
                            val mm = c.getString(mime)
                            val sz = c.getLong(size)
                            val kind = guessKind(nm, mm)
                            // หมายเหตุ: การเปิดอ่านจริงของบางเอกสารอาจถูกระบบบล็อกตามสิทธิ์ — เราแค่แสดงรายการไว้ก่อน
                            out += Item(u, nm, mm, sz, v, kind, c.getInt(trash) == 1)
                            processed++
                            report(progress, processed, expected, "Scanning documents")
                            if (cancelled?.invoke() == true) break
                        }
                    }
                } catch (_: SecurityException) {
                    // อุปกรณ์/สิทธิ์ไม่อนุญาต เข้าถึงตาราง Files — ข้ามแบบเงียบ ๆ
                }
            }
        }
        out
    }

    // ใช้นับคร่าว ๆ ของ Files table (ถ้า query ได้) เพื่อประเมิน expected
    private fun countFilesTableRough(volume: String, includeTrash: Boolean): Int {
        return try {
            val resolver = context.contentResolver
            val filesUri = MediaStore.Files.getContentUri(volume)
            val selDocs = buildString {
                append("(")
                append("${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/%'")
                append(" OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'text/%'")
                append(")")
                if (!includeTrash) append(" AND ${MediaStore.MediaColumns.IS_TRASHED}=0")
                append(" AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'image/%'")
                append(" AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'video/%'")
                append(" AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'audio/%'")
            }
            resolver.query(filesUri, arrayOf(MediaStore.MediaColumns._ID), selDocs, null, null)?.use { it.count } ?: 0
        } catch (_: Throwable) { 0 }
    }

    private fun report(cb: ProgressCallback?, processed: Int, expected: Int, note: String) {
        cb?.invoke(((processed * 100f) / expected).toInt().coerceIn(0, 100), processed, expected, note)
    }

    // ---------- Probes ----------
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
                while (true) {
                    val e = zis.nextEntry ?: break; entries++
                    while (zis.read(buf) > 0) {}
                    zis.closeEntry()
                }
                entries >= 0
            }
        } ?: false
    }.getOrDefault(false)

    private fun probeGeneric(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read(ByteArray(1024)) >= 0 } ?: false
    }.getOrDefault(false)

    // ---------- Repairs / Safe copy ----------
    suspend fun repairImageToJpeg(src: Uri, destDir: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val name = (queryName(src) ?: "image").substringBeforeLast(".") + "_fixed.jpg"
            val outDoc = createDestFile(destDir, "image/jpeg", name) ?: return@withContext null
            val bmp = context.contentResolver.openInputStream(src)?.use { ins -> BitmapFactory.decodeStream(ins) } ?: return@withContext null
            context.contentResolver.openOutputStream(outDoc.uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            context.contentResolver.openFileDescriptor(outDoc.uri, "rw")?.use { pfd -> Os.fsync(pfd.fileDescriptor) }
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

    // ---------- Utils ----------
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

    // นับไฟล์เพื่อ progress (รองรับ cancel)
    private fun countFiles(dir: DocumentFile, cancelled: (() -> Boolean)?): Int {
        if (cancelled?.invoke() == true) return 1
        var c = 0
        dir.listFiles().forEach { f ->
            if (cancelled?.invoke() == true) return@forEach
            c += if (f.isDirectory) countFiles(f, cancelled) else 1
        }
        return c
    }
}
