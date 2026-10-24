package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.durationMillis
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val MPRIS_ROOT_INTERFACE = "org.mpris.MediaPlayer2"
private const val MPRIS_PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
private const val MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2"
private const val MPRIS_BUS_NAME = "org.mpris.MediaPlayer2.cosmotune"

internal data class MprisCapabilities(
    val canGoNext: Boolean,
    val canGoPrevious: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canSeek: Boolean,
) {
    fun asProperties(): Map<String, Variant<*>> = linkedMapOf(
        "CanGoNext" to Variant(canGoNext),
        "CanGoPrevious" to Variant(canGoPrevious),
        "CanPlay" to Variant(canPlay),
        "CanPause" to Variant(canPause),
        "CanSeek" to Variant(canSeek),
    )

    companion object {
        fun from(playback: DesktopPlaybackState): MprisCapabilities {
            val hasTrack = playback.song != null
            return MprisCapabilities(
                canGoNext = hasTrack,
                canGoPrevious = hasTrack,
                canPlay = hasTrack && playback.error == null,
                canPause = hasTrack,
                canSeek = hasTrack && durationMs(playback.song, playback.durationMs) > 0L,
            )
        }
    }
}

internal class MprisCapabilityTracker {
    private var published: MprisCapabilities? = null

    fun changed(playback: DesktopPlaybackState, force: Boolean = false): Map<String, Variant<*>> {
        val current = MprisCapabilities.from(playback)
        val previous = published?.asProperties()
        published = current
        return current.asProperties().filterTo(linkedMapOf()) { (name, value) ->
            force || previous?.get(name)?.value != value.value
        }
    }
}

private fun durationMs(song: Song?, knownDurationMs: Long): Long =
    knownDurationMs.takeIf { it > 0L } ?: song?.durationText.durationMillis()

/** The object path MPRIS names [song] by; `SetPosition` is ignored for any other track. */
internal fun mprisTrackPath(song: Song): String =
    "$MPRIS_OBJECT_PATH/track/${Integer.toUnsignedString(song.videoId.hashCode(), 16)}"

/**
 * The `Metadata` of [song]: what the shell's media panel shows. Compared by value when publishing,
 * so a length or album that becomes known after the track started is sent too, not only a new track.
 */
internal fun mprisMetadata(song: Song?, knownDurationMs: Long): Map<String, Variant<*>> = song?.let { current ->
    // A LinkedHashMap, deliberately: `buildMap` hands back an internal builder class that
    // dbus-java refuses to marshal.
    LinkedHashMap<String, Variant<*>>().apply {
        put("mpris:trackid", Variant(DBusPath(mprisTrackPath(current))))
        put("xesam:title", Variant(current.title))
        if (current.artist.isNotBlank()) put("xesam:artist", Variant(listOf(current.artist), "as"))
        current.albumName?.takeIf(String::isNotBlank)?.let { put("xesam:album", Variant(it)) }
        durationMs(current, knownDurationMs).takeIf { it > 0L }?.let {
            put("mpris:length", Variant(it * 1_000L))
        }
        current.thumbnailUrl?.takeIf(String::isNotBlank)?.let { put("mpris:artUrl", Variant(it)) }
        current.localUri?.takeIf(String::isNotBlank)?.let { put("xesam:url", Variant(it)) }
    }
} ?: emptyMap()

/**
 * The playhead in microseconds as MPRIS reports it. `Position` is read on demand (playerctl, the
 * shell after a resume), so it is carried forward from the engine's last reading by the time
 * since, at [rate], while audio is actually moving; and never past the end of the track.
 */
internal fun mprisPositionUs(playback: DesktopPlaybackState, rate: Double, nowNanos: Long): Long {
    var ms = playback.positionMs.coerceAtLeast(0L)
    if (playback.isPlaying && !playback.awaitingAudio && playback.positionSampledAtNanos > 0L) {
        val elapsedMs = ((nowNanos - playback.positionSampledAtNanos).coerceAtLeast(0L) / 1_000_000.0 * rate).toLong()
        ms += elapsedMs
    }
    val length = durationMs(playback.song, playback.durationMs)
    if (length > 0L) ms = ms.coerceAtMost(length)
    return ms * 1_000L
}

/** What an MPRIS seek request asks the player to do. */
internal sealed interface MprisSeek {
    data class To(val positionMs: Long) : MprisSeek
    /** `Seek` past the end of the track behaves as `Next`, by the specification. */
    data object Next : MprisSeek
}

