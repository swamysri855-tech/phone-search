package com.phonesearch.app

import android.os.Environment
import android.webkit.MimeTypeMap
import java.io.File

/** Kinds of WhatsApp media folders, matched by the end of the folder name. */
enum class WaType(val label: String, val suffix: String, val onByDefault: Boolean) {
    VIDEO("🎬 Videos", "Video", true),
    AUDIO("🎵 Audio (పాటలు / ఆడియో)", "Audio", true),
    VOICE("🎤 Voice notes", "Voice Notes", true),
    DOCS("📄 Documents (PDF, APK…)", "Documents", true),
    IMAGES("🖼️ ఫోటోలు", "Images", false),
    GIFS("🎞️ GIFs", "Animated Gifs", false),
    STICKERS("😀 Stickers", "Stickers", false),
    STATUSES("👁️ చూసిన Statuses", ".Statuses", false),
}

/**
 * Finds old WhatsApp / WhatsApp Business media files. Files you sent live in "Sent"
 * subfolders, so leaving those out keeps only what others sent you (auto-downloads).
 */
object WhatsAppCleaner {

    private val MEDIA_DIRS = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
        "WhatsApp/Media",
        "WhatsApp Business/Media",
    )
    private val JUNK = setOf("tmp", "chk", "enc", "nomedia", "db", "log", "lock")

    fun scan(types: Set<WaType>, includeSent: Boolean, before: Long): List<SearchResult> {
        val root = Environment.getExternalStorageDirectory()
        val rootPath = root.absolutePath
        val out = ArrayList<SearchResult>()
        for (mediaRel in MEDIA_DIRS) {
            val media = File(root, mediaRel)
            val subs = media.listFiles { f -> f.isDirectory } ?: continue
            for (sub in subs) {
                val type = types.firstOrNull { t ->
                    if (t == WaType.STATUSES) sub.name == t.suffix else sub.name.endsWith(t.suffix)
                } ?: continue
                sub.walkTopDown()
                    .onEnter { d ->
                        d == sub || (!d.name.startsWith(".") && (includeSent || !d.name.equals("Sent", true)))
                    }
                    .filter { it.isFile }
                    .forEach { f ->
                        val ext = f.name.substringAfterLast('.', "").lowercase()
                        val modified = f.lastModified()
                        if (!f.name.startsWith(".") && ext !in JUNK && !ext.startsWith("crypt") && modified < before) {
                            val size = f.length()
                            if (size > 0) {
                                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                                    ?: if (ext == "opus") "audio/ogg" else null
                                out += SearchResult(
                                    kind = kindFor(mime, ext),
                                    title = f.name,
                                    subtitle = (f.parent ?: "").removePrefix(rootPath),
                                    date = modified,
                                    size = size,
                                    uri = null,
                                    mime = mime,
                                    path = f.absolutePath,
                                )
                            }
                        }
                    }
            }
        }
        return out.sortedByDescending { it.size }
    }

    private fun kindFor(mime: String?, ext: String): Kind = when {
        mime?.startsWith("image/") == true -> Kind.IMAGE
        mime?.startsWith("video/") == true -> Kind.VIDEO
        mime?.startsWith("audio/") == true -> Kind.AUDIO
        ext == "apk" -> Kind.APK
        else -> Kind.DOCUMENT
    }

    /** For the summary: which WhatsApp type a result came from. */
    fun typeOf(r: SearchResult): String {
        val folder = r.subtitle
        return WaType.values().firstOrNull { t ->
            if (t == WaType.STATUSES) folder.contains("/.Statuses") else folder.split('/').any { it.endsWith(t.suffix) }
        }?.label ?: "📁 ఇతరాలు"
    }
}
