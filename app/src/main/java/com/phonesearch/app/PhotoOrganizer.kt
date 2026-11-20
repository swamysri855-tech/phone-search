package com.phonesearch.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** What the AI saw in one photo, and where its renamed copy will go. */
data class PhotoPlan(val source: File, val group: PhotoGroup, val labels: List<String>, val newName: String)

enum class PhotoGroup(val folder: String, val telugu: String, val keywords: List<String>) {
    PLANTS("Plants_Herbs", "🌿 మొక్కలు / మూలికలు / పువ్వులు", listOf(
        "plant", "flower", "leaf", "tree", "petal", "garden", "herb", "moss", "grass", "bonsai",
        "flowerpot", "houseplant", "branch", "bamboo", "cactus", "lily", "rose", "sunflower")),
    FOOD("Food", "🍲 ఆహారం", listOf(
        "food", "fruit", "vegetable", "cake", "bread", "pizza", "dessert", "cuisine", "dish", "meal",
        "cookie", "juice", "drink", "coffee", "tea", "rice", "noodle", "soup", "salad", "candy",
        "chocolate", "icing", "pasta", "sushi", "hot dog", "burger", "egg", "cheese", "bakery")),
    PEOPLE("People", "🧑 మనుషులు", listOf(
        "selfie", "smile", "beard", "hand", "skin", "hair", "eyelash", "child", "baby", "crowd",
        "person", "jeans", "dress", "sari", "turban", "glasses", "moustache", "team", "dude", "bride")),
    ANIMALS("Animals", "🐄 జంతువులు / పక్షులు", listOf(
        "animal", "dog", "cat", "bird", "horse", "cattle", "insect", "butterfly", "fish", "pet",
        "bull", "cow", "goat", "chicken", "duck", "monkey", "elephant", "bee", "spider", "reptile")),
    DOCUMENTS("Documents", "📄 Documents / కాగితాలు", listOf(
        "paper", "poster", "receipt", "menu", "newspaper", "book", "comics", "text", "handwriting",
        "document", "whiteboard", "calendar", "map", "sign")),
    VEHICLES("Vehicles", "🚗 వాహనాలు", listOf(
        "car", "bicycle", "motorcycle", "bus", "truck", "vehicle", "boat", "train", "airplane",
        "wheel", "tire", "scooter", "auto", "jet")),
    NATURE("Nature", "🏞️ ప్రకృతి", listOf(
        "sky", "mountain", "beach", "sea", "lake", "river", "cloud", "sunset", "waterfall", "desert",
        "forest", "rainbow", "star", "field", "rock", "snow", "canyon", "lightning", "pond")),
    PLACES("Buildings_Places", "🛕 భవనాలు / ప్రదేశాలు", listOf(
        "building", "temple", "church", "mosque", "house", "bridge", "tower", "skyscraper", "monument",
        "room", "shelf", "kitchen", "stadium", "castle", "fountain", "road", "pier")),
    EVENTS("Events", "🎉 వేడుకలు", listOf(
        "party", "wedding", "fireworks", "concert", "balloon", "christmas", "event", "stage", "flag")),
    SCREENSHOTS("Screenshots", "📱 Screenshots", emptyList()),
    OTHERS("Others", "📦 ఇతరాలు", emptyList());

    companion object {
        fun forLabels(labels: List<String>): PhotoGroup {
            // Labels come best-first; the first one that matches a group decides.
            for (label in labels) {
                val l = label.lowercase()
                values().firstOrNull { g -> g.keywords.any { l.contains(it) } }?.let { return it }
            }
            return OTHERS
        }
    }
}

/** Looks at photos with an on-device AI model (no internet) and plans renamed, grouped copies. */
class PhotoOrganizer {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    val outputRoot: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AI_Sorted"
    )

    suspend fun analyze(items: List<SearchResult>, onProgress: (done: Int, total: Int) -> Unit): List<PhotoPlan> =
        withContext(Dispatchers.Default) {
            val out = ArrayList<PhotoPlan>()
            items.forEachIndexed { i, r ->
                coroutineContext.ensureActive()
                try {
                    analyzeOne(r)?.let { out += it }
                } catch (_: Exception) {
                }
                withContext(Dispatchers.Main) { onProgress(i + 1, items.size) }
            }
            out
        }

    private fun analyzeOne(r: SearchResult): PhotoPlan? {
        val file = File(r.path ?: return null)
        if (!file.canRead() || file.absolutePath.startsWith(outputRoot.absolutePath)) return null
        val bitmap = decodeSmall(file) ?: return null
        val labels = try {
            Tasks.await(labeler.process(InputImage.fromBitmap(bitmap, 0)))
                .sortedByDescending { it.confidence }
                .map { it.text }
        } finally {
            bitmap.recycle()
        }
        val group = if (file.absolutePath.contains("screenshot", ignoreCase = true)) {
            PhotoGroup.SCREENSHOTS
        } else {
            PhotoGroup.forLabels(labels)
        }
        val words = labels.take(2).map { clean(it) }.filter { it.isNotEmpty() }.ifEmpty { listOf("Photo") }
        val ext = file.name.substringAfterLast('.', "jpg")
        val name = words.joinToString("_") + "_" + dateFormat.format(Date(file.lastModified())) + "." + ext
        return PhotoPlan(file, group, labels, name)
    }

    /** Decode a small version (about 640px) — enough for the AI, and light on memory. */
    private fun decodeSmall(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun clean(label: String) =
        label.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').replaceFirstChar { it.uppercase() }

    fun close() = labeler.close()
}