internal object MprisSeeks {
    /** `Seek(Offset)`: [offsetUs] is relative to the current position, and may be negative. */
    fun relative(currentUs: Long, offsetUs: Long, durationMs: Long): MprisSeek? {
        if (offsetUs == 0L) return null
        val targetUs = currentUs + offsetUs
        if (durationMs > 0L && targetUs > durationMs * 1_000L) return MprisSeek.Next
        return MprisSeek.To(targetUs.coerceAtLeast(0L) / 1_000L)
    }

    /** `SetPosition(TrackId, Position)`: ignored for another track or a position outside the track. */
    fun absolute(currentTrackPath: String?, requestedTrackPath: String, positionUs: Long, durationMs: Long): MprisSeek? {
        if (currentTrackPath == null || currentTrackPath != requestedTrackPath) return null
        if (positionUs < 0L) return null
        if (durationMs > 0L && positionUs > durationMs * 1_000L) return null
        return MprisSeek.To(positionUs / 1_000L)
    }
}

/** What the player has to tell the bus after a state change: changed properties, and whether it jumped. */
internal data class MprisChanges(val properties: Map<String, Variant<*>>, val seekedToUs: Long?)

/**
 * Works out, from the last published state, what has changed. Kept apart from the bus so the rules
 * can be tested without a Linux session: a property is sent only when it changed (or on `force`,
 * and on the first publish), and a deliberate jump of the playhead within the same track sends
 * `Seeked`, which is how a shell's progress bar learns that the position moved.
 */
internal class MprisStatePublisher {
    private var published = false
    private var metadata: Map<String, String>? = null
    private var playbackStatus: String? = null
    private var shuffle = false
    private var loopStatus = "None"
    private var rate = 1.0
    private var volume = 1.0
    private var songId: String? = null
    private var seeks = 0
    private val capabilities = MprisCapabilityTracker()

    fun changes(
        playback: DesktopPlaybackState,
        shuffle: Boolean,
        loopStatus: String,
        rate: Double,
        nowNanos: Long,
        force: Boolean = false,
    ): MprisChanges {
        val all = force || !published
        val changed = linkedMapOf<String, Variant<*>>()
        val currentMetadata = mprisMetadata(playback.song, playback.durationMs)
        val metadataValues = currentMetadata.mapValues { it.value.value.toString() }
        if (all || metadata != metadataValues) changed["Metadata"] = Variant(currentMetadata, "a{sv}")
        val status = mprisPlaybackStatus(playback)
        if (all || playbackStatus != status) changed["PlaybackStatus"] = Variant(status)
        if (all || this.shuffle != shuffle) changed["Shuffle"] = Variant(shuffle)
        if (all || this.loopStatus != loopStatus) changed["LoopStatus"] = Variant(loopStatus)
        if (all || this.rate != rate) changed["Rate"] = Variant(rate)
        val currentVolume = playback.volume.toDouble().coerceIn(0.0, 1.0)
        if (all || volume != currentVolume) changed["Volume"] = Variant(currentVolume)
        changed.putAll(capabilities.changed(playback, all))
        val sameTrack = published && playback.song != null && playback.song.videoId == songId
        val seeked = if (sameTrack && playback.seeks != seeks) mprisPositionUs(playback, rate, nowNanos) else null

        published = true
        metadata = metadataValues
        playbackStatus = status
        this.shuffle = shuffle
        this.loopStatus = loopStatus
        this.rate = rate
        volume = currentVolume
        songId = playback.song?.videoId
        seeks = playback.seeks
        return MprisChanges(changed, seeked)
    }
}

internal fun mprisPlaybackStatus(playback: DesktopPlaybackState): String = when {
    playback.isPlaying -> "Playing"
    playback.song != null && playback.error == null -> "Paused"
    else -> "Stopped"
}

interface MprisMetadataType : TypeRef<Map<String, Variant<*>>>

@DBusInterfaceName(MPRIS_ROOT_INTERFACE)
@DBusProperty(name = "CanQuit", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "Identity", type = String::class, access = Access.READ)
@DBusProperty(name = "DesktopEntry", type = String::class, access = Access.READ)
@DBusProperty(name = "SupportedUriSchemes", type = MprisUriSchemesType::class, access = Access.READ)
@DBusProperty(name = "SupportedMimeTypes", type = MprisMimeTypesType::class, access = Access.READ)
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

interface MprisUriSchemesType : TypeRef<List<String>>

interface MprisMimeTypesType : TypeRef<List<String>>

