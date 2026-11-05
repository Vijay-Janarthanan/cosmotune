package com.cosmotune.video.downloads.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import androidx.core.content.ContextCompat
import com.cosmotune.video.downloads.DeviceCaps
import com.cosmotune.video.downloads.DownloadContainer
import com.cosmotune.video.downloads.DownloadController
import com.cosmotune.video.downloads.DownloadKind
import com.cosmotune.video.downloads.DownloadPlatform
import com.cosmotune.video.downloads.DownloadRunner
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.downloads.QueueConditions
import com.cosmotune.video.downloads.SavesCopyToMovies
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_channel
import com.cosmotune.video.resources.video_dl_notification
import com.cosmotune.video.resources.video_dl_pause
import com.cosmotune.video.segments.SegmentProvider
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.source.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import java.io.File
import java.util.Locale

/**
 * The download engine on Android (plan task Y-24, step 5): [DownloadRunner] with the phone's platform. The video and audio go
 * into the app's cache (`cacheDir/video-downloads/`), are joined by [TransmuxJob] (both kept when that fails), and the result
 * goes into the app's own external folder, `getExternalFilesDir(DIRECTORY_MOVIES)/Cosmotune/` (audio only:
 * `DIRECTORY_MUSIC/Cosmotune/`; no permission; removed with the app), with the subtitles and the sidecar beside it. The list is
 * `filesDir/video/downloads.json`. "Only on Wi-Fi" holds the queue while the network is metered; "Pause when the battery is
 * low" while the battery is under 15 % and not charging (checked at every start, and followed through `ACTION_BATTERY_LOW`
 * and `ACTION_BATTERY_OKAY`). While something downloads, [VideoDownloadService] keeps the app in the foreground with the
 * notification. [segments] (the graph's SponsorBlock client) fills the sidecar's segments when online.
 */
class AndroidDownloadController(
    context: Context,
    source: VideoSource,
    settings: VideoSettingsRepository,
    scope: CoroutineScope,
    segments: SegmentProvider? = null,
) : DownloadController by DownloadRunner(source, settings, scope, AndroidDownloadPlatform(context.applicationContext), segments),
    SavesCopyToMovies {
    private val app = context.applicationContext
    private val copier = SaveCopyToMovies(app)

    init {
        current = this
        scope.launch { followForNotification() }
    }

    override suspend fun saveCopyToMovies(id: String): Boolean = downloads.value.firstOrNull { it.id == id }?.let { copier.save(it) } ?: false

    /** Pauses the download running now (the notification's Pause action). */
    internal fun pauseActive() {
        downloads.value.firstOrNull { it.state is DownloadState.Running || it.state == DownloadState.Joining }?.let { pause(it.id) }
    }

    /** One line of the notification: the title, the percentage (null while joining or about to start). */
    private data class Notice(val title: String, val percent: Int?)

    private suspend fun followForNotification() {
        val channel = getString(Res.string.video_dl_channel)
        val pauseLabel = getString(Res.string.video_dl_pause)
        downloads
            .map { list ->
                // the running entry, or the next one about to start (so the service does not stop between two downloads)
                val entry = list.firstOrNull { it.state is DownloadState.Running || it.state == DownloadState.Joining }
                    ?: list.firstOrNull { it.state == DownloadState.Waiting }
                entry?.let { e -> Notice(e.item.title, (e.state as? DownloadState.Running)?.let { (it.fraction * 100).toInt() }) }
            }
            .distinctUntilChanged()
            .collectLatest { notice ->
                if (notice == null) {
                    delay(STOP_AFTER_MS)
                    VideoDownloadService.stop(app)
                } else {
                    VideoDownloadService.show(app, channel, getString(Res.string.video_dl_notification, notice.title), notice.percent, pauseLabel)
                }
            }
    }

    companion object {
        /** The controller of this process, for the notification's Pause action. */
        @Volatile internal var current: AndroidDownloadController? = null

        private const val STOP_AFTER_MS = 1_500L
    }
}

/** The phone's side of the engine: folders, the Transformer, the two holds and the language. */
private class AndroidDownloadPlatform(private val context: Context) : DownloadPlatform {
    override val caps: DeviceCaps by lazy { DeviceCapsAndroid.of() }
    override val cacheDir: File = File(context.cacheDir, "video-downloads")
    override val stateDir: File = File(context.filesDir, "video")

    override fun outputDir(kind: DownloadKind, settings: VideoSettings): File {
        val type = if (kind == DownloadKind.AUDIO) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        // no shared storage mounted: the app's private folder still keeps the download
        val base = context.getExternalFilesDir(type) ?: File(context.filesDir, type)
        return File(base, "Cosmotune")
    }

    override suspend fun join(video: File, audio: File, output: File, container: DownloadContainer): Boolean = TransmuxJob(context).run(video, audio, output)

    private val connectivity: ConnectivityManager? get() = context.getSystemService(ConnectivityManager::class.java)

    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE is the app's
    override fun isMetered(): Boolean = runCatching { connectivity?.isActiveNetworkMetered }.getOrNull() ?: false

    override fun isBatteryLow(): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return QueueConditions.isBatteryLow(percent, charging = plugged)
    }

    @SuppressLint("MissingPermission")
    override val changes: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { trySend(Unit) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        val registered = runCatching { connectivity?.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }.buffer(Channel.CONFLATED)

    override fun freeBytes(settings: VideoSettings): Long = (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir).usableSpace

    private fun locale(): Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

    override val appLanguage: String get() = locale().language

    override fun languageName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(locale()).replaceFirstChar { it.titlecase(locale()) }.ifEmpty { tag }
}
