package com.phonesearch.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.format.Formatter
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultAdapter(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    private val onClick: (SearchResult) -> Unit,
    private val onLongClick: (SearchResult) -> Unit,
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    private var items: List<SearchResult> = emptyList()
    private var terms: List<String> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    private val thumbs = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SearchResult>, searchTerms: List<String>) {
        items = list
        terms = searchTerms
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.icon)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val meta: TextView = v.findViewById(R.id.meta)
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        val ctx = h.itemView.context
        h.icon.text = r.kind.emoji
        h.title.text = highlight(r.title, ctx)
        h.subtitle.text = highlight(r.subtitle, ctx)
        h.subtitle.visibility = if (r.subtitle.isBlank()) View.GONE else View.VISIBLE

        val meta = ArrayList<String>()
        meta += r.kind.label
        if (r.date > 0) meta += dateFormat.format(Date(r.date))
        if (r.size > 0) meta += Formatter.formatShortFileSize(ctx, r.size)
        if (r.kind == Kind.MESSAGE && r.path != null) meta += r.path
        h.meta.text = meta.joinToString("  •  ")

        h.job?.cancel()
        h.thumb.setImageDrawable(null)
        h.thumb.visibility = View.GONE
        val uri = r.uri
        if ((r.kind == Kind.IMAGE || r.kind == Kind.VIDEO) && uri != null && Build.VERSION.SDK_INT >= 29) {
            val key = uri.toString()
            val cached = thumbs.get(key)
            if (cached != null) {
                h.thumb.setImageBitmap(cached)
                h.thumb.visibility = View.VISIBLE
            } else {
                h.job = scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            resolver.loadThumbnail(uri, Size(160, 160), null)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bmp != null) {
                        thumbs.put(key, bmp)
                        h.thumb.setImageBitmap(bmp)
                        h.thumb.visibility = View.VISIBLE
                    }
                }
            }
        }

        h.itemView.setOnClickListener { onClick(r) }
        h.itemView.setOnLongClickListener { onLongClick(r); true }
    }

    private fun highlight(text: String, ctx: android.content.Context): CharSequence {
        if (terms.isEmpty() || text.isEmpty()) return text
        val s = SpannableString(text)
        val color = ContextCompat.getColor(ctx, R.color.highlight)
        for (term in terms) {
            var idx = text.indexOf(term, ignoreCase = true)
            while (idx >= 0) {
                val end = idx + term.length
                s.setSpan(BackgroundColorSpan(color), idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(StyleSpan(Typeface.BOLD), idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                idx = text.indexOf(term, end, ignoreCase = true)
            }
        }
        return s
    }
}
