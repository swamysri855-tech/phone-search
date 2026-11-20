package com.phonesearch.app

import android.net.Uri

enum class Kind(val emoji: String, val label: String) {
    CONTACT("👤", "Contact"),
    MESSAGE("💬", "Message"),
    IMAGE("🖼️", "Photo"),
    VIDEO("🎬", "Video"),
    AUDIO("🎵", "Audio"),
    DOCUMENT("📄", "Document"),
    APK("📦", "APK"),
    FILE("📁", "File"),
}

enum class Category(val label: String) {
    ALL("అన్నీ"),
    CONTACTS("👤 Contacts"),
    MESSAGES("💬 Messages"),
    IMAGES("🖼️ Photos"),
    VIDEOS("🎬 Videos"),
    AUDIO("🎵 Audio"),
    DOCUMENTS("📄 Documents"),
    APKS("📦 APK"),
    FILES("📁 అన్ని Files"),
}

enum class SortMode(val label: String) {
    NEWEST("కొత్తవి ముందు"),
    OLDEST("పాతవి ముందు"),
    NAME("పేరు A → Z"),
    SIZE("పెద్ద సైజు ముందు"),
}

data class DateRange(val label: String, val from: Long, val to: Long) {
    val isAny get() = from <= 0L && to == Long.MAX_VALUE

    companion object {
        val ANY = DateRange("ఎప్పుడైనా", 0L, Long.MAX_VALUE)
    }
}

data class SearchFilters(
    val query: String,
    val category: Category,
    val dateRange: DateRange,
    val sort: SortMode,
) {
    val terms: List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun inRange(timeMs: Long) = timeMs in dateRange.from..dateRange.to
}

data class SearchResult(
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val date: Long,
    val size: Long = 0L,
    val uri: Uri? = null,
    val mime: String? = null,
    val path: String? = null,
    val number: String? = null,
    val fullText: String? = null,
) {
    val key: String get() = "$kind|${uri ?: number}|$date|$title"
    val isFile: Boolean get() = kind != Kind.CONTACT && kind != Kind.MESSAGE
}

data class SearchOutcome(
    val results: List<SearchResult>,
    val contacts: Int,
    val messages: Int,
    val files: Int,
)
