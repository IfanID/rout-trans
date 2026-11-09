package id.ifan.routtrans.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import id.ifan.routtrans.R
import id.ifan.routtrans.data.AppConfig
import id.ifan.routtrans.data.AppEntry
import id.ifan.routtrans.data.ConfigStore
import id.ifan.routtrans.data.InjectionStore
import id.ifan.routtrans.data.Settings
import id.ifan.routtrans.root.ModuleManager
import id.ifan.routtrans.root.RootShell
import id.ifan.routtrans.translate.LanguageCatalog
import id.ifan.routtrans.translate.LanguageOption
import id.ifan.routtrans.translate.TranslateEngine
import id.ifan.routtrans.translate.TranslationStore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

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

        updateSelectionInfo()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        // Status injeksi bisa berubah saat pengguna membuka aplikasi target
        // lalu kembali ke sini, jadi selalu dibaca ulang.
        refreshInjections()
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
            R.id.action_status -> {
                showStatus()
                true
            }
            R.id.action_injection_status -> {
                showInjectionDetail()
                true
            }
            R.id.action_log -> {
                showTranslationLog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

    private fun prepareModels() {
        val from = sources[spinnerFrom.selectedItemPosition].code
        val to = targets[spinnerTo.selectedItemPosition].code
        setBusy(true, getString(R.string.status_downloading))
        worker.execute {
            try {
                TranslateEngine.prepare(from, to)
                runOnUiThread {
                    setBusy(false)
                    setStatus(R.string.status_models_ready)
                    toast(R.string.toast_models_ready)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    setStatus(R.string.status_prepare_failed, e.message.orEmpty())
                    toast(R.string.toast_prepare_failed)
                }
            }
        }
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
        val records = InjectionStore.all(this)
        val lastSave = Settings.lastSave(this)
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

    private fun showTranslationLog() {
        val entries = TranslationStore.recent()
        if (entries.isEmpty()) {
            dialog(R.string.action_log, getString(R.string.log_empty))
            return
        }
        val report = entries.take(40).joinToString("\n") { entry ->
            val time = formatTime(entry.time)
            if (entry.ok) {
                getString(R.string.log_entry, time, entry.from, entry.to, entry.source, entry.result.orEmpty())
            } else {
                getString(R.string.log_entry_error, time, entry.source, entry.error.orEmpty())
            }
        }
        dialog(R.string.action_log, report)
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

    private fun dialog(@StringRes title: Int, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirm(@StringRes title: Int, @StringRes message: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .show()
    }
}