@DBusInterfaceName(MPRIS_PLAYER_INTERFACE)
@DBusProperty(name = "Metadata", type = MprisMetadataType::class, access = Access.READ)
@DBusProperty(name = "PlaybackStatus", type = String::class, access = Access.READ)
@DBusProperty(name = "LoopStatus", type = String::class, access = Access.READ_WRITE)
@DBusProperty(name = "Rate", type = Double::class, access = Access.READ_WRITE)
@DBusProperty(name = "Shuffle", type = Boolean::class, access = Access.READ_WRITE)
@DBusProperty(name = "Volume", type = Double::class, access = Access.READ_WRITE)
@DBusProperty(name = "Position", type = Long::class, access = Access.READ)
@DBusProperty(name = "MinimumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = Access.READ)
interface MprisPlayer : DBusInterface {
    fun Previous()
    fun Next()
    fun Stop()
    fun Play()
    fun Pause()
    fun PlayPause()
    fun Seek(offsetUs: Long)
    fun SetPosition(trackId: DBusPath, positionUs: Long)
    fun OpenUri(uri: String)

    /** Sent when the playhead jumps (a seek from anywhere), so the shell's progress follows it. */
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

/** Publishes the desktop player's state through the standard Linux MPRIS session-bus interfaces. */
internal class DesktopMprisController(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onShuffleChanged: (Boolean) -> Unit,
    private val onLoopStatusChanged: (String) -> Unit,
    private val onRateChanged: (Double) -> Unit,
    private val onVolumeChanged: (Double) -> Unit,
    private val onSeek: (Long) -> Unit,
    /** The engine's live state, for `Position` reads between updates; the last update when null. */
    private val livePlayback: (() -> DesktopPlaybackState)? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Cosmotune-MPRIS").apply { isDaemon = true }
    }
    private var connection: DBusConnection? = null
    private var started = false
    private var state = DesktopPlaybackState()
    private var shuffle = false
    private var loopStatus = "None"
    private var rate = 1.0
    private val publisher = MprisStatePublisher()

    fun start() {
        if (!isLinux() || synchronized(lock) { started }) return
        synchronized(lock) { started = true }
        worker.execute {
            runCatching {
                // Not shared.
                val bus = DBusConnectionBuilder.forSessionBus().withShared(false).build()
                bus.requestBusName(MPRIS_BUS_NAME)
                bus.exportObject(MPRIS_OBJECT_PATH, ExportedPlayer(this@DesktopMprisController))
                synchronized(lock) { connection = bus }
                publish(force = true)
            }.onFailure { failure ->
                synchronized(lock) {
                    connection?.close()
                    connection = null
                    started = false
                }
                System.err.println("Cosmotune MPRIS unavailable: ${failure.message}")
            }
        }
    }

    fun stop() {
        val bus = synchronized(lock) {
            started = false
            val current = connection
            connection = null
            current
        }
        runCatching { bus?.close() }
        worker.shutdownNow()
    }

    fun update(
        playback: DesktopPlaybackState,
        shuffle: Boolean,
        loopStatus: String,
        rate: Double,
    ) {
        synchronized(lock) {
            state = playback
            this.shuffle = shuffle
            this.loopStatus = loopStatus
            this.rate = rate.coerceIn(0.25, 3.0)
        }
        publish(force = false)
    }

    private fun publish(force: Boolean) {
        val currentConnection: DBusConnection
        val changes: MprisChanges
        synchronized(lock) {
            currentConnection = connection ?: return
            changes = publisher.changes(state, shuffle, loopStatus, rate, nanoTime(), force)
        }
        if (changes.properties.isNotEmpty()) runCatching {
            currentConnection.sendMessage(
                Properties.PropertiesChanged(
                    MPRIS_OBJECT_PATH,
                    MPRIS_PLAYER_INTERFACE,
                    changes.properties,
                    emptyList(),
                ),
            )
        }
        changes.seekedToUs?.let { position ->
            runCatching { currentConnection.sendMessage(MprisPlayer.Seeked(MPRIS_OBJECT_PATH, position)) }
        }
    }

    /** The playback state `Position` and the seek requests are measured against: the live one when there is one. */
    private fun current(): DesktopPlaybackState = livePlayback?.invoke() ?: synchronized(lock) { state }

    internal fun seekBy(offsetUs: Long) {
        val playback = current()
        val rate = synchronized(lock) { rate }
        val seek = MprisSeeks.relative(
            mprisPositionUs(playback, rate, nanoTime()),
            offsetUs,
            durationMs(playback.song, playback.durationMs),
        )
        when (seek) {
            is MprisSeek.To -> onSeek(seek.positionMs)
            MprisSeek.Next -> onNext()
            null -> Unit
        }
    }

    internal fun seekTo(trackId: String, positionUs: Long) {
        val playback = current()
        val seek = MprisSeeks.absolute(
            playback.song?.let(::mprisTrackPath),
            trackId,
            positionUs,
            durationMs(playback.song, playback.durationMs),
        )
        if (seek is MprisSeek.To) onSeek(seek.positionMs)
    }

    /** One property, answered from the same table as [allProperties]. */
    private fun property(interfaceName: String, propertyName: String): Variant<*> =
        allProperties(interfaceName)[propertyName] ?: unknownProperty(interfaceName, propertyName)

    private fun allProperties(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        MPRIS_ROOT_INTERFACE -> linkedMapOf(
            "CanQuit" to variant(false),
            "CanRaise" to variant(false),
            "HasTrackList" to variant(false),
            "Identity" to variant("Cosmotune"),
            "DesktopEntry" to variant("cosmotune"),
            "SupportedUriSchemes" to variant(listOf("file", "http", "https"), "as"),
            "SupportedMimeTypes" to variant(
                listOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg"),
                "as",
            ),
        )
        MPRIS_PLAYER_INTERFACE -> synchronized(lock) {
            val capabilities = MprisCapabilities.from(state)
            linkedMapOf(
                "Metadata" to variant(mprisMetadata(state.song, state.durationMs), "a{sv}"),
                "PlaybackStatus" to variant(mprisPlaybackStatus(state)),
                "LoopStatus" to variant(loopStatus),
                "Rate" to variant(rate),
                "Shuffle" to variant(shuffle),
                "Volume" to variant(state.volume.toDouble().coerceIn(0.0, 1.0)),
                "Position" to variant(mprisPositionUs(livePlayback?.invoke() ?: state, rate, nanoTime())),
                "MinimumRate" to variant(0.25),
                "MaximumRate" to variant(3.0),
            ).apply {
                putAll(capabilities.asProperties())
                put("CanControl", variant(true))
            }
        }
        else -> unknownProperty(interfaceName, "")
    }

    private fun setProperty(interfaceName: String, propertyName: String, value: Any?) {
        val unwrapped = (value as? Variant<*>)?.value ?: value
        if (interfaceName != MPRIS_PLAYER_INTERFACE) throw unknownProperty(interfaceName, propertyName)
        when (propertyName) {
            "Shuffle" -> (unwrapped as? Boolean)?.let(onShuffleChanged)
                ?: throw DBusExecutionException("Shuffle expects a boolean")
            "LoopStatus" -> (unwrapped as? String)?.let(onLoopStatusChanged)
                ?: throw DBusExecutionException("LoopStatus expects a string")
            "Rate" -> (unwrapped as? Number)?.toDouble()?.let(onRateChanged)
                ?: throw DBusExecutionException("Rate expects a number")
            "Volume" -> (unwrapped as? Number)?.toDouble()?.let(onVolumeChanged)
                ?: throw DBusExecutionException("Volume expects a number")
            else -> throw unknownProperty(interfaceName, propertyName)
        }
    }

    private fun unknownProperty(interfaceName: String, propertyName: String): Nothing =
        throw DBusExecutionException("Unknown property $interfaceName.$propertyName")

    private fun variant(value: Any, signature: String? = null): Variant<*> =
        if (signature == null) Variant(value) else Variant(value, signature)

    private fun isLinux(): Boolean =
        DesktopPlatform.isLinux

    private class ExportedPlayer(private val controller: DesktopMprisController) :
        MprisRoot,
        MprisPlayer,
        Properties {
        override fun getObjectPath(): String = MPRIS_OBJECT_PATH

        override fun Raise() = Unit

        override fun Quit() = Unit

        override fun Previous() = controller.onPrevious()

        override fun Next() = controller.onNext()

        override fun Stop() = controller.onPause()

        override fun Play() = controller.onPlay()

        override fun Pause() = controller.onPause()

        override fun PlayPause() = controller.onPlayPause()

        // Seek is relative by the specification; it used to be taken as a position, so
        // "five seconds on" jumped to 0:05.
        override fun Seek(offsetUs: Long) = controller.seekBy(offsetUs)

        override fun SetPosition(trackId: DBusPath, positionUs: Long) = controller.seekTo(trackId.path, positionUs)

        override fun OpenUri(uri: String) = Unit

        override fun <A> Get(interfaceName: String, propertyName: String): A =
            controller.property(interfaceName, propertyName) as A

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
            controller.setProperty(interfaceName, propertyName, value)
        }

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
            controller.allProperties(interfaceName)
    }
}
