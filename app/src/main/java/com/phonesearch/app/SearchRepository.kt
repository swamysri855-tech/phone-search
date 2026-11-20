package com.phonesearch.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Searches contacts, SMS and every file indexed on the phone. */
class SearchRepository(private val context: Context) {

    private val resolver = context.contentResolver

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun search(f: SearchFilters): SearchOutcome = withContext(Dispatchers.IO) {
        coroutineScope {
            val wantContacts = f.category == Category.ALL || f.category == Category.CONTACTS
            val wantMessages = f.category == Category.ALL || f.category == Category.MESSAGES
            val wantFiles = f.category != Category.CONTACTS && f.category != Category.MESSAGES

            val contactsJob = async { if (wantContacts) safe { searchContacts(f) } else emptyList() }
            val messagesJob = async { if (wantMessages) safe { searchMessages(f) } else emptyList() }
            val filesJob = async { if (wantFiles) safe { searchFiles(f) } else emptyList() }

            val contacts = contactsJob.await()
            val messages = messagesJob.await()
            val files = filesJob.await()

            val all = (contacts + messages + files).let { list ->
                when (f.sort) {
                    SortMode.NEWEST -> list.sortedByDescending { it.date }
                    SortMode.OLDEST -> list.sortedBy { it.date }
                    SortMode.NAME -> list.sortedBy { it.title.lowercase() }
                    SortMode.SIZE -> list.sortedByDescending { it.size }
                }
            }
            SearchOutcome(all, contacts.size, messages.size, files.size)
        }
    }

    private inline fun safe(block: () -> List<SearchResult>): List<SearchResult> =
        try {
            block()
        } catch (e: Exception) {
            emptyList()
        }

    /** Builds "(colA LIKE ? OR colB LIKE ?) AND (...)" — every word must match some column. */
    private fun termsClause(terms: List<String>, columns: List<String>, args: MutableList<String>): String? {
        if (terms.isEmpty()) return null
        return terms.joinToString(" AND ") { term ->
            columns.forEach { _ -> args += "%$term%" }
            "(" + columns.joinToString(" OR ") { "$it LIKE ?" } + ")"
        }
    }

    // ---------------------------------------------------------------- Contacts

    private data class ContactNumber(val name: String, val number: String)

