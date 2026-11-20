package com.phonesearch.app

import android.app.Activity
import android.os.Environment
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** A simple folder browser for the phone's internal storage. */
class FolderPicker(
    private val activity: Activity,
    private val confirmLabel: String,
    private val onPicked: (File) -> Unit,
) {
    private val root: File = Environment.getExternalStorageDirectory()

    fun show(dir: File = root) {
        val subs = (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
        val atRoot = dir.canonicalPath == root.canonicalPath
        val offset = if (atRoot) 0 else 1
        val labels = ArrayList<String>()
        if (!atRoot) labels += "⬆️  వెనక్కి"
        labels += "➕  కొత్త folder తయారు చేయి"
        subs.forEach { labels += "📁  ${it.name}" }

        MaterialAlertDialogBuilder(activity)
            .setTitle("📂 " + displayPath(dir))
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    !atRoot && which == 0 -> show(dir.parentFile ?: root)
                    which == offset -> askNewFolder(dir)
                    else -> show(subs[which - offset - 1])
                }
            }
            .setPositiveButton(confirmLabel) { _, _ -> onPicked(dir) }
            .setNegativeButton("రద్దు", null)
            .show()
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

    private fun displayPath(dir: File): String {
        val rel = dir.absolutePath.removePrefix(root.absolutePath)
        return "ఫోన్ storage" + rel.ifEmpty { "" }
    }
}
