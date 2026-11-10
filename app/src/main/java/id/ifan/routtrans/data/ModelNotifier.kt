package id.ifan.routtrans.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import id.ifan.routtrans.R

/**
 * Notifikasi kemajuan unduhan model MLKit.
 *
 * MLKit tidak memberi progres byte, jadi kemajuan ditampilkan per model:
 * "2/4" dengan bilah yang berjalan selama satu model diunduh.
 */
object ModelNotifier {

    private const val CHANNEL_ID = "models"
    private const val NOTIFICATION_ID = 1001

    fun progress(context: Context, done: Int, total: Int, from: String, to: String) {
        val manager = manager(context) ?: return
        val text = context.getString(R.string.status_preparing_model, done, total, from, to)
        val notification = builder(context, text)
            // Satu model berjalan tanpa progres byte, jadi bilahnya animasi
            // sampai model itu selesai.
            .setProgress(total, done - 1, true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun finish(context: Context, ready: Int, total: Int) {
        val manager = manager(context) ?: return
        val text = if (ready == total) {
            context.getString(R.string.notification_models_done, ready)
        } else {
            context.getString(R.string.status_models_partial, ready, total)
        }
        val notification = builder(context, text)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        manager(context)?.cancel(NOTIFICATION_ID)
    }

    private fun builder(context: Context, text: String): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notification_models_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun ensureChannel(context: Context) {
        val manager = manager(context) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_models),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_models_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)
}