    private fun loadAllContactNumbers(): List<ContactNumber> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<ContactNumber>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(COL_DISPLAY_NAME, COL_NUMBER), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                out += ContactNumber(name, num)
            }
        }
        return out
    }

    private fun searchContacts(f: SearchFilters): List<SearchResult> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val args = ArrayList<String>()
        val digitTerms = f.terms.map { t -> if (t.any { it.isDigit() }) t.filter { it.isDigit() || it == '+' } else t }
        val sel = termsClause(digitTerms, listOf(COL_DISPLAY_NAME, COL_NUMBER, COL_NORMALIZED_NUMBER), args)
        val out = ArrayList<SearchResult>()
        val seen = HashSet<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(COL_CONTACT_ID, COL_DISPLAY_NAME, COL_NUMBER, COL_LOOKUP, COL_UPDATED),
            sel, args.toTypedArray(), "$COL_DISPLAY_NAME COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < LIMIT_CONTACTS) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: ""
                val number = c.getString(2) ?: ""
                val updated = c.getLong(4)
                if (!f.dateRange.isAny && !f.inRange(updated)) continue
                if (!seen.add("$id:${lastDigits(number)}")) continue
                val uri = ContactsContract.Contacts.getLookupUri(id, c.getString(3))
                out += SearchResult(
                    kind = Kind.CONTACT,
                    title = name.ifBlank { number },
                    subtitle = number,
                    date = updated,
                    uri = uri,
                    number = number,
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Messages

    private fun searchMessages(f: SearchFilters): List<SearchResult> {
        if (!has(Manifest.permission.READ_SMS)) return emptyList()
        val contacts = loadAllContactNumbers()
        val nameByDigits = HashMap<String, String>()
        contacts.forEach { nameByDigits[lastDigits(it.number)] = it.name }

        val args = ArrayList<String>()
        val clauses = ArrayList<String>()
        // Each word must match the body, the number, or the name of the sender.
        for (term in f.terms) {
            val parts = arrayListOf("body LIKE ?", "address LIKE ?")
            args += "%$term%"
            args += "%$term%"
            contacts.asSequence()
                .filter { it.name.contains(term, ignoreCase = true) }
                .map { lastDigits(it.number) }
                .filter { it.length >= 5 }
                .distinct()
                .take(25)
                .forEach {
                    parts += "address LIKE ?"
                    args += "%$it"
                }
            clauses += "(" + parts.joinToString(" OR ") + ")"
        }
        if (!f.dateRange.isAny) {
            clauses += "date >= ? AND date <= ?"
            args += f.dateRange.from.toString()
            args += f.dateRange.to.toString()
        }
        val order = if (f.sort == SortMode.OLDEST) "date ASC" else "date DESC"
        val out = ArrayList<SearchResult>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "type"),
            clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.toTypedArray(), order,
        )?.use { c ->
            while (c.moveToNext() && out.size < LIMIT_MESSAGES) {
                val address = c.getString(1) ?: ""
                val body = c.getString(2) ?: ""
                val date = c.getLong(3)
                val type = c.getInt(4)
                val name = nameByDigits[lastDigits(address)]
                val direction = if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "పంపినది ↗" else "వచ్చినది ↙"
                out += SearchResult(
                    kind = Kind.MESSAGE,
                    title = name?.let { "$it ($address)" } ?: address,
                    subtitle = snippet(body, f.terms),
                    date = date,
                    path = direction,
                    number = address,
                    fullText = body,
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Files

    private fun searchFiles(f: SearchFilters): List<SearchResult> {
        val filesUri = MediaStore.Files.getContentUri("external")
        val args = ArrayList<String>()
        val clauses = arrayListOf("_size > 0")

        val pathCol = if (Build.VERSION.SDK_INT >= 29) "relative_path" else "_data"
        termsClause(f.terms, listOf("_display_name", pathCol, "mime_type"), args)?.let { clauses += it }

        if (!f.dateRange.isAny) {
            clauses += "date_modified >= ? AND date_modified <= ?"
            args += (f.dateRange.from / 1000).toString()
            args += (minOf(f.dateRange.to, Long.MAX_VALUE / 2) / 1000).toString()
        }
        when (f.category) {
            Category.IMAGES -> clauses += "media_type = 1"
            Category.AUDIO -> clauses += "media_type = 2"
            Category.VIDEOS -> clauses += "media_type = 3"
            Category.DOCUMENTS -> clauses += DOCUMENT_CLAUSE
            Category.APKS -> clauses += APK_CLAUSE
            else -> Unit
        }
        val order = when (f.sort) {
            SortMode.NEWEST -> "date_modified DESC"
            SortMode.OLDEST -> "date_modified ASC"
            SortMode.NAME -> "_display_name COLLATE NOCASE ASC"
            SortMode.SIZE -> "_size DESC"
        }

        val projection = arrayOf("_id", "_display_name", "_data", "mime_type", "_size", "date_modified", "media_type")
        val out = ArrayList<SearchResult>()
        resolver.query(filesUri, projection, clauses.joinToString(" AND "), args.toTypedArray(), order)?.use { c ->
            while (c.moveToNext() && out.size < LIMIT_FILES) {
                val id = c.getLong(0)
                val path = c.getString(2)
                val name = c.getString(1) ?: path?.substringAfterLast('/') ?: continue
                val mime = c.getString(3)
                val size = c.getLong(4)
                val date = c.getLong(5) * 1000
                val mediaType = c.getInt(6)
                val kind = kindFor(mediaType, mime, name)
                val uri: Uri = when (kind) {
                    Kind.IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    Kind.VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    Kind.AUDIO -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    else -> ContentUris.withAppendedId(filesUri, id)
                }
                val folder = path?.substringBeforeLast('/', "")
                    ?.removePrefix("/storage/emulated/0")?.ifEmpty { "/" } ?: ""
                out += SearchResult(
                    kind = kind,
                    title = name,
                    subtitle = folder,
                    date = date,
                    size = size,
                    uri = uri,
                    mime = mime,
                    path = path,
                )
            }
        }
        return out
    }

    private fun kindFor(mediaType: Int, mime: String?, name: String): Kind {
        val lower = name.lowercase()
        return when {
            mediaType == 1 || mime?.startsWith("image/") == true -> Kind.IMAGE
            mediaType == 3 || mime?.startsWith("video/") == true -> Kind.VIDEO
            mediaType == 2 || mime?.startsWith("audio/") == true -> Kind.AUDIO
            mime == APK_MIME || lower.endsWith(".apk") -> Kind.APK
            DOC_EXTENSIONS.any { lower.endsWith(it) } || mime?.startsWith("text/") == true -> Kind.DOCUMENT
            else -> Kind.FILE
        }
    }

    companion object {
        const val LIMIT_CONTACTS = 300
        const val LIMIT_MESSAGES = 400
        const val LIMIT_FILES = 1500

        private const val COL_CONTACT_ID = "contact_id"
        private const val COL_DISPLAY_NAME = "display_name"
        private const val COL_NUMBER = "data1"
        private const val COL_NORMALIZED_NUMBER = "data4"
        private const val COL_LOOKUP = "lookup"
        private const val COL_UPDATED = "contact_last_updated_timestamp"

        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val APK_CLAUSE = "(mime_type = '$APK_MIME' OR _display_name LIKE '%.apk')"

        private val DOC_EXTENSIONS = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv",
            ".rtf", ".odt", ".ods", ".odp", ".epub", ".json", ".xml", ".html", ".md",
        )
        private val DOCUMENT_CLAUSE = "(" + (
            listOf("mime_type LIKE 'text/%'", "mime_type LIKE 'application/pdf'",
                "mime_type LIKE 'application/msword'", "mime_type LIKE 'application/vnd.ms-%'",
                "mime_type LIKE 'application/vnd.openxmlformats%'", "mime_type LIKE 'application/vnd.oasis%'",
                "mime_type LIKE 'application/epub%'") +
                DOC_EXTENSIONS.map { "_display_name LIKE '%$it'" }
            ).joinToString(" OR ") + ")"

        fun lastDigits(number: String): String = number.filter { it.isDigit() }.takeLast(10)

        /** Part of a long message around the first matching word. */
        fun snippet(body: String, terms: List<String>): String {
            val flat = body.replace('\n', ' ')
            if (flat.length <= 140 || terms.isEmpty()) return flat
            val idx = terms.map { flat.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: return flat
            val start = maxOf(0, idx - 40)
            return (if (start > 0) "…" else "") + flat.substring(start)
        }
    }
}
