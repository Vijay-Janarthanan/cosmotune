package com.cosmotune.music.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import java.awt.EventQueue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import androidx.compose.ui.graphics.toAwtImage
import kotlinx.coroutines.runBlocking

/** Native macOS Now Playing and remote commands. Inactive on other platforms. */
internal object DesktopMacMedia {
    interface CommandCallback : Callback { fun invoke(command: Int, positionSeconds: Double) }
    interface Api : Library {
        fun cosmo_media_start(callback: CommandCallback)
        fun cosmo_media_update(generation: Long, title: String, artist: String, album: String,
            position: Double, duration: Double, rate: Double, playing: Int)
        fun cosmo_media_artwork(generation: Long, bytes: ByteArray, length: Long)
        fun cosmo_media_stop()
    }
    data class Actions(val play: () -> Unit, val pause: () -> Unit, val toggle: () -> Unit,
        val next: () -> Unit, val previous: () -> Unit, val seek: (Long) -> Unit)
    private val worker by lazy { Executors.newSingleThreadExecutor { Thread(it, "Cosmotune-MacMedia").apply { isDaemon = true } } }
    private val artWorker by lazy { Executors.newSingleThreadExecutor { Thread(it, "Cosmotune-MacMedia-Art").apply { isDaemon = true } } }
    private val epoch = AtomicLong()
    private var generation = 0L
    private var artworkKey: Pair<String, String?>? = null
    @Volatile private var actions: Actions? = null
    private var api: Api? = null
    // Strong reference for the lifetime of native handlers; no collected JNA callback pointers.
    private val callback = object : CommandCallback {
        override fun invoke(command: Int, positionSeconds: Double) {
            val token = epoch.get()
            EventQueue.invokeLater { if (epoch.get() == token) actions?.let { dispatch(it, command, positionSeconds) } }
        }
    }

    fun start(next: Actions) {
        if (!DesktopPlatform.isMac) return
        actions = next
        val token = epoch.incrementAndGet()
        worker.execute {
            if (epoch.get() != token) return@execute
            runCatching {
                if (api == null) {
                    val file = Files.createTempFile("cosmotune-media-", ".dylib").toFile().apply { deleteOnExit() }
                    val input = requireNotNull(javaClass.getResourceAsStream("/native/libcosmotune-media.dylib")) { "Packaged macOS media bridge is missing" }
                    input.use { source -> file.outputStream().use { output -> source.copyTo(output) } }
                    api = Native.load(file.absolutePath, Api::class.java)
                }
                artworkKey = null
                api!!.cosmo_media_start(callback)
            }.onFailure { DesktopTrackLog.log("macOS Now Playing unavailable: ${it.javaClass.simpleName}") }
        }
    }

    fun publish(state: DesktopPlaybackState, rate: Double) {
        if (!DesktopPlatform.isMac || actions == null) return
        val token = epoch.get()
        worker.execute {
            if (epoch.get() != token) return@execute
            val native = api ?: return@execute
            val song = state.song
            val key = song?.videoId.orEmpty() to song?.thumbnailUrl
            val changedArt = key != artworkKey
            if (changedArt) { artworkKey = key; generation++ }
            val currentGeneration = generation
            val moving = state.isPlaying && !state.awaitingAudio && !state.isLoading && state.error == null
            val position = mprisPositionUs(state, rate, System.nanoTime()) / 1_000_000.0
            native.cosmo_media_update(currentGeneration, song?.title.orEmpty(), song?.artist.orEmpty(), song?.albumName.orEmpty(),
                position, state.durationMs.coerceAtLeast(0) / 1000.0, if (moving) rate.coerceIn(.25, 3.0) else 0.0, if (moving) 1 else 0)
            if (changedArt && song?.thumbnailUrl != null) artWorker.execute artwork@{
                val bytes = runCatching {
                    val image = runBlocking { DesktopArtworkCache.load(song.thumbnailUrl) }?.toAwtImage() ?: return@runCatching null
                    ByteArrayOutputStream().use { output -> ImageIO.write(image, "png", output); output.toByteArray() }
                }.getOrNull() ?: return@artwork
                worker.execute { if (epoch.get() == token && generation == currentGeneration && bytes.size <= 4 * 1024 * 1024)
                    api?.cosmo_media_artwork(currentGeneration, bytes, bytes.size.toLong()) }
            }
        }
    }

    fun stop() {
        if (!DesktopPlatform.isMac) return
        actions = null; epoch.incrementAndGet()
        worker.execute { api?.cosmo_media_stop(); artworkKey = null }
    }

    internal fun dispatch(actions: Actions, command: Int, seconds: Double) {
        when (command) {
            0 -> actions.play(); 1, 6 -> actions.pause(); 2 -> actions.toggle()
            3 -> actions.next(); 4 -> actions.previous()
            5 -> if (seconds.isFinite() && seconds >= 0 && seconds < Long.MAX_VALUE / 1000.0) actions.seek((seconds * 1000).toLong())
        }
    }
}
