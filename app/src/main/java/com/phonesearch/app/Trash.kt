package com.phonesearch.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

data class TrashItem(
    val id: Long,
    val binPath: String,
    val origPath: String,
    val name: String,
    val size: Long,
    val deletedAt: Long
)

/** రీసైకిల్ బిన్ + శాశ్వత డిలీట్. */
object Trash {
    const val DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun binDir(): File {
        val d = File(Environment.getExternalStorageDirectory(), ".PhoneSearchBin")
        d.mkdirs()
        val nomedia = File(d, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return d
    }

    private fun scan(ctx: Context, vararg paths: String) {
        MediaScannerConnection.scanFile(ctx, paths, null, null)
    }

    private fun moveFile(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = true)
            from.delete()
        } catch (e: Exception) {
            to.delete()
            false
        }
    }

    /** ఫైల్‌ను బిన్‌కి పంపుతుంది (తిరిగి తెచ్చుకోవచ్చు). */
    fun moveToBin(ctx: Context, f: File): Boolean {
        if (!f.isFile) return false
        val db = Db.get(ctx).writableDatabase
        val v = ContentValues().apply {
            put("binPath", "")
            put("origPath", f.absolutePath)
            put("name", f.name)
            put("size", f.length())
            put("deletedAt", System.currentTimeMillis())
        }
        val id = db.insert("trash", null, v)
        if (id == -1L) return false
        val dest = File(binDir(), "${id}_${f.name}")
        val orig = f.absolutePath
        val ok = moveFile(f, dest)
        if (ok) {
            db.execSQL("UPDATE trash SET binPath=? WHERE id=?", arrayOf<Any>(dest.absolutePath, id))
            scan(ctx, orig)
        } else {
            db.delete("trash", "id=?", arrayOf(id.toString()))
        }
        return ok
    }

    /** బిన్ లేకుండా నేరుగా శాశ్వతంగా తొలగిస్తుంది. */
    fun deleteForever(ctx: Context, f: File): Boolean {
        val p = f.absolutePath
        val ok = f.delete()
        if (ok) scan(ctx, p)
        return ok
    }

    fun list(ctx: Context): List<TrashItem> {
        val out = ArrayList<TrashItem>()
        Db.get(ctx).readableDatabase.rawQuery(
            "SELECT id, binPath, origPath, name, size, deletedAt FROM trash ORDER BY deletedAt DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(TrashItem(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5)))
            }
        }
        return out
    }

    fun daysLeft(item: TrashItem): Int {
        val left = DAYS - ((System.currentTimeMillis() - item.deletedAt) / DAY_MS).toInt()
        return if (left < 0) 0 else left
    }

    fun restore(ctx: Context, item: TrashItem): Boolean {
        val src = File(item.binPath)
        if (!src.exists()) {
            removeRow(ctx, item.id)
            return false
        }
        var dest = File(item.origPath)
        dest.parentFile?.mkdirs()
        if (dest.exists()) {
            val base = dest.nameWithoutExtension
            val ext = dest.extension
            val newName = if (ext.isEmpty()) "$base (restored)" else "$base (restored).$ext"
            dest = File(dest.parentFile, newName)
        }
        val ok = moveFile(src, dest)
        if (ok) {
            removeRow(ctx, item.id)
            scan(ctx, dest.absolutePath)
        }
        return ok
    }

    fun deleteFromBin(ctx: Context, item: TrashItem): Boolean {
        val f = File(item.binPath)
        val ok = !f.exists() || f.delete()
        if (ok) removeRow(ctx, item.id)
        return ok
    }

    fun emptyBin(ctx: Context): Int {
        var n = 0
        for (t in list(ctx)) if (deleteFromBin(ctx, t)) n++
        return n
    }

    /** 30 రోజులు దాటినవి శాశ్వతంగా తొలగిస్తుంది. */
    fun purgeOld(ctx: Context) {
        for (t in list(ctx)) {
            if (System.currentTimeMillis() - t.deletedAt > DAYS * DAY_MS) deleteFromBin(ctx, t)
        }
    }

    private fun removeRow(ctx: Context, id: Long) {
        Db.get(ctx).writableDatabase.delete("trash", "id=?", arrayOf(id.toString()))
    }
}
