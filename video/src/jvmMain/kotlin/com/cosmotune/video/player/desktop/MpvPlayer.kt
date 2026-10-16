package com.cosmotune.video.player.desktop

import com.cosmotune.video.player.PlayerError
import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.PlayerSnapshot
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.SubtitleCue
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_END_FILE_REASON_EOF
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_END_FILE_REASON_ERROR
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_END_FILE_REASON_QUIT
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_END_FILE_REASON_STOP
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_ERROR_NOTHING_TO_PLAY
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_ERROR_OPTION_NOT_FOUND
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_ERROR_UNKNOWN_FORMAT
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_EVENT_END_FILE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_EVENT_FILE_LOADED
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_EVENT_LOG_MESSAGE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_EVENT_NONE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_EVENT_PROPERTY_CHANGE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_EVENT_SHUTDOWN
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_EVENT_START_FILE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_FORMAT_DOUBLE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_FORMAT_FLAG
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_FORMAT_INT64
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_FORMAT_NONE
import com.cosmotune.video.player.desktop.MpvLibrary.Companion.MPV_FORMAT_STRING
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.LongByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * One mpv core for the desktop engine (spec 6.3): created with the options of spec 6.3 set before `mpv_initialize`, warnings
 * and errors requested from its log, the properties the engine needs observed, and one daemon thread that loops
 * `mpv_wait_event` and publishes to [snapshot] (only when something the snapshot holds changes; the position is never in it)
 * and [cues] (from `sub-text`: one cue, replaced on change). The engine owns the snapshot's `muted`, `audioOnly` and
 * `bandwidthKbps`; this class writes the rest.
 *
 * Loads are numbered by the engine ([latestLoad]): while the engine has announced a load this core has not issued yet, the
 * snapshot says LOADING and events about the file on screen are ignored; events about a file a newer load replaced (mpv's
 * playlist entry ids tell) are ignored too, so nothing of an old video leaks into the new one's state.
 *
 * The position is kept apart: the last `time-pos` and when it came, so [positionMs] adds the wall time since while playing
 * and never calls into mpv. A source that expired becomes [PlayerErrorKind.SOURCE_EXPIRED]: an `end-file` with an error on a
 * remote source, a log line with "HTTP error 403", or the proxy's word ([markExpired]).
 *
 * The render context ([MpvRenderer]) must be created before the first [load] and freed before [close].
 */
