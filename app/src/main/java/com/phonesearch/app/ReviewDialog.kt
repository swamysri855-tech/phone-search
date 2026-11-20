package com.phonesearch.app

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen grid of the photos the AI picked. The person taps wrong ones to drop them,
 * then moves or copies only what they kept.
 */
class ReviewDialog(
    private val activity: AppCompatActivity,
    private val plans: List<PhotoPlan>,
    private val title: String,
    private val showGroup: Boolean,
    private val onDone: (kept: List<PhotoPlan>, move: Boolean) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val removed = HashSet<Int>()
    private val dialog = Dialog(activity, R.style.Theme_PhoneSearch)
    private lateinit var titleView: TextView
    private lateinit var moveButton: MaterialButton
    private lateinit var copyButton: MaterialButton
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun show() {
        dialog.setContentView(R.layout.dialog_review)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        titleView = dialog.findViewById(R.id.reviewTitle)
        moveButton = dialog.findViewById(R.id.reviewMove)
        copyButton = dialog.findViewById(R.id.reviewCopy)

        val grid = dialog.findViewById<RecyclerView>(R.id.reviewGrid)
        grid.layoutManager = GridLayoutManager(activity, 3)
        grid.adapter = Adapter()

        dialog.findViewById<View>(R.id.reviewCancel).setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
        moveButton.setOnClickListener { finish(move = true) }
        copyButton.setOnClickListener { finish(move = false) }
        dialog.setOnCancelListener { onCancel() }
        updateCounts()
        dialog.show()
    }

    private fun finish(move: Boolean) {
        val kept = plans.filterIndexed { i, _ -> i !in removed }
        dialog.dismiss()
        if (kept.isEmpty()) onCancel() else onDone(kept, move)
    }

    private fun updateCounts() {
        val kept = plans.size - removed.size
        titleView.text = "$title — $kept / ${plans.size}"
        moveButton.text = "📂 $kept తరలించు"
        moveButton.isEnabled = kept > 0
        copyButton.isEnabled = kept > 0
    }

    private fun showLarge(plan: PhotoPlan) {
        val image = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val big = Dialog(activity)
        big.setContentView(image)
        image.setOnClickListener { big.dismiss() }
        activity.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(plan.source, 1280) }
            image.setImageBitmap(bmp)
        }
        big.show()
    }

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.reviewImage)
        val label: TextView = v.findViewById(R.id.reviewLabel)
        val mark: TextView = v.findViewById(R.id.reviewMark)
        var job: Job? = null
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = plans.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_review, parent, false)
            if (parent.width > 0) v.layoutParams.height = parent.width / 3 - 4
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val plan = plans[position]
            val off = position in removed
            h.label.text = if (showGroup) "${plan.group.folder} • ${plan.labels.firstOrNull().orEmpty()}"
            else plan.labels.take(2).joinToString(", ")
            h.mark.visibility = if (off) View.VISIBLE else View.GONE
            h.image.alpha = if (off) 0.3f else 1f

            h.job?.cancel()
            val key = plan.source.absolutePath
            val cached = thumbs.get(key)
            h.image.setImageBitmap(cached)
            if (cached == null) {
                h.job = activity.lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { decode(plan.source, 300) }
                    if (bmp != null) {
                        thumbs.put(key, bmp)
                        h.image.setImageBitmap(bmp)
                    }
                }
            }

            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (!removed.remove(pos)) removed += pos
                notifyItemChanged(pos)
                updateCounts()
            }
            h.itemView.setOnLongClickListener {
                showLarge(plan)
                true
            }
        }
    }

    companion object {
        fun decode(file: File, maxSide: Int): Bitmap? {
            return try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    // Respects the photo's rotation (EXIF), so pictures aren't shown sideways.
                    android.media.ThumbnailUtils.createImageThumbnail(file, android.util.Size(maxSide, maxSide), null)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
