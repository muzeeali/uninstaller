package com.zeetech.uninstaller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class StorageAlertWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val prefs = context.getSharedPreferences("surgical_uninstaller_prefs", Context.MODE_PRIVATE)

        // Check if the alert notification is enabled by user
        val alertsEnabled = prefs.getBoolean("storage_alerts_enabled", true)
        if (!alertsEnabled) return Result.success()

        // Anti-spam: don't notify more than once per 12 hours
        val lastNotified = prefs.getLong("last_storage_alert_ms", 0L)
        val twelveHoursMs = 12L * 60 * 60 * 1000
        if (System.currentTimeMillis() - lastNotified < twelveHoursMs) return Result.success()

        // Read current storage stats
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes  = stat.availableBytes
        val usedBytes  = totalBytes - freeBytes
        val used = usedBytes.toFloat() / totalBytes.toFloat()   // 0.0 – 1.0

        // Read the user's configured threshold (default 90%)
        val threshold = prefs.getFloat("storage_threshold", 0.9f)

        if (used >= threshold) {
            sendNotification(used, usedBytes, freeBytes)
            prefs.edit().putLong("last_storage_alert_ms", System.currentTimeMillis()).apply()
        }

        return Result.success()
    }

    private fun sendNotification(usedRatio: Float, usedBytes: Long, freeBytes: Long) {
        val channelId = "storage_alert"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Storage Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Warns when device storage is critically low"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val usedPct  = (usedRatio * 100).toInt()
        val freePct  = 100 - usedPct
        val freeGb   = formatBytes(freeBytes)
        val usedGb   = formatBytes(usedBytes)

        // Tap opens the app
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("⚠️ Storage Critical — $usedPct% Used")
            .setContentText("Only $freeGb free ($freePct%). Tap to reclaim space.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "Your storage is $usedPct% full.\n" +
                        "Used: $usedGb   ·   Free: $freeGb ($freePct%)\n\n" +
                        "Open Uninstaller to remove unused apps and free space."
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            gb >= 1.0 -> "%.1f GB".format(gb)
            mb >= 1.0 -> "%.0f MB".format(mb)
            else      -> "$bytes B"
        }
    }

    companion object {
        const val WORK_NAME       = "storage_alert_worker"
        const val NOTIFICATION_ID = 1001

        /** Schedule a periodic check every 6 hours. Safe to call multiple times — replaces existing. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StorageAlertWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Cancel the periodic check. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
