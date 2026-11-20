package com.phonesearch.app

import android.app.Activity
import android.os.Environment
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * A simple folder browser for the phone's internal storage, with shortcuts to common folders.
 * [extraButton] adds an optional third button (e.g. "search the whole phone").
 */
class FolderPicker(
    private val activity: Activity,
    private val confirmLabel: String,
    private val extraButton: Pair<String, () -> Unit>? = null,
    private val onPicked: (File) -> Unit,
) {
    private val root: File = Environment.getExternalStorageDirectory()

    private val shortcuts: List<Pair<String, String>> = listOf(
        "📷 Camera ఫోటోలు" to "DCIM/Camera",
        "📱 Screenshots" to "Pictures/Screenshots",
        "📱 Screenshots" to "DCIM/Screenshots",
        "🟢 WhatsApp Images" to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
        "🟢 WhatsApp Documents" to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
        "🟢 WhatsApp (మొత్తం)" to "Android/media/com.whatsapp/WhatsApp/Media",
        "⬇️ Download" to "Download",
        "🤖 AI_Sorted" to "Pictures/AI_Sorted",
    )

    fun show(dir: File = root) {
        val atRoot = dir.canonicalPath == root.canonicalPath
        val entries = ArrayList<Pair<String, () -> Unit>>()
        if (!atRoot) entries += "⬆️  వెనక్కి" to { show(dir.parentFile ?: root) }
        if (atRoot) {
            shortcuts.map { (label, rel) -> label to File(root, rel) }
                .filter { it.second.isDirectory }
                .distinctBy { it.first }
                .forEach { (label, f) -> entries += "⭐  $label" to { show(f) } }
        }
        entries += "➕  కొత్త folder తయారు చేయి" to { askNewFolder(dir) }
        (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
            .forEach { sub -> entries += "📁  ${sub.name}" to { show(sub) } }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("📂 " + displayPath(dir))
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setPositiveButton(confirmLabel) { _, _ -> onPicked(dir) }
            .setNegativeButton("రద్దు", null)
        extraButton?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
        builder.show()
    }

    private fun askNewFolder(parent: File) {
        val input = EditText(activity).apply {
            hint = "Folder పేరు"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = FrameLayout(activity).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("కొత్త folder")
            .setView(box)
            .setPositiveButton("తయారు చేయి") { _, _ ->
                val name = input.text.toString().trim().replace("/", "")
                val newDir = File(parent, name)
                if (name.isNotEmpty() && (newDir.isDirectory || newDir.mkdirs())) {
                    show(newDir)
                } else {
                    Toast.makeText(activity, "Folder తయారు చేయలేకపోయాను", Toast.LENGTH_SHORT).show()
                    show(parent)
                }
            }
            .setNegativeButton("వెనక్కి") { _, _ -> show(parent) }
            .show()
    }

    companion object {
        fun displayPath(dir: File): String {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val rel = dir.absolutePath.removePrefix(root)
            return if (rel.isEmpty()) "ఫోన్ storage" else rel
        }
    }
}