internal class MpvPlayer(
    private val mpv: MpvLibrary,
    private val snapshot: MutableStateFlow<PlayerSnapshot>,
    private val cues: MutableStateFlow<List<SubtitleCue>>,
    options: Map<String, String> = DEFAULT_OPTIONS,
    /** The number of the engine's latest load (or unload); a load this core has not issued yet keeps the snapshot LOADING. */
    private val latestLoad: () -> Int = { 0 },
    /** The video's size in pixels when it changes (0 x 0 for none); called on the event thread. */
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    /** A file was loaded (seeks and subtitles can be applied now); called on the event thread, which must not block on it. */
    private val onFileLoaded: () -> Unit = {},
    /** Whether the proxy's last trouble was the network rather than the source (an error then is `NETWORK`). */
    private val networkTrouble: () -> Boolean = { false },
) : AutoCloseable {

    /** The properties observed, with their `reply_userdata` (ordinal + 1), their format, and whether they belong to the file. */
    private enum class Observed(val property: String, val format: Int, val ofTheFile: Boolean = true) {
        TIME_POS("time-pos", MPV_FORMAT_DOUBLE),
        DURATION("duration", MPV_FORMAT_DOUBLE),
        PAUSE("pause", MPV_FORMAT_FLAG, ofTheFile = false),
        SPEED("speed", MPV_FORMAT_DOUBLE, ofTheFile = false),
        CACHE_TIME("demuxer-cache-time", MPV_FORMAT_DOUBLE),
        VIDEO_WIDTH("video-params/w", MPV_FORMAT_INT64),
        VIDEO_HEIGHT("video-params/h", MPV_FORMAT_INT64),
        FRAME_DROPS("frame-drop-count", MPV_FORMAT_INT64),
        DECODER_DROPS("decoder-frame-drop-count", MPV_FORMAT_INT64),
        HWDEC("hwdec-current", MPV_FORMAT_STRING),
        SUB_TEXT("sub-text", MPV_FORMAT_STRING),
        EOF("eof-reached", MPV_FORMAT_FLAG),
        PAUSED_FOR_CACHE("paused-for-cache", MPV_FORMAT_FLAG),
        SEEKING("seeking", MPV_FORMAT_FLAG),
    }

    /** The mpv handle. */
    val handle: Pointer = mpv.mpv_create() ?: throw IllegalStateException("mpv_create returned null")

    private val lock = Any()

    /** The engine's number of the load (or unload) this core issued last. */
    @Volatile var issued = 0
        private set

    // State of the current file, guarded by [lock]. [currentEntry]: the playlist entry id of the last load; [startedEntry]: the
    // entry mpv last started. Events of any other entry belong to a file a newer load replaced.
    private var currentEntry = NO_ENTRY
    private var startedEntry = NO_ENTRY
    private var requested = false
    private var loaded = false
    private var paused = true
    private var eof = false
    private var pausedForCache = false
    private var seeking = false
    private var durationMs = 0L
    private var bufferedMs = 0L
    private var speed = 1f
    private var videoWidth = 0
    private var videoHeight = 0
    private var frameDrops = 0L
    private var decoderDrops = 0L
    private var decoder: String? = null
    private var error: PlayerError? = null
    private var remote = false

    /** Whether the current load is a live stream (written under [lock]; read by [positionMs] too). */
    @Volatile private var live = false
    private var lastError: String? = null

    // The playhead, guarded by [clock].
    private val clock = Any()
    private var clockMs = 0L
    private var clockAtNanos = 0L
    private var clockRunning = false
    private var clockSpeed = 1.0

    @Volatile private var running = true
    private val events: Thread

    init {
        try {
            for ((name, value) in options) {
                val result = mpv.mpv_set_option_string(handle, name, value)
                // A build without Lua has no ytdl or scripts to turn off: an option it does not know is no error worth a line.
                if (result < 0 && result != MPV_ERROR_OPTION_NOT_FOUND) {
                    System.err.println("Cosmotune video: mpv option $name: ${mpv.mpv_error_string(result)}")
                }
            }
            val initialized = mpv.mpv_initialize(handle)
            check(initialized >= 0) { "mpv_initialize: ${mpv.mpv_error_string(initialized)}" }
            mpv.mpv_request_log_messages(handle, "warn")
            for (observed in Observed.entries) mpv.mpv_observe_property(handle, observed.ordinal + 1L, observed.property, observed.format)
        } catch (e: Throwable) {
            mpv.mpv_terminate_destroy(handle)
            throw e
        }
        events = Thread(::eventLoop, "cosmotune-mpv-events").apply {
            isDaemon = true
            start()
        }
    }

    /** Whether the engine announced a load this core has not issued yet. */
    fun pending(): Boolean = latestLoad() != issued

    // ---- commands (the engine's thread) -------------------------------------------------------------------------------

    /** `mpv_command`; logs a failure and returns mpv's result. */
    fun command(vararg args: String): Int {
        val result = mpv.mpv_command(handle, arrayOf(*args))
        if (result < 0) System.err.println("Cosmotune video: mpv ${args.first()}: ${mpv.mpv_error_string(result)}")
        return result
    }

    /** `mpv_set_property_string`; logs a failure and returns mpv's result. */
    fun set(name: String, value: String): Int {
        val result = mpv.mpv_set_property_string(handle, name, value)
        if (result < 0) System.err.println("Cosmotune video: mpv set $name: ${mpv.mpv_error_string(result)}")
        return result
    }

    /**
     * Issues the engine's load number [generation]: loads [url] (an `edl://` address, an HLS address, a loopback address or a
     * local path) paused, from [startMs] (live: from the live edge), with video off when [audioOnly], and the request headers
     * mpv itself sends (only HLS reaches the source directly). [remote] decides what an error at the end of the file means.
     */
    fun load(
        generation: Int, url: String, startMs: Long, live: Boolean, remote: Boolean, audioOnly: Boolean, userAgent: String?,
        headerFields: List<String>,
    ) {
        set("pause", "yes")
        set("start", if (live) "none" else seconds(startMs))
        set("stream-lavf-o", if (live) LIVE_LAVF_OPTIONS else "")
        set("user-agent", userAgent ?: DEFAULT_USER_AGENT)
        set("http-header-fields", headerFields.joinToString(","))
        set("vid", if (audioOnly) "no" else "auto")
        // Under the lock, so the event thread knows the new entry's id before it handles that entry's first event.
        synchronized(lock) {
            reset(requested = true)
            this.remote = remote
            this.live = live
            issued = generation
            jumpClock(if (live) 0 else startMs)
            cues.value = emptyList()
            if (command("loadfile", url, "replace") >= 0) currentEntry = int64("playlist/0/id") ?: NO_ENTRY
            else error = PlayerError(PlayerErrorKind.OTHER, "mpv refused the address")
            publish()
        }
    }

    /** Issues the engine's unload number [generation]: stops and forgets the file; the core stays, idle. */
    fun stop(generation: Int) {
        synchronized(lock) {
            reset(requested = false)
            issued = generation
            jumpClock(0)
            cues.value = emptyList()
            command("stop")
            publish()
        }
    }

    /**
     * Issues the engine's load number [generation] as a failure the engine found before mpv could start it ([error]): what
     * played before stops, and the snapshot shows the error.
     */
    fun fail(generation: Int, error: PlayerError) {
        synchronized(lock) {
            reset(requested = true)
            issued = generation
            this.error = error
            jumpClock(0)
            cues.value = emptyList()
            command("stop")
            publish()
        }
    }

    /** Whether the current file is loaded (seeks and subtitles work). */
    fun isLoaded(): Boolean = synchronized(lock) { loaded && !pending() }

    /** Seeks to [ms] (`seek <s> absolute+exact`); the playhead moves there at once. The file must be loaded. */
    fun seek(ms: Long) {
        jumpClock(ms)
        command("seek", seconds(ms), "absolute+exact")
    }

    /** Moves the playhead [positionMs] reports to [ms] now, before mpv confirms (a seek just asked for). */
    fun jumpClock(ms: Long) {
        synchronized(clock) {
            clockMs = ms
            clockAtNanos = System.nanoTime()
        }
    }

    /**
     * Adds the subtitle file at [url] as a track (`sub-add <url> auto <title> <lang>`, not selected), for offline playback
     * where every downloaded language is offered.
     */
    fun addSubtitles(url: String, title: String, language: String) {
        command("sub-add", url, "auto", title, language)
    }

    /**
     * Shows the subtitles at [url] (`sub-add <url> select`, or `cached` when this file already has them, so they are not
     * loaded twice), or none for null (`set sid no`).
     */
    fun showSubtitles(url: String?, alreadyAdded: Boolean) {
        if (url == null) set("sid", "no") else command("sub-add", url, if (alreadyAdded) "cached" else "select")
    }

    /** Reports that the source's addresses expired (the proxy saw a 403 for the current load). */
    fun markExpired(message: String) {
        synchronized(lock) {
            if (error != null || !requested || pending()) return
            error = PlayerError(PlayerErrorKind.SOURCE_EXPIRED, message)
            publish()
        }
    }

    /** The playhead in milliseconds: the last `time-pos` plus the wall time since it while playing. Never calls into mpv. */
    fun positionMs(): Long {
        synchronized(clock) {
            var ms = clockMs
            if (clockRunning) ms += ((System.nanoTime() - clockAtNanos) / 1_000_000.0 * clockSpeed).toLong()
            val duration = durationMs
            // A live stream has no end to stop at (Y-28).
            if (!live && duration > 0 && ms > duration) ms = duration
            return ms.coerceAtLeast(0)
        }
    }

    /** Stops the event thread and destroys the core. Free the render context first. */
    override fun close() {
        if (!running) return
        running = false
        mpv.mpv_wakeup(handle)
        events.join(EVENT_THREAD_JOIN_MS)
        mpv.mpv_terminate_destroy(handle)
    }

    // ---- events (the event thread) ------------------------------------------------------------------------------------

    private fun eventLoop() {
        while (running) {
            val event = mpv.mpv_wait_event(handle, -1.0)
            when (event.getInt(MpvEventLayout.EVENT_ID)) {
                MPV_EVENT_NONE -> Unit
                MPV_EVENT_SHUTDOWN -> return
                MPV_EVENT_PROPERTY_CHANGE -> onProperty(event)
                MPV_EVENT_LOG_MESSAGE -> onLog(event.getPointer(MpvEventLayout.EVENT_DATA))
                MPV_EVENT_START_FILE -> {
                    // struct mpv_event_start_file { int64_t playlist_entry_id; }
                    val entry = event.getPointer(MpvEventLayout.EVENT_DATA)?.getLong(0) ?: NO_ENTRY
                    synchronized(lock) { startedEntry = entry }
                }
                MPV_EVENT_FILE_LOADED -> {
                    val current = synchronized(lock) {
                        val isCurrent = isCurrentFile()
                        if (isCurrent) {
                            loaded = true
                            publish()
                        }
                        isCurrent
                    }
                    if (current) onFileLoaded()
                }
                MPV_EVENT_END_FILE -> onEndFile(event.getPointer(MpvEventLayout.EVENT_DATA))
            }
        }
    }

    /** Whether the events now arriving are about the file of the load this core issued last (and no newer one is announced). Under [lock]. */
    private fun isCurrentFile(): Boolean = requested && currentEntry != NO_ENTRY && startedEntry == currentEntry && !pending()

    private fun onProperty(event: Pointer) {
        val id = event.getLong(MpvEventLayout.EVENT_REPLY_USERDATA).toInt() - 1
        val observed = Observed.entries.getOrNull(id) ?: return
        val property = event.getPointer(MpvEventLayout.EVENT_DATA) ?: return
        val format = property.getInt(MpvEventLayout.PROPERTY_FORMAT)
        val data = if (format == MPV_FORMAT_NONE) null else property.getPointer(MpvEventLayout.PROPERTY_DATA)
        if (observed == Observed.TIME_POS) {
            // Every frame: only the playhead moves, the snapshot does not.
            if (data != null) synchronized(lock) {
                if (isCurrentFile()) synchronized(clock) {
                    clockMs = (data.getDouble(0) * 1000).toLong()
                    clockAtNanos = System.nanoTime()
                }
            }
            return
        }
        if (observed == Observed.SUB_TEXT) {
            val current = synchronized(lock) { isCurrentFile() }
            if (current) onSubText(if (format == MPV_FORMAT_STRING) data?.getPointer(0)?.getString(0, "UTF-8") else null)
            return
        }
        var size: Long = -1
        synchronized(lock) {
            if (observed.ofTheFile && !isCurrentFile()) return
            val changed = when (observed) {
                Observed.DURATION -> assign(data?.let { (it.getDouble(0) * 1000).toLong() } ?: 0L, durationMs) { durationMs = it }
                Observed.PAUSE -> assign(data?.getInt(0) == 1, paused) { paused = it }
                Observed.SPEED -> assign(data?.getDouble(0)?.toFloat() ?: 1f, speed) { speed = it }
                Observed.CACHE_TIME -> {
                    // Many times a second while reading ahead: published in whole seconds only.
                    val ms = data?.let { (it.getDouble(0) * 1000).toLong() / BUFFER_STEP_MS * BUFFER_STEP_MS } ?: 0L
                    assign(ms, bufferedMs) { bufferedMs = it }
                }
                Observed.VIDEO_WIDTH -> assign(data?.getLong(0)?.toInt() ?: 0, videoWidth) { videoWidth = it }
                Observed.VIDEO_HEIGHT -> assign(data?.getLong(0)?.toInt() ?: 0, videoHeight) { videoHeight = it }
                Observed.FRAME_DROPS -> assign(data?.getLong(0) ?: 0L, frameDrops) { frameDrops = it }
                Observed.DECODER_DROPS -> assign(data?.getLong(0) ?: 0L, decoderDrops) { decoderDrops = it }
                Observed.HWDEC -> assign(decoderLabel(if (format == MPV_FORMAT_STRING) data?.getPointer(0)?.getString(0, "UTF-8") else null), decoder) { decoder = it }
                Observed.EOF -> assign(data?.getInt(0) == 1, eof) { eof = it }
                Observed.PAUSED_FOR_CACHE -> assign(data?.getInt(0) == 1, pausedForCache) { pausedForCache = it }
                Observed.SEEKING -> assign(data?.getInt(0) == 1, seeking) { seeking = it }
                Observed.TIME_POS, Observed.SUB_TEXT -> false
            }
            if (changed) {
                publish()
                if (observed == Observed.VIDEO_WIDTH || observed == Observed.VIDEO_HEIGHT) size = FrameGeometry.pack(videoWidth, videoHeight)
            }
        }
        if (size >= 0) onVideoSize(FrameGeometry.widthOf(size), FrameGeometry.heightOf(size))
    }

    private inline fun <T> assign(value: T, current: T, store: (T) -> Unit): Boolean {
        if (value == current) return false
        store(value)
        return true
    }

    private fun onSubText(text: String?) {
        if (text.isNullOrBlank()) {
            cues.value = emptyList()
            return
        }
        val start = property("sub-start")?.let { (it * 1000).toLong() } ?: positionMs()
        val end = property("sub-end")?.let { (it * 1000).toLong() } ?: start
        cues.value = listOf(SubtitleCue(text.trimEnd('\n'), start, end))
    }

    private fun onLog(message: Pointer?) {
        message ?: return
        val level = message.getPointer(MpvEventLayout.LOG_LEVEL)?.getString(0, "UTF-8") ?: return
        val text = message.getPointer(MpvEventLayout.LOG_TEXT)?.getString(0, "UTF-8")?.trimEnd() ?: return
        if (level == "error" || level == "fatal") {
            val prefix = message.getPointer(MpvEventLayout.LOG_PREFIX)?.getString(0, "UTF-8")
            System.err.println("Cosmotune video: mpv [$prefix] $text")
        }
        synchronized(lock) {
            if (!isCurrentFile()) return // a line about a file a newer load replaced
            if (level == "error" || level == "fatal") lastError = text
            if (EXPIRED_LOG in text && error == null) {
                error = PlayerError(PlayerErrorKind.SOURCE_EXPIRED, text)
                publish()
            }
        }
    }

    private fun onEndFile(data: Pointer?) {
        data ?: return
        val reason = data.getInt(MpvEventLayout.END_FILE_REASON)
        val code = data.getInt(MpvEventLayout.END_FILE_ERROR)
        val entry = data.getLong(MpvEventLayout.END_FILE_ENTRY)
        synchronized(lock) {
            if (entry != currentEntry || pending()) return // the file a newer load replaced
            when (reason) {
                MPV_END_FILE_REASON_ERROR -> if (error == null) {
                    val kind = when {
                        remote && networkTrouble() -> PlayerErrorKind.NETWORK
                        remote -> PlayerErrorKind.SOURCE_EXPIRED
                        code == MPV_ERROR_UNKNOWN_FORMAT || code == MPV_ERROR_NOTHING_TO_PLAY -> PlayerErrorKind.DECODER
                        else -> PlayerErrorKind.OTHER
                    }
                    error = PlayerError(kind, lastError ?: mpv.mpv_error_string(code) ?: "playback failed ($code)")
                }
                MPV_END_FILE_REASON_EOF -> eof = true
                MPV_END_FILE_REASON_STOP, MPV_END_FILE_REASON_QUIT -> {
                    loaded = false
                    requested = false
                }
                else -> return
            }
            publish()
        }
    }

    /** Forgets the file's state. Under [lock]. */
    private fun reset(requested: Boolean) {
        this.requested = requested
        currentEntry = NO_ENTRY
        loaded = false
        eof = false
        pausedForCache = false
        seeking = false
        durationMs = 0
        bufferedMs = 0
        videoWidth = 0
        videoHeight = 0
        frameDrops = 0
        decoderDrops = 0
        decoder = null
        error = null
        lastError = null
        live = false
    }

    /** Publishes the snapshot's mpv part and sets the playhead's clock going or stopped. Under [lock]. */
    private fun publish() {
        val announced = pending()
        // A live stream's duration is the end of its window (Y-28): what can be sought in is what mpv has read since it joined (its
        // cache keeps it), from 0 to the end of the cache; FFmpeg gives a live playlist no duration of its own.
        val window = if (live) bufferedMs else durationMs
        val status = when {
            announced -> PlayerStatus.LOADING
            error != null -> PlayerStatus.ERROR
            !loaded -> if (requested) PlayerStatus.LOADING else PlayerStatus.IDLE
            eof -> PlayerStatus.ENDED
            seeking || pausedForCache -> PlayerStatus.BUFFERING
            else -> PlayerStatus.READY
        }
        val playing = !paused && status != PlayerStatus.ERROR && status != PlayerStatus.ENDED && status != PlayerStatus.IDLE
        snapshot.update {
            if (announced) {
                it.copy(status = status, playing = playing, speed = speed, error = null)
            } else {
                it.copy(
                    status = status, playing = playing, durationMs = window, bufferedMs = bufferedMs, speed = speed,
                    videoWidth = videoWidth, videoHeight = videoHeight, error = error, decoderName = decoder,
                    droppedFrames = (frameDrops + decoderDrops).toInt(),
                )
            }
        }
        val run = playing && status == PlayerStatus.READY
        synchronized(clock) {
            if (run != clockRunning || clockSpeed != speed.toDouble()) {
                // Fold the time run so far into the base before the clock changes pace.
                val now = System.nanoTime()
                if (clockRunning) clockMs += ((now - clockAtNanos) / 1_000_000.0 * clockSpeed).toLong()
                clockAtNanos = now
                clockRunning = run
                clockSpeed = speed.toDouble()
            }
        }
    }

    private fun property(name: String): Double? {
        val value = DoubleByReference()
        return if (mpv.mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, value) >= 0) value.value else null
    }

    private fun int64(name: String): Long? {
        val value = LongByReference()
        return if (mpv.mpv_get_property(handle, name, MPV_FORMAT_INT64, value) >= 0) value.value else null
    }

    companion object {
        /**
         * The options of spec 6.3, set before `mpv_initialize`, and a few that keep libmpv quiet in an embedding (no scripts, no
         * on-screen text, no key bindings). Live streams add [LIVE_LAVF_OPTIONS] per load; the variant of FFmpeg's reconnect
         * for streamed input is never set (it breaks live HLS, spec 2.4).
         */
        val DEFAULT_OPTIONS: Map<String, String> = buildMap {
            put("vo", "libmpv")
            put("hwdec", "auto-copy")
            put("ytdl", "no")
            put("keep-open", "yes")
            put("cache", "yes")
            put("demuxer-max-bytes", "150MiB")
            put("demuxer-readahead-secs", "30")
            put("sub-visibility", "no")
            put("audio-pitch-correction", "yes")
            if (System.getProperty("os.name").contains("mac", ignoreCase = true)) put("ao", "avfoundation")
            put("idle", "yes")
            put("load-scripts", "no")
            put("osd-level", "0")
            put("input-default-bindings", "no")
            put("input-vo-keyboard", "no")
            put("audio-client-name", "Cosmotune")
        }

        /** FFmpeg's reconnect options for live HLS (spec 6.3). */
        const val LIVE_LAVF_OPTIONS = "reconnect=1,reconnect_delay_max=30"

        /** The log text that means the source refused with 403 (FFmpeg's HTTP protocol). */
        const val EXPIRED_LOG = "HTTP error 403"

        /** mpv's own User-Agent, restored for loads that do not reach the source directly. */
        const val DEFAULT_USER_AGENT = "libmpv"

        /** The buffered position is published in steps of this many milliseconds. */
        const val BUFFER_STEP_MS = 1000L

        private const val EVENT_THREAD_JOIN_MS = 2000L

        /** No playlist entry (mpv's ids start at 1). */
        private const val NO_ENTRY = -1L

        /** `hwdec-current` as the stats card shows it: "software" for none, else "hardware (<api>)". */
        internal fun decoderLabel(hwdec: String?): String? = when {
            hwdec == null -> null
            hwdec.isBlank() || hwdec == "no" -> "software"
            else -> "hardware ($hwdec)"
        }

        /** Seconds with three decimals and a dot, as mpv reads them. */
        internal fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)
    }
}
