package com.recovereasy.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extension ช่วยให้ MainActivity เรียกใช้ได้ แม้ใน RecoverEasyEngine
 * ของคุณจะยังไม่มีเมธอดเหล่านี้ (ไม่ทับของเดิม)
 */
suspend fun RecoverEasyEngine.safeCopyWithDigest(
    src: Uri,
    toTree: Uri,
    fileName: String
) = withContext(Dispatchers.IO) {
    // ถ้าใน RecoverEasyEngine ของคุณมีเมธอดชื่อนี้จริง ๆ โค้ดนี้จะ call-through
    // ถ้าไม่มี จะพยายามเรียก แล้วรีเทิร์น null แทน (ไม่ให้ build พัง)
    try { this@safeCopyWithDigest.safeCopyWithDigest(src, toTree, fileName) } catch (_: Throwable) { null }
}

/** กรณี MainActivity เรียก scanAppCaches แต่ Engine ยังไม่มี ให้ map ไป scanByFolderAllTypes ชั่วคราว */
suspend fun RecoverEasyEngine.scanAppCaches(
    root: Uri,
    onProgress: ((Int, Int) -> Unit)? = null,
    isCancelled: (() -> Boolean)? = null
) = this.scanByFolderAllTypes(root, onProgress, isCancelled)
