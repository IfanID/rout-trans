package id.ifan.routtrans.translate

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import id.ifan.routtrans.data.InjectionStore

/**
 * Titik masuk terjemahan untuk payload yang berjalan di proses aplikasi lain.
 *
 * Berkas dex payload tidak bisa memuat MLKit, jadi terjemahan dikerjakan di
 * proses RoutTrans lalu dikembalikan lewat ContentProvider.call(). Payload
 * hanya perlu mengirim daftar teks dan menerima daftar hasil dengan urutan sama.
 */
class TranslateProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { TranslationStore.init(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        when (method) {
            METHOD_PING -> Bundle().apply { putBoolean("ok", true) }
            METHOD_TRANSLATE -> translate(extras)
            METHOD_INJECTED -> recordInjection(extras)
            else -> error("Metode tidak dikenal: $method")
        }

    /** Payload melapor setelah berhasil atau gagal terpasang di proses target. */
    private fun recordInjection(extras: Bundle?): Bundle {
        val context = context ?: return error("Konteks tidak tersedia")
        val data = extras ?: return error("Data injeksi tidak ada")
        val pkg = data.getString(KEY_PACKAGE) ?: return error("Nama paket tidak ada")
        InjectionStore.record(
            context = context,
            packageName = pkg,
            process = data.getString(KEY_PROCESS).orEmpty(),
            from = data.getString(KEY_FROM).orEmpty(),
            to = data.getString(KEY_TO).orEmpty(),
            error = data.getString(KEY_ERROR)?.ifBlank { null },
        )
        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun translate(extras: Bundle?): Bundle {
        if (extras == null) return error("Permintaan kosong")
        val texts = extras.getStringArray(KEY_TEXTS)
            ?: return error("Daftar teks tidak ada")
        if (texts.isEmpty()) return Bundle().apply { putStringArray(KEY_TEXTS, texts) }

        val from = extras.getString(KEY_FROM) ?: LanguageCatalog.AUTO
        val to = extras.getString(KEY_TO) ?: "id"

        return try {
            // Dedupe supaya teks yang sama hanya sekali diterjemahkan, lalu
            // kembalikan hasilnya sesuai urutan asli pemanggil.
            val unique = texts.toList().distinct()
            val result = TranslateEngine.translate(
                from = from,
                to = to,
                texts = unique,
                client = extras.getString("package").orEmpty(),
            )
            val bySource = unique.indices.associate { unique[it] to result.texts[it] }
            val output = texts.map { bySource[it] ?: it }.toTypedArray()

            Bundle().apply {
                putStringArray(KEY_TEXTS, output)
                result.detected?.let { putString(KEY_DETECTED, it) }
                result.error?.let { putString(KEY_ERROR, it) }
            }
        } catch (e: Exception) {
            // Provider tidak boleh melempar ke proses pemanggil; kegagalan
            // dikirim sebagai teks asli plus pesan galat.
            error("Terjemahan gagal: ${e.message}", texts)
        }
    }

    private fun error(message: String, texts: Array<String>? = null): Bundle =
        Bundle().apply {
            putString(KEY_ERROR, message)
            if (texts != null) putStringArray(KEY_TEXTS, texts)
        }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "id.ifan.routtrans.translate"
        const val METHOD_PING = "ping"
        const val METHOD_TRANSLATE = "translate"
        const val METHOD_INJECTED = "injected"
        const val KEY_TEXTS = "texts"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_DETECTED = "detected"
        const val KEY_ERROR = "error"
        const val KEY_PACKAGE = "package"
        const val KEY_PROCESS = "process"
    }
}
