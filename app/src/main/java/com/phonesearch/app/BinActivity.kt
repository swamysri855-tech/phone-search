package com.phonesearch.app

import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/** రీసైకిల్ బిన్: తిరిగి తెచ్చుకోవడం, శాశ్వత డిలీట్, బిన్ ఖాళీ చేయడం. */
class BinActivity : AppCompatActivity() {

    private var items: List<TrashItem> = emptyList()
    private lateinit var adapter: BinAdapter
    private lateinit var info: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@BinActivity, R.color.bg))
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + dp(16), bars.top + dp(12), bars.right + dp(16), bars.bottom + dp(8))
            insets
        }

        root.addView(TextView(this).apply {
            text = "🗑 రీసైకిల్ బిన్"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@BinActivity, R.color.text))
        })
        info = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@BinActivity, R.color.text_muted))
        }
        root.addView(info)
        root.addView(MaterialButton(this).apply {
            text = "బిన్ ఖాళీ చేయి (శాశ్వతంగా)"
            setOnClickListener { confirmEmpty() }
        })

        adapter = BinAdapter()
        val list = ListView(this).apply {
            this.adapter = this@BinActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onItemClick(items[pos]) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items = Trash.list(this)
        adapter.notifyDataSetChanged()
        info.text = "${items.size} ఫైళ్లు • ${Trash.DAYS} రోజుల తర్వాత ఆటోమేటిక్‌గా శాశ్వతంగా పోతాయి"
    }

    private fun onItemClick(item: TrashItem) {
        val isApk = item.name.lowercase().endsWith(".apk")
        val warn = if (isApk) "\n\n⚠ ఇది APK ఫైల్. అనుమానాస్పదమైతే తిరిగి తేకండి — శాశ్వత డిలీట్ చేయండి." else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setMessage("అసలు చోటు:\n${item.origPath}$warn")
            .setPositiveButton("తిరిగి తెచ్చుకో") { _, _ ->
                if (isApk) confirmRestoreApk(item) else doRestore(item)
            }
            .setNegativeButton("శాశ్వత డిలీట్") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("శాశ్వతంగా డిలీట్ చేయాలా?")
                    .setMessage("${item.name}\n\nఇది తిరిగి రాదు.")
                    .setPositiveButton("అవును, డిలీట్") { _, _ ->
                        toast(if (Trash.deleteFromBin(this, item)) "శాశ్వతంగా డిలీట్ అయింది" else "డిలీట్ చేయలేకపోయాను")
                        reload()
                    }
                    .setNegativeButton("వద్దు", null)
                    .show()
            }
            .setNeutralButton("రద్దు", null)
            .show()
    }

    private fun confirmRestoreApk(item: TrashItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠ APK ఫైల్ తిరిగి తేవాలా?")
            .setMessage("ఇది వైరస్ కావచ్చు. మీకు పూర్తిగా నమ్మకం ఉంటేనే తిరిగి తెచ్చుకోండి.")
            .setPositiveButton("అవును, తెచ్చుకో") { _, _ -> doRestore(item) }
            .setNegativeButton("వద్దు", null)
            .show()
    }

    private fun doRestore(item: TrashItem) {
        toast(if (Trash.restore(this, item)) "తిరిగి వచ్చింది" else "తిరిగి తేలేకపోయాను")
        reload()
    }

    private fun confirmEmpty() {
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("బిన్ ఖాళీ చేయాలా?")
            .setMessage("${items.size} ఫైళ్లు శాశ్వతంగా పోతాయి. తిరిగి రావు.")
            .setPositiveButton("అవును, ఖాళీ చేయి") { _, _ ->
                thread {
                    val n = Trash.emptyBin(this)
                    runOnUiThread {
                        toast("$n ఫైళ్లు శాశ్వతంగా డిలీట్ అయ్యాయి")
                        reload()
                    }
                }
            }
            .setNegativeButton("వద్దు", null)
            .show()
    }

    private inner class BinAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = (convertView as? LinearLayout) ?: LinearLayout(this@BinActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(TextView(context).apply {
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(context, R.color.text))
                })
                addView(TextView(context).apply {
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                })
            }
            val t = items[position]
            val prefix = if (t.name.lowercase().endsWith(".apk")) "⚠ " else ""
            (v.getChildAt(0) as TextView).text = prefix + t.name
            (v.getChildAt(1) as TextView).text =
                "${Formatter.formatShortFileSize(this@BinActivity, t.size)} • ${Trash.daysLeft(t)} రోజులు మిగిలాయి"
            return v
        }
    }
}
