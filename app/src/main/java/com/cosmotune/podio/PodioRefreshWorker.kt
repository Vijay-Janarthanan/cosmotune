package com.cosmotune.podio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cosmotune.music.MainActivity
import com.cosmotune.music.R
import com.cosmotune.music.download.Downloads
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Podio's background refresh (FEATURES.md #10): every few hours, on an unmetered connection with the battery not low, the followed
 * shows are read again ([PodioRefresh]); new episodes are announced in one notification and, when chosen in Settings › Podio,
 * downloaded. Nothing runs while nothing is followed or the refresh is turned off.
 */
class PodioRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val library = PodioLibrary(libraryFile(applicationContext))
        val found = PodioRefresh.run(library, PodioCatalogue(youtubePodcasts = YouTubePodcasts()))
        if (found.isNotEmpty()) {
            // Each show's own choices (its settings sheet): download, add to Up next, say so.
            val plan = PodioShowRules.apply(found, library, AUTO_DOWNLOADS)
            plan.download.forEach { Downloads.enqueue(applicationContext, it.episode.item().song(), "Podio") }
            if (plan.notify.isNotEmpty()) notify(plan.notify)
        }
        // Played downloads, and those beyond a show's "Downloads to keep", go while the app is closed too.
        runCatching {
            val downloads = Downloads.getDownloadedSongs(applicationContext).mapNotNull(com.cosmotune.music.data.model.Song::offlinePodioItem)
            PodioRetention.toRemove(downloads, library.state.value, playingId = null, queue = library.queue().state.value)
                .forEach { Downloads.delete(applicationContext, it.id) }
        }
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }

    private fun notify(found: List<PodioRefresh.Found>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "New podcast episodes", NotificationManager.IMPORTANCE_DEFAULT))
        val (title, lines) = PodioRefresh.notificationText(found)
        val open = PendingIntent.getActivity(applicationContext, NOTIFICATION, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val style = NotificationCompat.InboxStyle().also { inbox -> lines.forEach(inbox::addLine) }
        manager.notify(NOTIFICATION, NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title).setContentText(lines.firstOrNull().orEmpty()).setStyle(style).setContentIntent(open).setAutoCancel(true).build())
    }

    companion object {
        private const val CHANNEL = "podio_new_episodes"
        private const val NOTIFICATION = 8231
        private const val WORK = "cosmotune-podio-refresh"
        /** At most this many new episodes are downloaded by one refresh. */
        private const val AUTO_DOWNLOADS = 5

        fun libraryFile(context: Context): File = File(context.filesDir, "podio/library.json")

        /** Keeps the periodic refresh scheduled while it is wanted ([on]: the setting is on and a show is followed), and stops it otherwise. */
        fun schedule(context: Context, on: Boolean) {
            val work = WorkManager.getInstance(context.applicationContext)
            if (!on) { work.cancelUniqueWork(WORK); return }
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PodioRefreshWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                    .setInitialDelay(1, TimeUnit.HOURS).build())
        }
    }
}
