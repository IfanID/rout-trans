package id.ifan.routtrans.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import id.ifan.routtrans.R
import id.ifan.routtrans.data.AppEntry
import id.ifan.routtrans.data.InjectionStore
import java.text.DateFormat
import java.util.Date

/**
 * Daftar aplikasi terpasang dengan satu sakelar per aplikasi.
 *
 * Baris juga menampilkan status injeksi terakhir supaya pengguna bisa melihat
 * apakah payload benar-benar masuk ke aplikasi target, bukan hanya memercayai
 * konfigurasi yang tersimpan.
 */
class AppListAdapter(
    private val onToggle: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private val items = mutableListOf<AppEntry>()
    private var injections: Map<String, InjectionStore.Record> = emptyMap()
    private var lastSave = 0L

    fun submit(list: List<AppEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setInjections(records: Map<String, InjectionStore.Record>, lastSave: Long) {
        injections = records
        this.lastSave = lastSave
        notifyDataSetChanged()
    }

    fun selected(): List<AppEntry> = items.filter { it.enabled }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val label: TextView = view.findViewById(R.id.appLabel)
        private val packageName: TextView = view.findViewById(R.id.appPackage)
        private val injection: TextView = view.findViewById(R.id.appInjection)
        private val toggle: MaterialSwitch = view.findViewById(R.id.appToggle)

        fun bind(entry: AppEntry) {
            icon.setImageDrawable(entry.icon)
            label.text = entry.label
            packageName.text = entry.packageName

            val record = injections[entry.packageName]
            when {
                record == null || record.time < lastSave -> {
                    injection.text = injection.context.getString(R.string.injection_none)
                    injection.setTextColor(
                        ContextCompat.getColor(injection.context, R.color.injection_idle)
                    )
                }
                record.error != null -> {
                    injection.text = injection.context.getString(
                        R.string.injection_error,
                        record.error,
                    )
                    injection.setTextColor(
                        ContextCompat.getColor(injection.context, R.color.injection_error)
                    )
                }
                else -> {
                    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM)
                        .format(Date(record.time))
                    injection.text = injection.context.getString(R.string.injection_ok, time)
                    injection.setTextColor(
                        ContextCompat.getColor(injection.context, R.color.injection_ok)
                    )
                }
            }

            // Listener dilepas dulu supaya perubahan status programatik tidak
            // dianggap ketukan pengguna saat baris didaur ulang.
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = entry.enabled
            toggle.setOnCheckedChangeListener { _, checked ->
                entry.enabled = checked
                onToggle(entry)
            }
        }
    }
}
