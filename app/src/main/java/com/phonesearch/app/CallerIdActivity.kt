package com.phonesearch.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

/**
 * ఆఫ్‌లైన్ కాలర్ ID సెటప్: కాల్ వచ్చినప్పుడు కాంటాక్ట్ పేరు (లేదా CSV జాబితాలోని పేరు) స్క్రీన్ మీద చూపిస్తుంది.
 */
class CallerIdActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
            syncContacts(silent = true)
        }

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    private val csvLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                thread {
                    val n = try { Contacts.importCsv(this, uri) } catch (e: Exception) { -1 }
                    runOnUiThread {
                        refresh()
                        toast(if (n >= 0) "$n కొత్త పేర్లు జాబితాలో చేరాయి" else "ఫైల్ చదవలేకపోయాను")
                    }
                }
            }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun btn(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@CallerIdActivity, R.color.bg))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + dp(16), bars.top + dp(12), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        col.addView(TextView(this).apply {
            text = "📞 కాలర్ ID (ఆఫ్‌లైన్)"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@CallerIdActivity, R.color.text))
        })
        col.addView(TextView(this).apply {
            text = "కాల్ వచ్చినప్పుడు కాంటాక్ట్స్ (లేదా మీరు ఇచ్చిన CSV జాబితా) నుంచి పేరు కనుక్కుని స్క్రీన్ పైన చూపిస్తుంది. " +
                "ఇంటర్నెట్ వాడదు. తెలియని నంబర్ల పేర్లు కావాలంటే CSV జాబితా (పేరు,నంబర్) దిగుమతి చేయండి."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@CallerIdActivity, R.color.text_muted))
            setPadding(0, dp(6), 0, dp(10))
        })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@CallerIdActivity, R.color.text))
        }
        col.addView(status)

        col.addView(btn("1️⃣ అనుమతులు ఇవ్వండి (కాంటాక్ట్స్, ఫోన్)") { askRuntimePerms() })
        col.addView(btn("2️⃣ ఇతర యాప్‌ల పైన చూపడానికి అనుమతి") { askOverlay() })
        col.addView(btn("3️⃣ కాలర్ ID యాప్‌గా ఈ యాప్‌ను ఎంచుకోండి") { askRole() })
        col.addView(btn("🔄 కాంటాక్ట్స్ మళ్లీ సింక్ చేయి") { syncContacts(silent = false) })
        col.addView(btn("📥 CSV జాబితా దిగుమతి (పేరు,నంబర్)") { csvLauncher.launch(arrayOf("*/*")) })

        col.addView(TextView(this).apply {
            text = "గమనిక: వన్‌ప్లస్ ఫోన్‌లో Settings → Battery → ఈ యాప్‌కి 'Don't optimize' పెట్టండి. " +
                "ట్రూకాలర్ కాలర్ ID రోల్ లో ఉంటే, 3️⃣ నొక్కినప్పుడు ఈ యాప్‌ను ఎంచుకోండి."
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@CallerIdActivity, R.color.text_muted))
            setPadding(0, dp(14), 0, 0)
        })
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private val runtimePerms = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasRole(): Boolean =
        Build.VERSION.SDK_INT >= 29 &&
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    private fun mark(ok: Boolean) = if (ok) "✔" else "✖"

    private fun refresh() {
        status.text = "${mark(granted(Manifest.permission.READ_CONTACTS))} కాంటాక్ట్స్ అనుమతి\n" +
            "${mark(granted(Manifest.permission.READ_PHONE_STATE))} ఫోన్ స్థితి అనుమతి\n" +
            "${mark(Settings.canDrawOverlays(this))} ఇతర యాప్‌ల పైన చూపడం\n" +
            "${mark(hasRole())} కాలర్ ID యాప్‌గా ఎంచుకున్నారు\n" +
            "పేర్ల జాబితాలో నంబర్లు: ${Db.get(this).numberCount()}"
    }

    private fun askRuntimePerms() {
        val missing = runtimePerms.filter { !granted(it) }
        if (missing.isEmpty()) toast("ఈ అనుమతులు ఇప్పటికే ఉన్నాయి ✔") else permLauncher.launch(missing.toTypedArray())
    }

    private fun askOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("ఇప్పటికే అనుమతి ఉంది ✔")
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun askRole() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("ఈ ఫీచర్‌కి Android 10 లేదా ఆపై వెర్షన్ కావాలి"); return
        }
        if (hasRole()) {
            toast("ఇప్పటికే కాలర్ ID యాప్‌గా ఉంది ✔"); return
        }
        val rm = getSystemService(RoleManager::class.java)
        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    private fun syncContacts(silent: Boolean) {
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            if (!silent) toast("ముందు కాంటాక్ట్స్ అనుమతి ఇవ్వండి")
            return
        }
        thread {
            val n = try { Contacts.sync(this) } catch (e: Exception) { -1 }
            runOnUiThread {
                refresh()
                if (!silent) toast(if (n >= 0) "$n కాంటాక్ట్ నంబర్లు సింక్ అయ్యాయి" else "సింక్ విఫలమైంది")
            }
        }
    }
}
