package id.ifan.routtrans.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import id.ifan.routtrans.R
import id.ifan.routtrans.data.AppConfig
import id.ifan.routtrans.data.AppEntry
import id.ifan.routtrans.data.ConfigStore
import id.ifan.routtrans.data.AppLog
import id.ifan.routtrans.data.InjectionStore
import id.ifan.routtrans.data.LogFiles
import id.ifan.routtrans.data.ModelNotifier
import id.ifan.routtrans.data.Settings
import id.ifan.routtrans.root.ModuleManager
import id.ifan.routtrans.root.PayloadUpdater
import id.ifan.routtrans.root.RootShell
import id.ifan.routtrans.translate.LanguageCatalog
import id.ifan.routtrans.translate.LanguageOption
import id.ifan.routtrans.translate.TranslateEngine
import id.ifan.routtrans.translate.TranslationStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Layar pengendali: memilih bahasa, memilih aplikasi target, lalu menulis
 * konfigurasi yang dibaca modul Zygisk.
 *
 * Setiap operasi menampilkan status di layar (dan toast/dialog saat selesai)
 * supaya pengguna tahu persis apakah konfigurasi benar-benar tersimpan.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var selectionInfo: TextView
    private lateinit var injectionInfo: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var spinnerFrom: Spinner
    private lateinit var spinnerTo: Spinner
    private lateinit var saveButton: Button
    private lateinit var prepareButton: Button

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RoutTransUi")
    }

    private lateinit var adapter: AppListAdapter
    private var sources: List<LanguageOption> = emptyList()
    private var targets: List<LanguageOption> = emptyList()
    private var busy = false
    private var rootAvailable = false
    private var lastLogRefresh = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mencatat crash (termasuk kegagalan memuat kelas layanan) ke app.log
        // agar penyebabnya bisa dibaca tanpa logcat.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.add(this, "CRASH ${error.javaClass.simpleName}: ${error.message ?: "-"}")
            previousHandler?.uncaughtException(thread, error)
        }

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        applyWindowInsets()

        statusText = findViewById(R.id.statusText)
        selectionInfo = findViewById(R.id.selectionInfo)
        injectionInfo = findViewById(R.id.injectionInfo)
        progressBar = findViewById(R.id.progressBar)
        spinnerFrom = findViewById(R.id.spinnerFrom)
        spinnerTo = findViewById(R.id.spinnerTo)
        saveButton = findViewById(R.id.saveButton)
        prepareButton = findViewById(R.id.prepareButton)

        setupLanguages()

        adapter = AppListAdapter { updateSelectionInfo() }
        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        saveButton.setOnClickListener { save() }
        prepareButton.setOnClickListener { prepareModels() }
        injectionInfo.setOnClickListener { showInjectionDetail() }

        // Setelah pasang ulang, preferensi ikut terhapus sehingga waktu simpan
        // kembali 0 dan catatan injeksi lama tampak seolah masih berlaku.
        // Anggap pemasangan ini sebagai titik awal konfigurasi.
        if (Settings.lastSave(this) == 0L) {
            Settings.setLastSave(this, System.currentTimeMillis())
        }

        // Log terjemahan ikut dimuat dari penyimpanan supaya tidak kosong
        // setiap kali proses RoutTrans dimulai ulang.
        TranslationStore.init(this)
        // Pastikan payload di folder modul selalu versi yang sama dengan APK.
        worker.execute {
            AppLog.add(this, "Aplikasi dibuka")
            PayloadUpdater.ensureLatest(this)
            // APK hasil CI juga membawa zip modul; pasang otomatis bila baru.
            if (ModuleManager.ensureLatest(this)) {
                AppLog.add(this, "Modul diperbarui, menunggu reboot")
                runOnUiThread { toast(R.string.toast_module_updated) }
            }
            // Log injeksi selalu tersedia di Android/data tanpa harus membuka
            // menunya lebih dulu.
            LogFiles.write(this, LogFiles.INJECTION, readInjectionLog())
        }

        updateSelectionInfo()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        // Status injeksi bisa berubah saat pengguna membuka aplikasi target
        // lalu kembali ke sini, jadi selalu dibaca ulang.
        refreshInjections()
        refreshInjectionLogIfStale()
    }

    /**
     * Menyegarkan `injection.log` saat aplikasi kembali ke depan.
     *
     * Tanpa ini berkas hanya terisi ketika RoutTrans dibuka dari awal, sehingga
     * kejadian di aplikasi target sering tidak terekam. Dibatasi agar tidak
     * memanggil root terlalu sering.
     */
    private fun refreshInjectionLogIfStale() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLogRefresh < LOG_REFRESH_MS) return
        lastLogRefresh = now
        worker.execute {
            LogFiles.write(this, LogFiles.INJECTION, readInjectionLog())
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (busy) {
            toast(R.string.status_working)
            return true
        }
        return when (item.itemId) {
            R.id.action_install_module -> {
                confirm(R.string.action_install_module, R.string.confirm_install_module) { installModule() }
                true
            }
            R.id.action_remove_module -> {
                confirm(R.string.action_remove_module, R.string.confirm_remove_module) { removeModule() }
                true
            }
            R.id.action_update_payload -> {
                updatePayload()
                true
            }
            R.id.action_status -> {
                showStatus()
                true
            }
            R.id.action_injection_status -> {
                showInjectionDetail()
                true
            }
            R.id.action_diagnostics -> {
                showDiagnostics()
                true
            }
            R.id.action_log -> {
                showTranslationLog()
                true
            }
            R.id.action_app_log -> {
                showAppLog()
                true
            }
            R.id.action_delete_models -> {
                showDeleteModels()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * targetSdk 36 membuat Android menggambar konten sampai ke area status bar,
     * sehingga judul aplikasi bisa tertutup jam dan notifikasi. Padding di sini
     * menggeser seluruh isi ke bawah sesuai ukuran system bar.
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars =
            night != Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupLanguages() {
        sources = LanguageCatalog.sources(this)
        targets = LanguageCatalog.targets(this)

        spinnerFrom.adapter = spinnerAdapter(sources)
        spinnerTo.adapter = spinnerAdapter(targets)
        spinnerFrom.setSelection(LanguageCatalog.resolve(sources, Settings.from(this)))
        spinnerTo.setSelection(LanguageCatalog.resolve(targets, Settings.to(this)))
    }

    private fun spinnerAdapter(options: List<LanguageOption>): ArrayAdapter<String> {
        val labels = options.map { it.label(this) }
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun loadApps() {
        setBusy(true, getString(R.string.status_loading))
        worker.execute {
            val rootOk = RootShell.hasRoot()
            var apps: List<AppEntry> = emptyList()
            var failed = false
            try {
                val manager = packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val seen = LinkedHashSet<String>()
                val active = if (rootOk) ConfigStore.loadActive() else emptyMap()
                for (info in manager.queryIntentActivities(launcher, 0)) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == packageName || !seen.add(pkg)) continue
                    val appInfo = try {
                        manager.getApplicationInfo(pkg, 0)
                    } catch (e: Exception) {
                        continue
                    }
                    if (!appInfo.enabled) continue
                    apps += AppEntry(
                        packageName = pkg,
                        label = manager.getApplicationLabel(appInfo).toString(),
                        icon = manager.getApplicationIcon(appInfo),
                        enabled = active.containsKey(pkg),
                    )
                }
                apps = apps.sortedBy { it.label.lowercase(Locale.getDefault()) }
            } catch (e: Exception) {
                failed = true
            }

            runOnUiThread {
                setBusy(false)
                rootAvailable = rootOk
                adapter.submit(apps)
                updateSelectionInfo()
                refreshInjections()
                when {
                    !rootOk -> setStatus(R.string.status_no_root)
                    failed -> setStatus(R.string.status_load_failed)
                    else -> setStatus(R.string.status_ready, apps.size)
                }
            }
        }
    }

    private fun save() {
        if (!rootAvailable) {
            setStatus(R.string.status_no_root)
            toast(R.string.toast_save_failed)
            return
        }
        val from = sources[spinnerFrom.selectedItemPosition].code
        val to = targets[spinnerTo.selectedItemPosition].code
        if (from != LanguageCatalog.AUTO && from == to) {
            toast(R.string.error_same_language)
            return
        }

        val selected = adapter.selected()
        val config = AppConfig(from = from, to = to)
        setBusy(true, getString(R.string.status_saving))
        worker.execute {
            val result = ConfigStore.apply(this, config, selected.map { it.packageName })
            val stopped = if (result.ok) {
                ConfigStore.forceStop(selected.map { it.packageName })
            } else {
                RootShell.Result(0, "")
            }
            val module = if (result.ok) ModuleManager.status() else ModuleManager.Status(false, false, "", "")

            runOnUiThread {
                setBusy(false)
                if (!result.ok) {
                    setStatus(
                        R.string.status_save_failed,
                        result.output.ifBlank { getString(R.string.error_unknown) },
                    )
                    toast(R.string.toast_save_failed)
                    return@runOnUiThread
                }

                Settings.setFrom(this, from)
                Settings.setTo(this, to)
                Settings.setLastSave(this, System.currentTimeMillis())
                Settings.setSelectedPackages(this, selected.map { it.packageName })
                AppLog.add(this, "Konfigurasi disimpan: ${selected.size} aplikasi, $from → $to")
                refreshInjections()
                when {
                    !stopped.ok -> setStatus(R.string.status_saved_stop_failed, selected.size)
                    !module.active -> setStatus(R.string.status_saved_no_module, selected.size)
                    else -> setStatus(R.string.status_saved, selected.size)
                }
                toast(R.string.toast_saved)
            }
        }
    }

    /**
     * Membuka dialog pilih model.
     *
     * Satu model MLKit berlaku untuk satu pasangan bahasa, jadi pengguna perlu
     * memilih bahasa mana saja yang ingin disiapkan sekaligus. Tanpa dialog
     * ini hanya pasangan yang sedang tampil di dropdown yang terunduh.
     */
    private fun prepareModels() {
        // MLKit mengunduh model lewat Google Play Services. Kalau layanan itu
        // tidak siap, unduhan pasti gagal; lebih baik dijelaskan sekarang.
        val gms = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(this)
        if (gms != ConnectionResult.SUCCESS) {
            val message = getString(R.string.status_gms_unavailable, gms)
            setStatus(R.string.status_prepare_failed, "kode $gms")
            dialog(R.string.dialog_failure, message)
            return
        }

        val toIndex = spinnerTo.selectedItemPosition
        val toCode = targets[toIndex].code
        val toLabel = targets[toIndex].label(this)
        val options = targets.filter { it.code != toCode }
        if (options.isEmpty()) {
            dialog(R.string.dialog_failure, getString(R.string.models_empty))
            return
        }

        // Yang sudah dipakai sekarang langsung dicentang: bahasa sumber yang
        // dipilih dan bahasa perangkat.
        val current = sources[spinnerFrom.selectedItemPosition].code
        val preferred = setOf(
            LanguageCatalog.normalize(LanguageCatalog.deviceLanguage()),
            LanguageCatalog.normalize(current),
        )
        val checked = BooleanArray(options.size) { options[it].code in preferred }
        val labels = options.map { it.label(this) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.action_prepare_model)
            .setMessage(getString(R.string.models_dialog_message, toLabel, MODEL_SIZE_MB))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_download) { _, _ ->
                val chosen = options.filterIndexed { index, _ -> checked[index] }.map { it.code }
                if (chosen.isEmpty()) {
                    toast(R.string.toast_no_model_selected)
                } else {
                    downloadModels(chosen, toCode)
                }
            }
            .show()
    }

    /** Mengunduh beberapa model berurutan dan melaporkan hasil tiap bahasa. */
    private fun downloadModels(sourcesToDownload: List<String>, to: String) {
        ensureNotificationPermission()
        setBusy(true, getString(R.string.status_downloading))
        worker.execute {
            val results = TranslateEngine.prepareMany(sourcesToDownload, to) { done, total, source ->
                // Notifikasi ikut diperbarui dari thread ini; NotificationManager
                // aman dipanggil lintas thread.
                ModelNotifier.progress(this, done, total, labelOf(source), labelOf(to))
                runOnUiThread {
                    setStatus(
                        R.string.status_preparing_model,
                        done,
                        total,
                        labelOf(source),
                        labelOf(to),
                    )
                }
            }

            val ready = results.count { it.error == null }
            ModelNotifier.finish(this, ready, results.size)
            AppLog.add(this, "Model siap: $ready/${results.size}")

            runOnUiThread {
                setBusy(false)
                val report = results.joinToString("\n") { result ->
                    if (result.error == null) {
                        getString(R.string.model_ready_line, labelOf(result.source), labelOf(to))
                    } else {
                        getString(
                            R.string.model_failed_line,
                            labelOf(result.source),
                            labelOf(to),
                            result.error.orEmpty(),
                        )
                    }
                }

                if (ready == results.size) {
                    setStatus(R.string.status_models_ready)
                    toast(R.string.toast_models_ready)
                } else {
                    setStatus(R.string.status_models_partial, ready, results.size)
                }
                // Rincian per bahasa selalu ditampilkan; kalau ada yang gagal,
                // pesan cause MLKit-nya ikut terlihat dan bisa disalin.
                dialog(
                    if (ready == results.size) R.string.dialog_success else R.string.dialog_failure,
                    report,
                )
            }
        }
    }

    private fun labelOf(code: String): String =
        targets.firstOrNull { it.code == code }?.label(this) ?: code

    /** Membaca logcat yang relevan; dipakai dialog dan penulisan berkas. */
    private fun readInjectionLog(): String {
        val log = RootShell.run(LOGCAT_COMMAND)
        return log.output.trim().ifBlank { getString(R.string.diagnostics_empty) }
    }

    /** Log kejadian aplikasi: payload, modul, konfigurasi, dan unduhan model. */
    private fun showAppLog() {
        val file = File(LogFiles.dir(this), AppLog.FILE_NAME)
        val report = try {
            if (file.exists()) file.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }.ifBlank { getString(R.string.app_log_empty) }
        val path = getString(R.string.log_saved_at, file.absolutePath)
        dialog(R.string.action_app_log, "$report\n\n$path")
    }

    /**
     * Menjelaskan mode aksesibilitas dan membuka pengaturan yang diperlukan.
     *
     * Mode ini melengkapi mode View: teks Compose, dialog, dan bottom sheet
     * hanya terbaca lewat pohon aksesibilitas.
     */

    /** Menampilkan daftar model terunduh dan memungkinkan penghapusan. */
    private fun showDeleteModels() {
        val files = TranslateEngine.installedModels(this)
        if (files.isEmpty()) {
            dialog(R.string.action_delete_models, getString(R.string.models_none))
            return
        }
        val labels = files.map { modelLabel(it) }.toTypedArray()
        val checked = BooleanArray(files.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete_models)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val chosen = files.filterIndexed { index, _ -> checked[index] }
                if (chosen.isEmpty()) {
                    toast(R.string.toast_no_model_selected)
                    return@setPositiveButton
                }
                confirm(R.string.action_delete, R.string.confirm_delete_models) {
                    TranslateEngine.deleteModels(chosen)
                    AppLog.add(this, "Model dihapus: ${chosen.size}")
                    toast(R.string.toast_models_deleted)
                }
            }
            .show()
    }

    /** Nama berkas model diubah menjadi "en → ko · 32 MB". */
    private fun modelLabel(file: File): String {
        val base = file.name.removeSuffix(".model")
        val parts = base.split('_')
        val pair = if (parts.size >= 2) "${parts.first()} → ${parts.last()}" else base
        val mb = (file.length() / (1024 * 1024)).coerceAtLeast(1)
        return "$pair · $mb MB"
    }

    /** Peringatan saat layanan aksesibilitas mati setelah pembaruan APK. */

    /** Memeriksa apakah layanan RoutTrans sudah diaktifkan di pengaturan sistem. */

    /** Android 13+ meminta izin notifikasi sebelum progres unduhan bisa tampil. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun installModule() {
        setBusy(true, getString(R.string.status_installing_module))
        worker.execute {
            val result = ModuleManager.install(this)
            runOnUiThread {
                setBusy(false)
                if (result.ok) {
                    dialog(
                        R.string.dialog_success,
                        getString(R.string.dialog_module_installed, result.output.ifBlank { "-" }),
                    )
                } else {
                    dialog(R.string.dialog_failure, result.output.ifBlank { getString(R.string.error_unknown) })
                }
            }
        }
    }

    private fun removeModule() {
        setBusy(true, getString(R.string.status_removing_module))
        worker.execute {
            val result = ModuleManager.remove()
            runOnUiThread {
                setBusy(false)
                if (result.ok) {
                    dialog(R.string.dialog_success, getString(R.string.dialog_module_removed))
                } else {
                    dialog(R.string.dialog_failure, result.output.ifBlank { getString(R.string.error_unknown) })
                }
            }
        }
    }

    private fun updatePayload() {
        setBusy(true, getString(R.string.status_updating_payload))
        worker.execute {
            val result = PayloadUpdater.update(this)
            if (result.ok) PayloadUpdater.rememberCurrent(this)
            runOnUiThread {
                setBusy(false)
                if (result.ok) {
                    toast(R.string.toast_payload_updated)
                    dialog(R.string.dialog_success, getString(R.string.dialog_payload_updated))
                } else {
                    dialog(R.string.dialog_failure, result.output.ifBlank { getString(R.string.error_unknown) })
                }
            }
        }
    }

    private fun showStatus() {
        setBusy(true, getString(R.string.status_checking))
        worker.execute {
            val root = RootShell.hasRoot()
            val module = ModuleManager.status()
            val provider = ModuleManager.isProviderAlive(this)
            val selected = adapter.selected().size
            val total = TranslationStore.totalCount()
            val last = TranslationStore.lastTime()

            val moduleState = when {
                !module.installed -> getString(R.string.value_not_installed)
                module.disabled -> getString(R.string.value_disabled)
                else -> getString(R.string.value_active)
            }
            val report = buildString {
                append(getString(R.string.status_line_root, yesNo(root)))
                append('\n')
                val moduleDetail = if (module.version.isBlank()) moduleState else "$moduleState (${module.version})"
                append(getString(R.string.status_line_module, moduleDetail))
                append('\n')
                append(getString(R.string.status_line_provider, yesNo(provider)))
                append('\n')
                // MLKit mengunduh model lewat Google Play Services; kode selain
                // SUCCESS menjelaskan kenapa "Siapkan model" gagal.
                val gms = GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(this@MainActivity)
                val gmsText = if (gms == ConnectionResult.SUCCESS) {
                    getString(R.string.value_yes)
                } else {
                    "kode $gms"
                }
                append(getString(R.string.status_line_gms, gmsText))
                append('\n')
                append(getString(R.string.status_line_selected, selected))
                append('\n')
                append(getString(R.string.status_line_translations, total))
                append('\n')
                append(
                    getString(
                        R.string.status_line_last,
                        if (last == null) getString(R.string.value_none) else formatTime(last),
                    )
                )
                append('\n')
                append(getString(R.string.status_line_injections, InjectionStore.total(this@MainActivity)))
                append('\n')
                append(
                    getString(
                        R.string.status_line_last_injection,
                        InjectionStore.lastTime(this@MainActivity)
                            ?.let { formatTime(it) }
                            ?: getString(R.string.value_none),
                    )
                )
            }

            runOnUiThread {
                setBusy(false)
                dialog(R.string.action_status, report.trimEnd())
            }
        }
    }

    /** Membaca ulang laporan injeksi dan memperbarui baris aplikasi + ringkasan bawah. */
    private fun refreshInjections() {
        if (!::adapter.isInitialized) return
        val lastSave = Settings.lastSave(this)
        // Catatan dari konfigurasi lama dibuang otomatis supaya status yang
        // tampil selalu berasal dari konfigurasi yang sedang aktif.
        InjectionStore.prune(this, lastSave)
        val records = InjectionStore.all(this)
        adapter.setInjections(records, lastSave)
        updateInjectionSummary(records, lastSave)
    }

    private fun updateInjectionSummary(
        records: Map<String, InjectionStore.Record>,
        lastSave: Long,
    ) {
        val selected = adapter.selected()
        if (selected.isEmpty()) {
            injectionInfo.text = getString(R.string.injection_summary_none)
            injectionInfo.setTextColor(
                MaterialColors.getColor(
                    injectionInfo,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            )
            return
        }

        var ok = 0
        var failed = 0
        for (app in selected) {
            val record = records[app.packageName] ?: continue
            if (record.time < lastSave) continue
            if (record.error == null) ok++ else failed++
        }

        injectionInfo.text = when {
            ok > 0 && failed > 0 ->
                getString(R.string.injection_summary_mixed, ok, selected.size, failed)
            ok > 0 -> getString(R.string.injection_summary_ok, ok, selected.size)
            failed > 0 -> getString(R.string.injection_summary_failed, failed)
            else -> getString(R.string.injection_summary_waiting)
        }
        injectionInfo.setTextColor(
            when {
                ok > 0 && failed == 0 -> ContextCompat.getColor(this, R.color.injection_ok)
                failed > 0 -> ContextCompat.getColor(this, R.color.injection_error)
                else -> MaterialColors.getColor(
                    injectionInfo,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            }
        )
    }

    private fun showInjectionDetail() {
        val selected = adapter.selected()
        if (selected.isEmpty()) {
            dialog(R.string.action_injection_status, getString(R.string.injection_detail_empty))
            return
        }
        val records = InjectionStore.all(this)
        val lastSave = Settings.lastSave(this)
        val report = selected.joinToString("\n\n") { app ->
            val record = records[app.packageName]
            val state = when {
                record == null || record.time < lastSave -> getString(R.string.injection_stale)
                record.error != null -> getString(R.string.injection_error, record.error.orEmpty())
                else -> getString(R.string.injection_ok, formatTime(record.time))
            }
            buildString {
                append(app.label)
                append('\n')
                append(app.packageName)
                append('\n')
                append(state)
                if (record != null && record.time >= lastSave) {
                    if (record.process.isNotEmpty()) {
                        append('\n')
                        append(getString(R.string.injection_process, record.process))
                    }
                    if (record.from.isNotEmpty()) {
                        append('\n')
                        append(getString(R.string.injection_languages, record.from, record.to))
                    }
                }
            }
        }
        dialog(R.string.action_injection_status, report)
    }

    /**
     * Menampilkan log injeksi dari logcat.
     *
     * Payload berjalan di proses aplikasi target, jadi kegagalannya hanya
     * tercatat di logcat sistem. Tanpa layar ini pengguna tidak punya cara
     * melihat alasan kegagalan selain memakai komputer.
     */
    private fun showDiagnostics() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroller = ScrollView(this).apply { addView(text) }
        val handler = Handler(Looper.getMainLooper())
        val saved = getString(R.string.log_saved_at, LogFiles.path(this, LogFiles.INJECTION))

        val refresh = object : Runnable {
            override fun run() {
                worker.execute {
                    val report = readInjectionLog()
                    // Berkas ditulis ulang setiap kali, bukan ditambahi, supaya
                    // isinya selalu sama dengan yang tampil di dialog.
                    LogFiles.write(this@MainActivity, LogFiles.INJECTION, report)
                    runOnUiThread { text.text = "$report\n\n$saved" }
                }
                handler.postDelayed(this, LIVE_REFRESH_MS)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.action_diagnostics)
            .setView(scroller)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_copy) { _, _ ->
                copyToClipboard(getString(R.string.action_diagnostics), text.text.toString())
            }
            .setOnDismissListener { handler.removeCallbacks(refresh) }
            .show()
        text.text = getString(R.string.diagnostics_empty)
        handler.post(refresh)
    }

    /**
     * Log terjemahan dengan pembaruan langsung.
     *
     * Payload di aplikasi target bisa mengirim permintaan kapan saja, jadi isi
     * dialog diperbarui tiap detik selama dibuka; pengguna tidak perlu menutup
     * dan membuka ulang untuk melihat hasil terbaru.
     */
    private fun showTranslationLog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val scroller = ScrollView(this).apply { addView(text) }

        fun render(): String {
            val entries = TranslationStore.recent()
            val body = if (entries.isEmpty()) {
                getString(R.string.log_empty)
            } else {
                entries.take(40).joinToString("\n") { entry ->
                    val time = formatTime(entry.time)
                    if (entry.ok) {
                        getString(R.string.log_entry, time, entry.from, entry.to, entry.source, entry.result.orEmpty())
                    } else {
                        getString(R.string.log_entry_error, time, entry.source, entry.error.orEmpty())
                    }
                }
            }
            val path = getString(
                R.string.log_saved_at,
                LogFiles.path(this, TranslationStore.FILE_NAME),
            )
            return "$body\n\n$path"
        }

        text.text = render()
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                text.text = render()
                handler.postDelayed(this, 1000L)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_log)
            .setView(scroller)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_copy) { _, _ ->
                copyToClipboard(getString(R.string.action_log), text.text.toString())
            }
            .setOnDismissListener { handler.removeCallbacks(tick) }
            .show()
        handler.post(tick)
    }

    private fun yesNo(value: Boolean): String =
        getString(if (value) R.string.value_yes else R.string.value_no)

    private fun formatTime(time: Long): String =
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(time))

    private fun setStatus(@StringRes id: Int, vararg args: Any) {
        statusText.text = getString(id, *args)
    }

    private fun setBusy(value: Boolean, message: String? = null) {
        busy = value
        progressBar.visibility = if (value) View.VISIBLE else View.GONE
        saveButton.isEnabled = !value
        prepareButton.isEnabled = !value
        if (message != null) statusText.text = message
    }

    private fun updateSelectionInfo() {
        val count = adapter.selected().size
        selectionInfo.text = resources.getQuantityString(R.plurals.selected_apps, count, count)
    }

    private fun toast(@StringRes id: Int) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    }

    /**
     * Dialog dengan tombol Salin.
     *
     * Hampir semua dialog di sini berisi hasil diagnostik (log, pesan galat
     * MLKit, status modul), jadi isinya harus bisa ditempel ke tempat lain.
     */
    private fun dialog(@StringRes title: Int, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_copy) { _, _ ->
                copyToClipboard(getString(title), message)
            }
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(R.string.toast_copied)
    }

    private fun confirm(@StringRes title: Int, @StringRes message: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .show()
    }

    private companion object {
        /** Baris logcat yang relevan untuk payload dan MLKit. */
        const val LOGCAT_COMMAND =
            "logcat -d -v brief 2>/dev/null | grep -aE 'RoutTrans[A-Za-z]*\\(|MlKit|mlkit|AndroidRuntime.*ifan.routtrans|Accessibility.*ifan.routtrans' | tail -300"

        /** Jeda pembaruan dialog log selama terbuka. */
        const val LIVE_REFRESH_MS = 3000L

        /** Perkiraan ukuran satu model bahasa MLKit, untuk informasi di dialog. */
        const val MODEL_SIZE_MB = 30

        /** Kode permintaan izin notifikasi Android 13+. */
        const val REQUEST_NOTIFICATIONS = 41

        /** Jarak minimal antar penyegaran injection.log saat aplikasi dibuka. */
        const val LOG_REFRESH_MS = 20_000L
    }
}
