package com.phonesearch.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object Contacts {

    /** ఫోన్ కాంటాక్ట్స్‌ను లోకల్ డేటాబేస్‌లోకి కాపీ చేస్తుంది. */
    fun sync(ctx: Context): Int {
        val list = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                list.add(name to num)
            }
        }
        return Db.get(ctx).replaceContacts(list)
    }

    /** CSV / టెక్స్ట్ ఫైల్: ప్రతి లైన్‌లో "పేరు,నంబర్" లేదా "నంబర్,పేరు". */
    fun importCsv(ctx: Context, uri: Uri): Int {
        val list = ArrayList<Pair<String, String>>()
        ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
            r.forEachLine { raw ->
                val line = raw.removePrefix("﻿")
                val parts = line.split(',', ';', '\t').map { it.trim().trim('"').trim() }
                val numIdx = parts.indexOfFirst { p ->
                    p.isNotEmpty() &&
                        p.all { it.isDigit() || it in "+ -()" } &&
                        p.count { it.isDigit() } >= 7
                }
                if (numIdx >= 0) {
                    val name = parts.filterIndexed { i, s -> i != numIdx && s.isNotEmpty() }.joinToString(" ")
                    if (name.isNotEmpty()) list.add(name to parts[numIdx])
                }
            }
        }
        return Db.get(ctx).importList(list)
    }
}
