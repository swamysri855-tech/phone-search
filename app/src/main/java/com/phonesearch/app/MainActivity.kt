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
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
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
    private lateinit var selectionBar: View
    private lateinit var selCount: TextView
    private lateinit var backCallback: OnBackPressedCallback

    private val repo by lazy { SearchRepository(applicationContext) }

    private var category = Category.ALL
    private var dateRange = DateRange.ANY
    private var sortMode = SortMode.NEWEST
    private var folder: File? = null
    private var cleanerResults: List<SearchResult>? = null
    private lateinit var folderButton: MaterialButton
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
            if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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

        adapter = ResultAdapter(lifecycleScope, contentResolver, ::openResult, ::onSelectionChanged)
        setupSelectionBar()
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
        findViewById<View>(R.id.aiButton).setOnClickListener { aiButtonClicked() }
        folderButton = findViewById(R.id.folderButton)
        folderButton.setOnClickListener { chooseSearchFolder() }
        findViewById<View>(R.id.cleanerButton).setOnClickListener { startCleaner() }
        findViewById<View>(R.id.cleanerExit).setOnClickListener {
            exitCleanerMode()
            runSearch(0)
        }
        findViewById<View>(R.id.binButton).setOnClickListener { startActivity(Intent(this, BinActivity::class.java)) }
        findViewById<View>(R.id.callerIdButton).setOnClickListener { startActivity(Intent(this, CallerIdActivity::class.java)) }
        permButton.setOnClickListener { requestMissingAccess() }

        if (missingRuntimePermissions().isNotEmpty()) {
            askedOnce = true
            permissionLauncher.launch(runtimePermissions)
        }

        // 30 రోజులు దాటిన బిన్ ఫైళ్లను శాశ్వతంగా తొలగిస్తుంది
        lifecycleScope.launch(Dispatchers.IO) { Trash.purgeOld(applicationContext) }
    }

    override fun onResume() {
        super.onResume()
        repo.refreshFolderIndex()
        updatePermissionBanner()
        runSearch(0)
        // కాలర్ ID కోసం కాంటాక్ట్స్‌ను లోకల్ జాబితాలోకి తాజాగా కాపీ చేస్తుంది
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch(Dispatchers.IO) { runCatching { Contacts.sync(applicationContext) } }
        }
    }

    // ---------------------------------------------------------------- Selection

    private fun setupSelectionBar() {
        selectionBar = findViewById(R.id.selectionBar)
        selCount = findViewById(R.id.selCount)
        findViewById<View>(R.id.selClose).setOnClickListener { adapter.clearSelection() }
        findViewById<View>(R.id.selAll).setOnClickListener { adapter.selectAll() }
        findViewById<View>(R.id.selAi).setOnClickListener { aiButtonClicked() }
        findViewById<View>(R.id.selDelete).setOnClickListener { startDelete() }
        findViewById<View>(R.id.selMove).setOnClickListener { startTransfer(move = true) }
        findViewById<View>(R.id.selCopyTo).setOnClickListener { startTransfer(move = false) }
        findViewById<View>(R.id.selShare).setOnClickListener { shareSelected() }
        findViewById<View>(R.id.selCopyText).setOnClickListener { copyText(adapter.selectedItems()) }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = adapter.clearSelection()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    private fun onSelectionChanged(count: Int) {
        selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        val bytes = if (count > 0) adapter.selectedItems().sumOf { it.size } else 0L
        selCount.text = "$count ఎంచుకున్నారు" +
            if (bytes > 0) " • ${android.text.format.Formatter.formatShortFileSize(this, bytes)}" else ""
        backCallback.isEnabled = count > 0
    }

    // ---------------------------------------------------------------- Delete (recycle bin / permanent)

    private fun startDelete() {
        val files = adapter.selectedItems().filter { it.isFile && it.path != null }
        if (files.isEmpty()) {
            toast("Files మాత్రమే డిలీట్ చేయగలం. Contacts / Messages ని ఇక్కడ డిలీట్ చేయలేం.")
            return
        }
        if (!canWriteFiles()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("అనుమతి కావాలి")
                .setMessage("Files డిలీట్ చేయడానికి \"All files access\" ఆన్ చేయాలి.")
                .setPositiveButton("ఆన్ చేయి") { _, _ -> requestMissingAccess() }
                .setNegativeButton("రద్దు", null)
                .show()
            return
        }
        val apks = files.count { it.path!!.lowercase().endsWith(".apk") }
        val warn = if (apks > 0) "\n\n⚠ వీటిలో $apks APK ఫైళ్లు ఉన్నాయి. వైరస్ అనుమానం ఉంటే 'శాశ్వత డిలీట్' ఎంచుకోండి — బిన్‌లో కూడా ఉండనివ్వదు." else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("🗑 ${files.size} file(s) డిలీట్ చేయాలా?")
            .setMessage(
                "• బిన్‌కి పంపు: ${Trash.DAYS} రోజులు బిన్‌లో ఉంటాయి, తిరిగి తెచ్చుకోవచ్చు.\n" +
                    "• శాశ్వత డిలీట్: బిన్ లేకుండా పూర్తిగా పోతాయి, తిరిగి రావు.$warn"
            )
            .setPositiveButton("బిన్‌కి పంపు") { _, _ -> doDelete(files, permanent = false) }
            .setNeutralButton("శాశ్వత డిలీట్") { _, _ -> confirmPermanentDelete(files) }
            .setNegativeButton("వద్దు", null)
            .show()
    }

    private fun confirmPermanentDelete(files: List<SearchResult>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠ శాశ్వతంగా డిలీట్ చేయాలా?")
            .setMessage("${files.size} file(s) పూర్తిగా పోతాయి. బిన్‌లోకి వెళ్లవు, తిరిగి రావు.")
            .setPositiveButton("అవును, శాశ్వతంగా డిలీట్") { _, _ -> doDelete(files, permanent = true) }
            .setNegativeButton("వద్దు", null)
            .show()
    }

    private fun doDelete(files: List<SearchResult>, permanent: Boolean) {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                for (r in files) {
                    val f = File(r.path ?: continue)
                    val done = if (permanent) Trash.deleteForever(applicationContext, f) else Trash.moveToBin(applicationContext, f)
                    if (done) ok++ else failed++
                }
            }
            delay(700) // MediaStore కి మార్పు తెలియడానికి కొద్దిగా సమయం
            progress.visibility = View.GONE
            val where = if (permanent) "శాశ్వతంగా డిలీట్ చేశాను" else "బిన్‌కి పంపాను (${Trash.DAYS} రోజులు)"
            toast("✅ $ok file(s) $where" + if (failed > 0) " • ❌ $failed కాలేదు" else "")
            repo.refreshFolderIndex()
            adapter.clearSelection()
            runSearch(0)
        }
    }

    private fun startTransfer(move: Boolean) {
        val files = adapter.selectedItems().filter { it.isFile }
        if (files.isEmpty()) {
            toast("Files మాత్రమే తరలించగలం. Contacts / Messages కి 📤 పంపు లేదా 📋 కాపీ వాడండి.")
            return
        }
        if (!canWriteFiles()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("అనుమతి కావాలి")
                .setMessage("Files తరలించడానికి / కాపీ చేయడానికి \"All files access\" ఆన్ చేయాలి.")
                .setPositiveButton("ఆన్ చేయి") { _, _ -> requestMissingAccess() }
                .setNegativeButton("రద్దు", null)
                .show()
            return
        }
        val label = if (move) "ఇక్కడికి తరలించు ✅" else "ఇక్కడికి కాపీ ✅"
        FolderPicker(this, label) { dest -> confirmTransfer(files, dest, move) }.show()
    }

    private fun confirmTransfer(files: List<SearchResult>, dest: File, move: Boolean) {
        val action = if (move) "తరలించాలా" else "కాపీ చేయాలా"
        val where = dest.absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath).ifEmpty { "/" }
        MaterialAlertDialogBuilder(this)
            .setMessage("${files.size} file(s) ని \"$where\" కి $action?")
            .setPositiveButton("అవును") { _, _ -> doTransfer(files, dest, move) }
            .setNegativeButton("వద్దు", null)
            .show()
    }

    private fun doTransfer(files: List<SearchResult>, dest: File, move: Boolean) {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val res = FileOps.transfer(applicationContext, files, dest, move)
            progress.visibility = View.GONE
            val verb = if (move) "తరలించాను" else "కాపీ చేశాను"
            val msg = buildString {
                append("✅ ${res.done} file(s) $verb")
                if (res.skipped > 0) append(" • ${res.skipped} వదిలేశాను (అక్కడే ఉన్నాయి / దొరకలేదు)")
                if (res.failed > 0) append(" • ❌ ${res.failed} కాలేదు")
            }
            toast(msg)
            repo.refreshFolderIndex()
            adapter.clearSelection()
            runSearch(0)
        }
    }

    // ---------------------------------------------------------------- AI photo sorting

    /** Big top button: sort the selected photos, or every photo currently shown. */
    private fun aiButtonClicked() {
        val source = if (adapter.inSelectionMode) adapter.selectedItems() else adapter.currentItems()
        val photos = source.filter { it.kind == Kind.IMAGE && it.path != null }
        if (photos.isEmpty()) {
            toast("ఇక్కడ ఫోటోలు లేవు. 🖼️ Photos chip నొక్కి మళ్ళీ ప్రయత్నించండి.")
            return
        }
        if (!canWriteFiles()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("అనుమతి కావాలి")
                .setMessage("ఫోటోలను చదివి folders లో పెట్టడానికి \"All files access\" ఆన్ చేయాలి.")
                .setPositiveButton("ఆన్ చేయి") { _, _ -> requestMissingAccess() }
                .setNegativeButton("రద్దు", null)
                .show()
            return
        }
        val which = if (adapter.inSelectionMode) "మీరు ఎంచుకున్న" else "ఇప్పుడు కనిపిస్తున్న"
        MaterialAlertDialogBuilder(this)
            .setTitle("🤖 ${photos.size} ఫోటోలను AI తో వేరు చేయాలా?")
            .setMessage(
                "$which ${photos.size} ఫోటోలను AI చూస్తుంది.\n\n" +
                    "🌿 మొక్కలు మాత్రమే: మొక్కలు, ఆకులు, పువ్వులు, పండ్లు, బెరడు, తీగలు, వేర్లు, విత్తనాలు ఉన్న ఫోటోలు మాత్రమే " +
                    "Plants_Herbs folder లోకి. మిగతావి ఎక్కడివక్కడే ఉంటాయి.\n\n" +
                    "🗂️ అన్ని రకాలు: ప్రతి ఫోటోనూ మొక్కలు, ఆహారం, మనుషులు… ఇలా 11 folders లోకి.\n\n" +
                    "తరలించే ముందు AI ఎంచుకున్నవి చూపిస్తాను, తప్పుగా వచ్చినవి మీరు తీసేయవచ్చు."
            )
            .setPositiveButton("🌿 మొక్కలు మాత్రమే") { _, _ -> startAiOrganize(photos, plantsOnly = true) }
            .setNeutralButton("🗂️ అన్ని రకాలు") { _, _ -> startAiOrganize(photos, plantsOnly = false) }
            .setNegativeButton("వద్దు", null)
            .show()
    }

    private fun startAiOrganize(photos: List<SearchResult>, plantsOnly: Boolean) {
        val organizer = PhotoOrganizer()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val label = TextView(this).apply {
            text = "AI ఫోటోలను చూస్తోంది… 0 / ${photos.size}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
        }
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            max = photos.size
            setPadding(0, pad / 2, 0, 0)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(bar)
        }
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (plantsOnly) "🌿 మొక్కల ఫోటోలు వెతుకుతోంది" else "🤖 ఫోటోలను గుర్తుపడుతోంది")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("ఆపు") { _, _ -> job?.cancel() }
            .show()

        job = lifecycleScope.launch {
            try {
                val plans = organizer.analyze(photos, plantsOnly) { done, total ->
                    label.text = "AI ఫోటోలను చూస్తోంది… $done / $total"
                    bar.setProgressCompat(done, true)
                }
                dialog.dismiss()
                reviewAiPlans(plans, organizer, plantsOnly, photos.size)
            } finally {
                if (dialog.isShowing) dialog.dismiss()
                if (!isActive) organizer.close()
            }
        }
    }

    private fun reviewAiPlans(plans: List<PhotoPlan>, organizer: PhotoOrganizer, plantsOnly: Boolean, total: Int) {
        if (plans.isEmpty()) {
            toast(if (plantsOnly) "$total ఫోటోలలో మొక్కల ఫోటోలు దొరకలేదు 🌿" else "ఏ ఫోటోనూ చదవలేకపోయాను")
            organizer.close()
            return
        }
        val where = organizer.outputRoot.absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath)
        val title = if (plantsOnly) "🌿 మొక్కల ఫోటోలు" else "🗂️ AI వేరు చేసినవి"
        ReviewDialog(
            activity = this,
            plans = plans,
            title = title,
            showGroup = !plantsOnly,
            onDone = { kept, move -> confirmMove(kept, organizer, where, move) },
            onCancel = { organizer.close() },
        ).show()
    }

    private fun confirmMove(plans: List<PhotoPlan>, organizer: PhotoOrganizer, where: String, move: Boolean) {
        val fromWhatsApp = plans.count { it.source.absolutePath.contains("whatsapp", ignoreCase = true) }
        if (!move || fromWhatsApp == 0) {
            applyAiPlans(plans, organizer, where, move)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ WhatsApp ఫోటోలు")
            .setMessage("$fromWhatsApp ఫోటోలు WhatsApp వి. తరలిస్తే WhatsApp chat లో అవి కనబడవు (ఫోటో మాత్రం $where లో ఉంటుంది).")
            .setPositiveButton("సరే, తరలించు") { _, _ -> applyAiPlans(plans, organizer, where, move = true) }
            .setNeutralButton("కాపీ చేయి") { _, _ -> applyAiPlans(plans, organizer, where, move = false) }
            .setNegativeButton("రద్దు") { _, _ -> organizer.close() }
            .setCancelable(false)
            .show()
    }

    private fun applyAiPlans(plans: List<PhotoPlan>, organizer: PhotoOrganizer, where: String, move: Boolean) {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val res = FileOps.applyPlans(applicationContext, plans, organizer.outputRoot, move)
            organizer.close()
            progress.visibility = View.GONE
            val verb = if (move) "తరలించాను" else "కాపీ చేశాను"
            toast("✅ ${res.done} ఫోటోలు $where లోకి $verb" + if (res.failed > 0) " • ❌ ${res.failed} కాలేదు" else "")
            repo.refreshFolderIndex()
            adapter.clearSelection()
            searchInput.setText("AI_Sorted")
        }
    }

    private fun canWriteFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun shareSelected() {
        val sel = adapter.selectedItems()
        if (sel.isEmpty()) return
        val uris = ArrayList(sel.filter { it.isFile }.mapNotNull { shareUriFor(it) })
        val text = sel.filter { !it.isFile }.joinToString("\n\n") { textFor(it) }
        val intent = when {
            uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            uris.size == 1 -> Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else -> Intent(Intent.ACTION_SEND)
        }
        if (uris.isNotEmpty()) {
            val mimes = sel.filter { it.isFile }.mapNotNull { it.mime }.distinct()
            intent.type = when {
                mimes.size == 1 -> mimes[0]
                mimes.isNotEmpty() && mimes.all { it.startsWith("image/") } -> "image/*"
                else -> "*/*"
            }
            val clip = ClipData.newRawUri("files", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"
        }
        if (text.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(intent, "ఎవరికి / ఎక్కడికి పంపాలి?"))
        } catch (e: Exception) {
            toast("పంపలేకపోయాను")
        }
    }

    private fun shareUriFor(r: SearchResult): Uri? {
        val file = r.path?.let { File(it) }
        if (file != null && file.canRead()) {
            try {
                return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } catch (_: Exception) {
            }
        }
        return r.uri
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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

    // ---------------------------------------------------------------- WhatsApp cleaner

    private fun startCleaner() {
        if (!canWriteFiles()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("అనుమతి కావాలి")
                .setMessage("WhatsApp files చూడటానికి, తీసేయడానికి \"All files access\" ఆన్ చేయాలి.")
                .setPositiveButton("ఆన్ చేయి") { _, _ -> requestMissingAccess() }
                .setNegativeButton("రద్దు", null)
                .show()
            return
        }
        val types = WaType.values()
        val labels = types.map { it.label } + "↗️ నేను పంపినవి (Sent) కూడా"
        val checked = (types.map { it.onByDefault } + false).toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("🧹 Step 1: ఏ files తీసేయాలి?")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("తర్వాత ▶") { _, _ ->
                val chosen = types.filterIndexed { i, _ -> checked[i] }.toSet()
                if (chosen.isEmpty()) toast("కనీసం ఒక రకం ఎంచుకోండి") else askCleanerAge(chosen, checked.last())
            }
            .setNegativeButton("రద్దు", null)
            .show()
    }

    private fun askCleanerAge(types: Set<WaType>, includeSent: Boolean) {
        val options = arrayOf(
            "1 నెల కంటే పాతవి", "3 నెలల కంటే పాతవి", "6 నెలల కంటే పాతవి",
            "1 సంవత్సరం కంటే పాతవి", "2 సంవత్సరాల కంటే పాతవి",
            "📅 ఈ తేదీకి ముందువి…", "అన్నీ (తేదీ ఏదైనా)",
        )
        val months = intArrayOf(1, 3, 6, 12, 24)
        MaterialAlertDialogBuilder(this)
            .setTitle("🧹 Step 2: ఎంత పాతవి?")
            .setItems(options) { _, which ->
                when (which) {
                    in months.indices -> olderThan(months[which]).let { runCleanerScan(types, includeSent, it.to + 1, it.label) }
                    5 -> pickSingleDate("ఈ తేదీకి ముందువి") { day ->
                        runCleanerScan(types, includeSent, day, "${shortDate(day)} కి ముందువి")
                    }
                    else -> runCleanerScan(types, includeSent, Long.MAX_VALUE, "అన్నీ")
                }
            }
            .setNegativeButton("రద్దు", null)
            .show()
    }

    private fun runCleanerScan(types: Set<WaType>, includeSent: Boolean, before: Long, ageLabel: String) {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            statusText.text = "🧹 WhatsApp files వెతుకుతోంది…"
            val found = withContext(Dispatchers.IO) { WhatsAppCleaner.scan(types, includeSent, before) }
            progress.visibility = View.GONE
            if (found.isEmpty()) {
                toast("$ageLabel WhatsApp files ఏవీ లేవు ✅")
                runSearch(0)
                return@launch
            }
            val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(this@MainActivity, b) }
            val summary = found.groupBy { WhatsAppCleaner.typeOf(it) }.entries
                .sortedByDescending { e -> e.value.sumOf { it.size } }
                .joinToString("\n") { (t, list) -> "$t: ${list.size} — ${fmt(list.sumOf { it.size })}" }
            val total = found.sumOf { it.size }
            val who = if (includeSent) "వచ్చినవి + పంపినవి" else "మీకు వచ్చినవి మాత్రమే (పంపినవి కాదు)"
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("🧹 ${found.size} files — ${fmt(total)}")
                .setMessage(
                    "$ageLabel • $who\n\n$summary\n\n" +
                        "తర్వాతి screen లో అన్నీ ఎంచుకుని చూపిస్తాను (పెద్దవి ముందు). " +
                        "ఉంచుకోవాలనుకున్నవాటిపై tap చేస్తే అవి డిలీట్ కావు. ఆ తర్వాత క్రింద 🗑 డిలీట్ నొక్కండి."
                )
                .setPositiveButton("చూసి ఎంచుకో ▶") { _, _ -> enterCleanerMode(found) }
                .setNegativeButton("వద్దు") { _, _ -> runSearch(0) }
                .show()
        }
    }

    private fun enterCleanerMode(items: List<SearchResult>) {
        cleanerResults = items
        findViewById<View>(R.id.cleanerBar).visibility = View.VISIBLE
        showCleanerList(items)
        adapter.selectAll()
    }

    private fun showCleanerList(items: List<SearchResult>) {
        adapter.submit(items, emptyList())
        val size = android.text.format.Formatter.formatShortFileSize(this, items.sumOf { it.size })
        findViewById<TextView>(R.id.cleanerText).text =
            "🧹 WhatsApp Cleaner: ${items.size} files • $size\n" +
                "ఉంచుకోవాలనుకున్నవాటిపై tap చేయండి. ఆ తర్వాత 🗑 డిలీట్ → జాగా వెంటనే ఖాళీ కావాలంటే \"శాశ్వత డిలీట్\"."
        statusText.text = "🧹 పెద్దవి ముందు • ఎంచుకున్నవి మాత్రమే డిలీట్ అవుతాయి"
    }

    private fun exitCleanerMode() {
        cleanerResults = null
        findViewById<View>(R.id.cleanerBar).visibility = View.GONE
        adapter.clearSelection()
    }

    // ---------------------------------------------------------------- Folder scope

    private fun chooseSearchFolder() {
        if (!canWriteFiles()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("అనుమతి కావాలి")
                .setMessage("Folders చూడటానికి \"All files access\" ఆన్ చేయాలి.")
                .setPositiveButton("ఆన్ చేయి") { _, _ -> requestMissingAccess() }
                .setNegativeButton("రద్దు", null)
                .show()
            return
        }
        FolderPicker(
            activity = this,
            confirmLabel = "ఇందులో వెతుకు ✅",
            extraButton = Pair<String, () -> Unit>("🌐 ఫోన్ మొత్తం") { setSearchFolder(null) },
        ) { dir -> setSearchFolder(dir) }.show(folder ?: Environment.getExternalStorageDirectory())
    }

    private fun setSearchFolder(dir: File?) {
        val isRoot = dir != null && dir.absolutePath == Environment.getExternalStorageDirectory().absolutePath
        folder = if (isRoot) null else dir
        folderButton.text = folder?.let { "📂 ${FolderPicker.displayPath(it)}  •  మార్చు" }
            ?: "📁 ఫోన్ మొత్తం వెతుకుతోంది • Folder ఎంచుకోండి"
        adapter.clearSelection()
        runSearch(0)
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
            "ఈ సంవత్సరం", "గత సంవత్సరం",
            "⏳ 1 నెల కంటే పాతవి", "⏳ 3 నెలల కంటే పాతవి", "⏳ 6 నెలల కంటే పాతవి",
            "⏳ 1 సంవత్సరం కంటే పాతవి", "⏳ 2 సంవత్సరాల కంటే పాతవి",
            "📅 ఈ తేదీకి ముందువి…", "📅 ఈ తేదీ తర్వాతవి…", "📅 ఈ తేదీల మధ్య…",
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
                    7 -> setDateRange(olderThan(1))
                    8 -> setDateRange(olderThan(3))
                    9 -> setDateRange(olderThan(6))
                    10 -> setDateRange(olderThan(12))
                    11 -> setDateRange(olderThan(24))
                    12 -> pickSingleDate("ఈ తేదీకి ముందువి") { day ->
                        setDateRange(DateRange("${shortDate(day)} కి ముందువి", 0L, day - 1))
                    }
                    13 -> pickSingleDate("ఈ తేదీ తర్వాతవి") { day ->
                        setDateRange(DateRange("${shortDate(day)} తర్వాతవి", day, Long.MAX_VALUE - 1))
                    }
                    14 -> showCustomRangePicker()
                }
            }
            .show()
    }

    /** Everything last changed more than [months] months ago. */
    private fun olderThan(months: Int): DateRange {
        val cutoff = Calendar.getInstance().apply {
            add(Calendar.MONTH, -months)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val label = if (months % 12 == 0) "${months / 12} సం. కంటే పాతవి" else "$months నెలల కంటే పాతవి"
        return DateRange(label, 0L, cutoff - 1)
    }

    /** Date picker for one day; gives back the start of that day in local time. */
    private fun pickSingleDate(title: String, onPicked: (Long) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker().setTitleText(title).build()
        picker.addOnPositiveButtonClickListener { utcMidnight ->
            val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
            val local = Calendar.getInstance().apply {
                clear()
                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
            }
            onPicked(local.timeInMillis)
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun shortDate(ms: Long) = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))

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
        cleanerResults?.let { list ->
            // Cleaner mode: keep showing the cleaner's list (minus files already deleted/moved).
            val alive = list.filter { r -> r.path?.let { File(it).exists() } == true }
            if (alive.isNotEmpty()) {
                cleanerResults = alive
                showCleanerList(alive)
                return
            }
            exitCleanerMode()
        }
        val filters = SearchFilters(
            searchInput.text?.toString().orEmpty(), category, dateRange, sortMode, folder?.absolutePath,
        )
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
        val where = folder?.let { "📂 ${it.name} లో " } ?: ""
        val head = where + if (f.terms.isEmpty()) "ఇటీవలివి" else "\"${f.query.trim()}\""
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
        if (!isMedia || r.uri == null) {
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

    private fun textFor(r: SearchResult): String = when (r.kind) {
        Kind.CONTACT -> "${r.title}: ${r.number}"
        Kind.MESSAGE -> "${r.title}:\n${r.fullText ?: r.subtitle}"
        else -> r.path ?: r.title
    }

    private fun copyText(items: List<SearchResult>) {
        if (items.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Phone Search", items.joinToString("\n\n") { textFor(it) }))
        Toast.makeText(this, "${items.size} కాపీ అయ్యాయి ✅", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
