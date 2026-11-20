package com.phonesearch.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** ఫోన్ లోపలే ఉండే చిన్న డేటాబేస్: నంబర్ → పేరు, మరియు రీసైకిల్ బిన్ వివరాలు. */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "phonesearch.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // source: 0 = ఫోన్ కాంటాక్ట్స్, 1 = దిగుమతి చేసిన CSV జాబితా
        db.execSQL("CREATE TABLE numbers(last10 TEXT PRIMARY KEY, name TEXT NOT NULL, source INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE trash(id INTEGER PRIMARY KEY AUTOINCREMENT, binPath TEXT NOT NULL, " +
                "origPath TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, deletedAt INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun lookup(number: String): String? {
        val k = last10(number)
        if (k.length < 7) return null
        readableDatabase.rawQuery("SELECT name FROM numbers WHERE last10=?", arrayOf(k)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** ఫోన్ కాంటాక్ట్స్ మళ్లీ రాస్తుంది (పాత కాంటాక్ట్ రికార్డులు తీసేసి). */
    fun replaceContacts(list: List<Pair<String, String>>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            db.delete("numbers", "source=0", null)
            for ((name, num) in list) {
                val k = last10(num)
                if (k.length < 7) continue
                val v = ContentValues().apply {
                    put("last10", k); put("name", name); put("source", 0)
                }
                db.insertWithOnConflict("numbers", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** CSV జాబితా: కాంటాక్ట్స్‌లో ఇప్పటికే ఉన్న నంబర్లను మార్చదు. */
    fun importList(list: List<Pair<String, String>>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for ((name, num) in list) {
                val k = last10(num)
                if (k.length < 7) continue
                val v = ContentValues().apply {
                    put("last10", k); put("name", name); put("source", 1)
                }
                if (db.insertWithOnConflict("numbers", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun numberCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM numbers", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx).also { inst = it } }

        /** +91 98480 12345, 098480-12345 ఏ రూపంలో ఉన్నా చివరి 10 అంకెలతో సరిపోల్చడం. */
        fun last10(n: String): String {
            val d = n.filter { it.isDigit() }
            return if (d.length > 10) d.takeLast(10) else d
        }
    }
}
