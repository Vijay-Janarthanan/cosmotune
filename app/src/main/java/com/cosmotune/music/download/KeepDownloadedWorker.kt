package com.cosmotune.music.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cosmotune.music.MainActivity
import com.cosmotune.music.R
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.ui.pages.KeepDownloaded
import com.cosmotune.ui.settings.cosmoLookSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Keeps the "Keep downloaded" playlists' new songs coming down with the page closed (the playlist switch,
 * [com.cosmotune.settings.CosmoLookSettings.keepDownloaded]): a WorkManager job on Wi-Fi (an unmetered connection) at most once a
 * day while any playlist is kept, and once more as soon as Wi-Fi allows whenever a switch is turned on. Nothing is scheduled while
 * no playlist is kept.
 */
object KeepDownloadedScheduler {
    private const val DAILY = "cosmotune-keep-downloaded"
    private const val SWITCHED_ON = "cosmotune-keep-downloaded-now"
    private const val LIKED_SOON = "cosmotune-keep-liked-soon"
    internal const val KEY_PLAYLISTS = "playlists"
    internal const val KEY_NEWEST_ONLY = "newest_only"

    fun init(context: Context) {
        val app = context.applicationContext
        val look = cosmoLookSettings(app)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var before: Set<String>? = null
            look.keepDownloaded.collect { kept ->
                val work = WorkManager.getInstance(app)
                if (kept.isEmpty()) {
                    work.cancelUniqueWork(DAILY)
                    work.cancelUniqueWork(SWITCHED_ON)
                } else {
                    val daily = PeriodicWorkRequestBuilder<KeepDownloadedWorker>(1, TimeUnit.DAYS)
                        .setConstraints(wifiOnly()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
                    work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, daily)
                    // A switch just turned on: that playlist comes down now (on Wi-Fi), not at the next daily pass.
                    val added = before?.let { kept - it }.orEmpty()
                    if (added.isNotEmpty()) {
                        val now = OneTimeWorkRequestBuilder<KeepDownloadedWorker>().setConstraints(wifiOnly())
                            .setInputData(workDataOf(KEY_PLAYLISTS to added.toTypedArray())).build()
                        work.enqueueUniqueWork(SWITCHED_ON, ExistingWorkPolicy.APPEND_OR_REPLACE, now)
                    }
                }
                before = kept
            }
        }
        // A like, from any surface (the player, a menu, the notification), while Liked Music is kept downloaded: a quick pass over
        // its newest page a little later (upstream 1.10), instead of the next daily one. Each like restarts the wait.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var before = com.cosmotune.music.data.LikeState.overrides.value
            com.cosmotune.music.data.LikeState.overrides.collect { now ->
                if (KeepDownloaded.likedPassWanted(before, now, look.keepDownloaded.value)) {
                    val soon = OneTimeWorkRequestBuilder<KeepDownloadedWorker>().setConstraints(wifiOnly())
                        .setInitialDelay(KeepDownloaded.LIKED_SETTLE_MS, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(KEY_PLAYLISTS to arrayOf(KeepDownloaded.LIKED_MUSIC), KEY_NEWEST_ONLY to true)).build()
                    WorkManager.getInstance(app).enqueueUniqueWork(LIKED_SOON, ExistingWorkPolicy.REPLACE, soon)
                }
                before = now
            }
        }
    }

    private fun wifiOnly(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build()
}

/**
 * One "Keep downloaded" pass ([KeepDownloaded.run]): each kept playlist read whole, its songs that are on neither the device nor
 * fetched by the switch before downloaded here with the app's own downloads (under the playlist, so its page counts them), the rest
 * left alone. The daily run does nothing when a pass ran less than a day ago; a run for a switch just turned on goes over only that
 * playlist.
 */
class KeepDownloadedWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val look = cosmoLookSettings(applicationContext)
        val only = inputData.getStringArray(KeepDownloadedScheduler.KEY_PLAYLISTS)?.toSet()
        val newestOnly = inputData.getBoolean(KeepDownloadedScheduler.KEY_NEWEST_ONLY, false)
        if (look.keepDownloaded.value.isEmpty()) return Result.success()
        if (only == null && !KeepDownloaded.due(look.keepDownloadedCheckedMs(), System.currentTimeMillis())) return Result.success()
        if (!AppSettings.downloadsAllowedNow) return Result.retry()
        return try {
            setForeground(notification())
            withTimeout(45 * 60 * 1000L) {
                KeepDownloaded.run(
                    look,
                    songsOf = { id ->
                        // A like lands at the top: the quick pass after one reads the newest page only.
                        if (newestOnly) YtMusicRepository.browseSongs(id).getOrThrow().songs
                        else YtMusicRepository.completePlaylistSongs(id).getOrThrow()
                    },
                    onDevice = { id -> id in Downloads.saved.value },
                    download = ::fetch,
                    playlists = only ?: look.keepDownloaded.value,
                    stamp = !newestOnly,
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }

    /** Downloads [songs] of [playlistId] here, four at a time, as the playlist-additions job does; fails when any did not land. */
    private suspend fun fetch(playlistId: String, songs: List<Song>) {
        for (song in songs) Downloads.enqueue(applicationContext, song, null, startService = false)
        Downloads.markRequested(playlistId, songs.map { it.videoId })
        Downloads.flushQueueJournal()
        coroutineScope {
            repeat(4) {
                launch {
                    while (true) {
                        val work = Downloads.takeNext() ?: break
                        val task = launch { Downloads.run(applicationContext, work) }
                        Downloads.onRunning(work, task)
                        try { task.join() } finally { Downloads.onIdle(work) }
                    }
                }
            }
        }
        require(songs.all { Downloads.savedUri(applicationContext, it.videoId) != null }) { "Some songs did not download; the next pass tries again." }
    }

    private fun notification(): ForegroundInfo {
        val manager = checkNotNull(applicationContext.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Keep downloaded", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 8118, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle("Keeping playlists downloaded").setContentText("Downloading new songs of the playlists you keep")
            .setContentIntent(open).setOngoing(true).setSilent(true).setProgress(0, 0, true).addAction(0, "Cancel", cancel).build()
        return ForegroundInfo(8118, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object { private const val CHANNEL = "keep_downloaded" }
}
