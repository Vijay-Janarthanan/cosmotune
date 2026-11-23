package com.cosmotune.listening

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * New releases from followed artists, in the background: about once a day, with "Check daily and tell me" on in Listening
 * tools > New releases, the due artists are read ([ReleaseChecks]) and what is new is announced in one notification. A single
 * release opens its page; several open the app.
 */
class ReleaseCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val store = ListeningStore(listeningFile(applicationContext))
        if (!store.state.value.preferences.autoCheckReleases || store.state.value.artists.isEmpty()) {
            Result.success()
        } else {
            val outcome = ReleaseChecks.run(store, ListeningNetwork::releases)
            notify(applicationContext, outcome.found)
            if (outcome.failures > 0 && outcome.failures == store.state.value.artists.size) Result.retry() else Result.success()
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }

    companion object {
        private const val CHANNEL = "music_new_releases"
        private const val NOTIFICATION = 8232
        private const val WORK = "cosmotune-release-check"

        fun listeningFile(context: Context): File = File(context.filesDir, "cosmotune-listening.json")

        /** Keeps the daily check scheduled while [on], and stops it otherwise. */
        fun schedule(context: Context, on: Boolean) {
            val work = WorkManager.getInstance(context.applicationContext)
            if (!on) { work.cancelUniqueWork(WORK); return }
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReleaseCheckWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                    .setInitialDelay(2, TimeUnit.HOURS).build())
        }

        /** One notification for [found] (nothing when empty, or when notifications are not allowed). */
        fun notify(context: Context, found: List<ReleaseNotice>) {
            if (found.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "New releases from artists you follow", NotificationManager.IMPORTANCE_DEFAULT))
            val (title, lines) = ReleaseChecks.notificationText(found)
            val target = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (found.size == 1) {
                // The release's own page, through the same path as a shared YouTube Music link.
                target.setAction(Intent.ACTION_VIEW).setData(Uri.parse("https://music.youtube.com/browse/${found[0].id}"))
            }
            val open = PendingIntent.getActivity(context, NOTIFICATION, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val style = NotificationCompat.InboxStyle().also { inbox -> lines.forEach(inbox::addLine) }
            manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(title).setContentText(lines.firstOrNull().orEmpty()).setStyle(style).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
