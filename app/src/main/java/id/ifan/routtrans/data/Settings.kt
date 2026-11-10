package id.ifan.routtrans.data

import android.content.Context
import id.ifan.routtrans.translate.LanguageCatalog

/** Menyimpan pilihan bahasa terakhir di SharedPreferences aplikasi. */
object Settings {

    private const val PREF = "routtrans"
    private const val KEY_FROM = "from"
    private const val KEY_TO = "to"
    private const val KEY_LAST_SAVE = "last_save"
    private const val KEY_PAYLOAD_HASH = "payload_hash"
    private const val KEY_MODULE_HASH = "module_hash"
    private const val KEY_SELECTED = "selected_packages"
    private const val KEY_A11Y_SEEN = "a11y_seen"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Sumber default "auto": bahasa teks dideteksi saat terjemahan berjalan. */
    fun from(context: Context): String {
        val saved = prefs(context).getString(KEY_FROM, null)
        return saved ?: LanguageCatalog.AUTO
    }

    fun setFrom(context: Context, from: String) {
        prefs(context).edit().putString(KEY_FROM, from).apply()
    }

    fun to(context: Context): String = prefs(context).getString(KEY_TO, "id") ?: "id"

    fun setTo(context: Context, to: String) {
        prefs(context).edit().putString(KEY_TO, to).apply()
    }

    /** Waktu konfigurasi terakhir diterapkan, dipakai untuk menilai injeksi terbaru. */
    fun lastSave(context: Context): Long = prefs(context).getLong(KEY_LAST_SAVE, 0L)

    fun setLastSave(context: Context, time: Long) {
        prefs(context).edit().putLong(KEY_LAST_SAVE, time).apply()
    }

    /** Sidik dex payload yang terakhir disalin ke folder modul. */
    fun payloadHash(context: Context): String =
        prefs(context).getString(KEY_PAYLOAD_HASH, "").orEmpty()

    fun setPayloadHash(context: Context, hash: String) {
        prefs(context).edit().putString(KEY_PAYLOAD_HASH, hash).apply()
    }

    /** Sidik zip modul yang terakhir dipasang. */
    fun moduleHash(context: Context): String =
        prefs(context).getString(KEY_MODULE_HASH, "").orEmpty()

    fun setModuleHash(context: Context, hash: String) {
        prefs(context).edit().putString(KEY_MODULE_HASH, hash).apply()
    }

    /** Paket yang dipilih untuk diterjemahkan, dibaca juga oleh mode aksesibilitas. */
    fun selectedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED, emptySet())?.toSet() ?: emptySet()

    fun setSelectedPackages(context: Context, packages: Collection<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED, packages.toSet()).apply()
    }

    /**
     * Pernah-tidaknya layanan aksesibilitas aktif.
     *
     * Dipakai untuk memperingatkan pengguna ketika Android mematikan layanan
     * itu setelah aplikasi diperbarui.
     */
    fun a11ySeen(context: Context): Boolean = prefs(context).getBoolean(KEY_A11Y_SEEN, false)

    fun setA11ySeen(context: Context, seen: Boolean) {
        prefs(context).edit().putBoolean(KEY_A11Y_SEEN, seen).apply()
    }
}
