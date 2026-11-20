package com.phonesearch.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var chipGroup: ChipGroup
    private lateinit var dateButton: MaterialButton
    private lateinit var sortButton: MaterialButton
    private lateinit var permBanner: View
    private lateinit var permText: TextView
    private lateinit var permButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: ResultAdapter

    private val repo by lazy { SearchRepository(applicationContext) }

    private var category = Category.ALL
    private var dateRange = DateRange.ANY
    private var sortMode = SortMode.NEWEST
    private var searchJob: Job? = null
    private var askedOnce = false

    private val runtimePermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermissionBanner()
            runSearch(0)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        searchInput = findViewById(R.id.searchInput)
        chipGroup = findViewById(R.id.chipGroup)
        dateButton = findViewById(R.id.dateButton)
        sortButton = findViewById(R.id.sortButton)
        permBanner = findViewById(R.id.permBanner)
        permText = findViewById(R.id.permText)
        permButton = findViewById(R.id.permButton)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)

        adapter = ResultAdapter(lifecycleScope, contentResolver, ::openResult, ::copyResult)
        findViewById<RecyclerView>(R.id.results).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        setupChips()
        searchInput.doAfterTextChanged { runSearch(300) }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                runSearch(0)
                true
            } else false
        }
        dateButton.setOnClickListener { showDateDialog() }
        sortButton.setOnClickListener { showSortDialog() }
        permButton.setOnClickListener { requestMissingAccess() }

        if (missingRuntimePermissions().isNotEmpty()) {
            askedOnce = true
            permissionLauncher.launch(runtimePermissions)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        runSearch(0)
    }

    // ---------------------------------------------------------------- UI setup

    private fun setupChips() {
        Category.values().forEach { cat ->
            val chip = layoutInflater.inflate(R.layout.item_chip, chipGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = cat.label
            chip.tag = cat
            chipGroup.addView(chip)
            if (cat == category) chip.isChecked = true
        }
        chipGroup.setOnCheckedStateChangeListener { group, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            category = group.findViewById<Chip>(id).tag as Category
            runSearch(0)
        }
    }

    private fun showSortDialog() {
        val modes = SortMode.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("ఎలా వరుసలో చూపాలి?")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(sortMode)) { d, which ->
                sortMode = modes[which]
                sortButton.text = "↕ ${sortMode.label}"
                d.dismiss()
                runSearch(0)
            }
            .show()
    }

    private fun showDateDialog() {
        val options = arrayOf(
            "ఎప్పుడైనా", "ఈ రోజు", "నిన్న", "గత 7 రోజులు", "గత 30 రోజులు",
            "ఈ సంవత్సరం", "గత సంవత్సరం", "తేదీలు ఎంచుకోండి (Custom)…",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("తేదీ ప్రకారం వెతకండి")
            .setItems(options) { _, which ->
                val now = System.currentTimeMillis()
                when (which) {
                    0 -> setDateRange(DateRange.ANY)
                    1 -> setDateRange(DateRange("ఈ రోజు", startOfDay(0), now))
                    2 -> setDateRange(DateRange("నిన్న", startOfDay(-1), startOfDay(0) - 1))
                    3 -> setDateRange(DateRange("గత 7 రోజులు", startOfDay(-6), now))
                    4 -> setDateRange(DateRange("గత 30 రోజులు", startOfDay(-29), now))
                    5 -> setDateRange(DateRange("ఈ సంవత్సరం", startOfYear(0), now))
                    6 -> setDateRange(DateRange("గత సంవత్సరం", startOfYear(-1), startOfYear(0) - 1))
                    7 -> showCustomRangePicker()
                }
            }
            .show()
    }

    private fun showCustomRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("మొదటి తేదీ – చివరి తేదీ")
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            val from = sel.first ?: return@addOnPositiveButtonClickListener
            val to = (sel.second ?: from) + DAY_MS - 1
            val fmt = SimpleDateFormat("dd MMM yy", Locale.getDefault())
            setDateRange(DateRange("${fmt.format(Date(from))} – ${fmt.format(Date(to))}", from, to))
        }
        picker.show(supportFragmentManager, "range")
    }

    private fun setDateRange(range: DateRange) {
        dateRange = range
        dateButton.text = "📅 ${range.label}"
        runSearch(0)
    }

    private fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfYear(offsetYears: Int): Long = Calendar.getInstance().apply {
        add(Calendar.YEAR, offsetYears)
        set(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---------------------------------------------------------------- Search

    private fun runSearch(debounceMs: Long) {
        searchJob?.cancel()
        val filters = SearchFilters(searchInput.text?.toString().orEmpty(), category, dateRange, sortMode)
        searchJob = lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            progress.visibility = View.VISIBLE
            val outcome = repo.search(filters)
            progress.visibility = View.GONE
            adapter.submit(outcome.results, filters.terms)
            statusText.text = buildStatus(outcome, filters)
        }
    }

    private fun buildStatus(o: SearchOutcome, f: SearchFilters): String {
        val head = if (f.terms.isEmpty()) "ఇటీవలివి" else "\"${f.query.trim()}\""
        if (o.results.isEmpty()) return "$head — ఏమీ దొరకలేదు"
        val parts = ArrayList<String>()
        if (o.contacts > 0) parts += "${o.contacts} contacts"
        if (o.messages > 0) parts += "${o.messages} messages"
        if (o.files > 0) parts += "${o.files} files"
        return "$head — ${o.results.size} ఫలితాలు (${parts.joinToString(", ")})"
    }

    // ---------------------------------------------------------------- Permissions

    private fun missingRuntimePermissions() = runtimePermissions.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun needsAllFilesAccess() =
        Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()

    private fun updatePermissionBanner() {
        val missing = missingRuntimePermissions()
        when {
            missing.isNotEmpty() -> {
                val names = missing.map {
                    when (it) {
                        Manifest.permission.READ_CONTACTS -> "Contacts"
                        Manifest.permission.READ_SMS -> "Messages"
                        else -> "Photos/Files"
                    }
                }.distinct()
                permText.text = "${names.joinToString(", ")} వెతకడానికి అనుమతి కావాలి."
                permBanner.visibility = View.VISIBLE
            }
            needsAllFilesAccess() -> {
                permText.text = "PDF, Documents, APK, ZIP లాంటి అన్ని files వెతకడానికి \"All files access\" ఆన్ చేయండి."
                permBanner.visibility = View.VISIBLE
            }
            else -> permBanner.visibility = View.GONE
        }
    }

    private fun requestMissingAccess() {
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            val permanentlyDenied = askedOnce && missing.none { shouldShowRequestPermissionRationale(it) }
            if (permanentlyDenied) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Permissions లో అన్నీ Allow చేయండి", Toast.LENGTH_LONG).show()
            } else {
                askedOnce = true
                permissionLauncher.launch(missing.toTypedArray())
            }
        } else if (needsAllFilesAccess()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    // ---------------------------------------------------------------- Actions

    private fun openResult(r: SearchResult) {
        val intent = when (r.kind) {
            Kind.CONTACT -> Intent(Intent.ACTION_VIEW, r.uri ?: Uri.parse("tel:${r.number}"))
            Kind.MESSAGE -> Intent(Intent.ACTION_VIEW, Uri.parse("sms:${r.number}"))
            else -> {
                val uri = fileUriFor(r) ?: return
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, r.mime ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "దీన్ని తెరవడానికి app లేదు", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fileUriFor(r: SearchResult): Uri? {
        val isMedia = r.kind == Kind.IMAGE || r.kind == Kind.VIDEO || r.kind == Kind.AUDIO
        if (!isMedia) {
            val file = r.path?.let { File(it) }
            if (file != null && file.canRead()) {
                try {
                    return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } catch (_: Exception) {
                }
            }
        }
        return r.uri
    }

    private fun copyResult(r: SearchResult) {
        val text = when (r.kind) {
            Kind.CONTACT -> "${r.title} ${r.number}"
            Kind.MESSAGE -> r.subtitle
            else -> r.path ?: r.title
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Phone Search", text))
        Toast.makeText(this, "కాపీ అయ్యింది ✅", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
