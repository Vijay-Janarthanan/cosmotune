package com.cosmotune.music.download

import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.cosmotune.listening.PlaylistDownloadUpdates
import com.cosmotune.music.MainActivity
import com.cosmotune.music.R
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.ui.listening.fetchYouTubePlaylist
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Restarts from persistent opt-in watches after process death; default-off installs schedule no job. */
object PlaylistDownloadScheduler {
    private const val NAME = "cosmotune-playlist-additions"
    fun init(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val store = runCatching { PlaylistDownloadUpdates.open(File(app.filesDir, "playlist-download-updates.json")) }.getOrNull() ?: return@launch
            var scheduled = false
            combine(store.watches, AppSettings.wifiOnlyDownloads) { watches, wifiOnly -> watches.any { it.enabled } to wifiOnly }.collect { (enabled, wifiOnly) ->
                if (enabled) {
                    val request = PeriodicWorkRequestBuilder<PlaylistDownloadWorker>(12, TimeUnit.HOURS, 4, TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
                    WorkManager.getInstance(app).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
                    scheduled = true
                } else if (scheduled || store.watches.value.isNotEmpty()) {
                    WorkManager.getInstance(app).cancelUniqueWork(NAME)
                    scheduled = false
                }
            }
        }
    }
}

class PlaylistDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = try { PlaylistDownloadUpdates.open(File(applicationContext.filesDir, "playlist-download-updates.json")) }
        catch (_: Exception) { return Result.failure() }
        if (store.watches.value.none { it.enabled }) return Result.success()
        if (!AppSettings.downloadsAllowedNow) return Result.retry()
        return try {
            setForeground(notification())
            withTimeout(45 * 60 * 1000L) {
                store.check({ source, id ->
                    require(source == "youtube") { "This playlist source is unavailable." }
                    fetchYouTubePlaylist(id)
                }, enqueue = { songs, title ->
                    val missing = songs.filter { Downloads.savedUri(applicationContext, it.videoId) == null }
                    for (song in missing) Downloads.enqueue(applicationContext, song, title, startService = false)
                    Downloads.flushQueueJournal()
                    coroutineScope {
                        repeat(4) { launch {
                            while (true) {
                                val work = Downloads.takeNext() ?: break
                                val task = launch { Downloads.run(applicationContext, work) }
                                Downloads.onRunning(work, task)
                                try { task.join() } finally { Downloads.onIdle(work) }
                            }
                        } }
                    }
                    require(missing.all { Downloads.savedUri(applicationContext, it.videoId) != null }) { "Some additions failed; retry on the next check." }
                }, force = true)
            }
            if (store.watches.value.any { it.enabled && it.error != null }) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }

    private fun notification(): ForegroundInfo {
        val manager = checkNotNull(applicationContext.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playlist additions", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 8117, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle("Updating downloaded playlists").setContentText("Checking opted-in playlists for new songs")
            .setContentIntent(open).setOngoing(true).setSilent(true).setProgress(0, 0, true).addAction(0, "Cancel", cancel).build()
        return ForegroundInfo(8117, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object { private const val CHANNEL = "playlist_additions" }
}
