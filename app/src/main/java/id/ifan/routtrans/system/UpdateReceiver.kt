package id.ifan.routtrans.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.ifan.routtrans.data.AppLog
import id.ifan.routtrans.root.ModuleManager
import id.ifan.routtrans.root.PayloadUpdater

/**
 * Menyinkronkan payload dan modul tanpa membuka aplikasi.
 *
 * `MY_PACKAGE_REPLACED` dikirim setelah APK diperbarui, sehingga dex payload
 * dan zip modul langsung disalin. `BOOT_COMPLETED` menjaga keduanya tetap
 * mutakhir setelah perangkat menyala.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        val pending = goAsync()
        Thread {
            try {
                AppLog.add(context, "Peristiwa sistem: $action")
                PayloadUpdater.ensureLatest(context)
                if (ModuleManager.ensureLatest(context)) {
                    AppLog.add(context, "Modul diperbarui, menunggu reboot")
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
