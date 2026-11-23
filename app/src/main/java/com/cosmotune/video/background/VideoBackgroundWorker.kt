package com.cosmotune.video.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cosmotune.music.R
import com.cosmotune.video.alerts.alertLink
import com.cosmotune.video.alerts.checkNewUploads
import com.cosmotune.video.alerts.runAutoBackupIfDue
import com.cosmotune.video.settings.needsBackgroundWork
import com.cosmotune.video.feed.UploadAlerts
import com.cosmotune.video.graph.androidVideoGraph
import java.util.concurrent.TimeUnit

/**
 * Video mode's background work on the phone, every hour or so on a network and a battery that is not low: the new-video alerts (the
 * feed of the channels whose bell is on is read and their new uploads shown as one notification that opens the newest) and the
 * automatic library backup to Downloads/Cosmotune backups when it is due. Nothing is read while neither is on.
 */
class VideoBackgroundWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = androidVideoGraph(applicationContext)
        if (!graph.settings.settings.value.needsBackgroundWork()) return Result.success()
        runAutoBackupIfDue(graph, DownloadsBackupFolder(applicationContext))
        val items = checkNewUploads(graph)
        val (title, text) = UploadAlerts.text(items) ?: return Result.success()
        notify(applicationContext, title, text, alertLink(items.first()))
        return Result.success()
    }

    companion object {
        private const val NAME = "cosmotune-video-new-uploads"
        private const val CHANNEL = "video-new-uploads"
        private const val ID = 7_301

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<VideoBackgroundWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        private fun notify(context: Context, title: String, text: String, link: String) {
            val notifications = NotificationManagerCompat.from(context)
            if (!notifications.areNotificationsEnabled()) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "New videos", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val tap = PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            runCatching { notifications.notify(ID, notification) }
        }
    }
}
