package com.phonesearch.app

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class TransferResult(val done: Int, val failed: Int, val skipped: Int)

/** Moves or copies files on the phone and tells Android's media index about the change. */
object FileOps {

    suspend fun transfer(context: Context, items: List<SearchResult>, dest: File, move: Boolean): TransferResult =
        withContext(Dispatchers.IO) {
            var done = 0
            var failed = 0
            var skipped = 0
            val changedPaths = ArrayList<String>()
            for (r in items) {
                val src = r.path?.let { File(it) }
                if (src == null || !src.isFile) {
                    skipped++
                    continue
                }
                if (move && src.parentFile?.canonicalPath == dest.canonicalPath) {
                    skipped++ // already in that folder
                    continue
                }
                val target = uniqueTarget(dest, src.name)
                val ok = try {
                    if (move) {
                        src.renameTo(target) || (copy(src, target) && deleteOrRollback(src, target))
                    } else {
                        copy(src, target)
                    }
                } catch (e: Exception) {
                    target.delete()
                    false
                }
                if (ok) {
                    done++
                    changedPaths += target.absolutePath
                    if (move) changedPaths += src.absolutePath
                } else {
                    failed++
                }
            }
            scan(context, changedPaths)
            TransferResult(done, failed, skipped)
        }

    /** Copy each plan's photo into outputRoot/<group>/<newName>, keeping the original untouched. */
    suspend fun copyPlans(context: Context, plans: List<PhotoPlan>, outputRoot: File): TransferResult =
        withContext(Dispatchers.IO) {
            var done = 0
            var failed = 0
            val created = ArrayList<String>()
            for (p in plans) {
                val dir = File(outputRoot, p.group.folder)
                val target = if (dir.isDirectory || dir.mkdirs()) uniqueTarget(dir, p.newName) else null
                val ok = target != null && try {
                    copy(p.source, target)
                } catch (e: Exception) {
                    target.delete()
                    false
                }
                if (ok) {
                    done++
                    created += target!!.absolutePath
                } else {
                    failed++
                }
            }
            scan(context, created)
            TransferResult(done, failed, 0)
        }

    private fun copy(src: File, target: File): Boolean {
        src.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        target.setLastModified(src.lastModified()) // keep the original date for date-wise search
        return target.length() == src.length()
    }

    private fun deleteOrRollback(src: File, target: File): Boolean {
        if (src.delete()) return true
        target.delete() // couldn't remove the original: undo the copy so nothing is duplicated
        return false
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    /** Ask MediaStore to re-index, and wait (briefly) so the next search sees the new locations. */
    private suspend fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        val remaining = java.util.concurrent.atomic.AtomicInteger(paths.size)
        val finished = CompletableDeferred<Unit>()
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
            if (remaining.decrementAndGet() == 0) finished.complete(Unit)
        }
        withTimeoutOrNull(5000) { finished.await() }
    }
}
