package com.cosmotune.music.ui.tv

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.music.data.cast.dlna.DlnaDiscovery
import com.cosmotune.music.data.cast.dlna.DlnaReceiver
import com.cosmotune.music.data.cast.dlna.DlnaReceiverHost
import com.cosmotune.music.data.cast.dlna.ReceivedMedia
import com.cosmotune.music.data.cast.dlna.ReceiverStatus
import com.cosmotune.music.data.settings.TvSettings
import com.cosmotune.music.watch.AndroidWatch
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchRoute
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.model.Container
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * While "Play from your phone" is on in TV settings, this TV answers on the Wi-Fi as a video receiver: a phone's or computer's
 * Play on a TV (or any DLNA app) sends a video, it opens in Watch's player here ([onPlay] brings Watch forward), and the
 * sender's play, pause, seek, stop and volume work this player.
 */
@Composable
internal fun TvWatchReceiver(onPlay: () -> Unit) {
    val enabled by TvSettings.tvWatchReceiver.collectAsState()
    val context = LocalContext.current.applicationContext
    val play = rememberUpdatedState(onPlay)
    DisposableEffect(enabled, context) {
        val receiver = if (!enabled) null else runCatching {
            val address = lanAddress() ?: error("No Wi-Fi address")
            DlnaReceiver(ReceiverHost(context) { play.value() }, "Cosmotune on ${deviceName(context)}", address)
        }.getOrNull()
        // The TV must hold Wi-Fi's multicast lock to hear the phone's search.
        val hold = receiver?.let { runCatching { DlnaDiscovery.multicastHold?.invoke() }.getOrNull() }
        onDispose { runCatching { hold?.close() }; receiver?.close() }
    }
}

/** What a received video plays as: an item of its own, never kept in Continue watching (the sender keeps its place). */
internal object ReceivedVideo {
    const val PLUGIN = "received"

    fun route(media: ReceivedMedia): WatchRoute.Play {
        val id = Integer.toHexString(media.url.hashCode())
        val target = PlayTarget.Item(WatchKey.item(PLUGIN, id), PLUGIN, id, media.title)
        val hls = media.mimeType?.contains("mpegurl", true) == true || media.url.substringBefore('?').endsWith(".m3u8", true)
        val stream = Stream(StreamSource(PLUGIN, "Sent here", "SENT"), "Sent to this TV", url = media.url,
            protocol = if (hls) StreamProtocol.HLS else StreamProtocol.PROGRESSIVE,
            container = when {
                media.mimeType?.contains("webm") == true -> Container.WEBM
                media.mimeType?.contains("matroska") == true -> Container.MKV
                else -> Container.UNKNOWN
            },
            durationMs = media.durationMs.takeIf { it > 0 })
        val summary = TitleSummary(target.key, TitleType.FILM, media.title, posterUrl = media.artworkUrl, origin = PLUGIN)
        return WatchRoute.Play(target, summary, stream)
    }
}

private class ReceiverHost(private val context: Context, private val bringForward: () -> Unit) : DlnaReceiverHost {
    private val audio = context.getSystemService(AudioManager::class.java)

    /** Runs [block] on the main thread (the player lives there) and waits for it, at most a second. */
    private fun <T> main(block: () -> T): T? = runBlocking { withTimeoutOrNull(1_000) { kotlinx.coroutines.withContext(Dispatchers.Main) { block() } } }

    private fun ours(): Boolean = (WatchNav.stack.value.lastOrNull() as? WatchRoute.Play)?.target?.key?.value?.startsWith("a:${ReceivedVideo.PLUGIN}:") == true

    override fun play(media: ReceivedMedia) {
        main {
            val now = WatchNav.stack.value
            // A new video takes the place of the one sent before it.
            WatchNav.stack.value = (if (ours()) now.dropLast(1) else now) + ReceivedVideo.route(media)
            bringForward()
        }
    }

    override fun resume() { main { if (ours()) AndroidWatch.player(context).engine.play() } }
    override fun pause() { main { if (ours()) AndroidWatch.player(context).engine.pause() } }
    override fun seek(positionMs: Long) { main { if (ours()) AndroidWatch.player(context).engine.seekTo(positionMs) } }

    override fun stop() {
        main { if (ours()) { AndroidWatch.player(context).stop(); WatchNav.back() } }
    }

    override fun status(): ReceiverStatus = main {
        if (!ours() || !AndroidWatch.playerBuilt) return@main ReceiverStatus("STOPPED")
        val player = AndroidWatch.player(context)
        val state = player.state.value
        val transport = when (state.status) {
            WatchPlayerStatus.PLAYING -> "PLAYING"
            WatchPlayerStatus.PAUSED -> "PAUSED_PLAYBACK"
            WatchPlayerStatus.RESOLVING, WatchPlayerStatus.LOADING, WatchPlayerStatus.BUFFERING -> "TRANSITIONING"
            else -> "STOPPED"
        }
        ReceiverStatus(transport, player.engine.positionMs(), state.durationMs)
    } ?: ReceiverStatus("TRANSITIONING")

    override fun volume(): Int? = audio?.let {
        val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { m -> m > 0 } ?: return null
        it.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    override fun setVolume(level: Int) {
        val manager = audio ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max + 50) / 100, 0)
    }
}

/** The TV's Wi-Fi or Ethernet address (IPv4, the one phones search on). */
private fun lanAddress() = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
    .flatMap { it.inetAddresses.toList() }
    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

/** "Living room TV": the name the owner gave the TV, else its model. */
private fun deviceName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: Build.MODEL.orEmpty().ifBlank { "TV" }
