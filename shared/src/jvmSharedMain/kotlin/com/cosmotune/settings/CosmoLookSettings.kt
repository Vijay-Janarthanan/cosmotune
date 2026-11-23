package com.cosmotune.settings

import com.cosmotune.design.AppMode
import com.cosmotune.design.Intensity
import com.cosmotune.design.VideoView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the look settings are kept: SharedPreferences on Android, java.util.prefs on the desktop, a map in tests. The integer
 * pair has default bodies so that a store written before the intensity setting still compiles (it keeps nothing and answers
 * the default).
 */
interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int = default
    fun putInt(key: String, value: Int) {}
    fun getString(key: String, default: String): String = default
    fun putString(key: String, value: String) {}
}

/**
 * The look's settings (spec section 7; the v2 Settings board) and the colour intensity. Main thread only. The old look and its
 * switch are gone: a `new_look` value an older build stored is never read.
 */
class CosmoLookSettings(private val store: KeyValueStore) {
    private val coverColoursFlow = MutableStateFlow(store.getBoolean(KEY_COVER_COLOURS, true))
    private val intensityFlow = MutableStateFlow(Intensity.clamp(store.getInt(KEY_INTENSITY, Intensity.DEFAULT)))
    private val modeFlow = MutableStateFlow(modeOf(store.getInt(KEY_MODE, AppMode.MUSIC.ordinal)))
    private val npControlsMinimizedFlow = MutableStateFlow(store.getBoolean(KEY_NP_CONTROLS_MINIMIZED, false))
    private val amoledFlow = MutableStateFlow(store.getBoolean(KEY_AMOLED, false))
    private val videoViewFlow = MutableStateFlow(VideoView.kept(store.getInt(KEY_VIDEO_VIEW, VideoView.WHOLE.ordinal)))
    private val fullScreenFillFlow = MutableStateFlow(store.getBoolean(KEY_FULL_SCREEN_FILL, false))
    private val compactRowsFlow = MutableStateFlow(store.getBoolean(KEY_COMPACT_ROWS, false))
    val coverColours: StateFlow<Boolean> = coverColoursFlow.asStateFlow()

    /** How much colour the cover brings: 0 Calm, 1 Balanced (the default), 2 Rich. See [Intensity.multiplier]. */
    val intensity: StateFlow<Int> = intensityFlow.asStateFlow()

    /**
     * The mode the app opens in (the profile drawer's switch): Music until the owner chooses another. Kept as the enum's ordinal;
     * a stored number that is no mode reads as Music.
     */
    val mode: StateFlow<AppMode> = modeFlow.asStateFlow()

    /**
     * Whether Now Playing's controls are minimized to the slim bar over a video (NP-1). Kept across songs and restarts; false
     * (expanded) until the person minimizes them. It only ever shows while a video plays on the phone's portrait page.
     */
    val nowPlayingControlsMinimized: StateFlow<Boolean> = npControlsMinimizedFlow.asStateFlow()

    /**
     * Pure black behind the dark theme (for OLED panels): the page is black and the surface tiers are lifted neutral greys
     * (`CoverPaletteSpec.amoled`). Off until switched on; the light theme ignores it.
     */
    val amoled: StateFlow<Boolean> = amoledFlow.asStateFlow()

    /**
     * How Now Playing shows a song's video: [VideoView.WHOLE] (its own shape, nothing cut; the default) or [VideoView.FILL]
     * (cut to fill the frame). Cinema is entered per visit and is not kept; a stored Cinema or Mini reads as Whole.
     */
    val videoView: StateFlow<VideoView> = videoViewFlow.asStateFlow()

    /**
     * The size of every list row (Look and colours, Row size): Comfortable (false, the default: 60 dp rows with a 48 dp disc) or
     * Compact (true: 52 dp rows with a 40 dp disc).
     */
    val compactRows: StateFlow<Boolean> = compactRowsFlow.asStateFlow()

    fun setCompactRows(on: Boolean) { store.putBoolean(KEY_COMPACT_ROWS, on); compactRowsFlow.value = on }

    private val keepDownloadedFlow = MutableStateFlow(readSet(KEY_KEEP_DOWNLOADED))

    /**
     * The playlists kept downloaded (a playlist page's "Keep downloaded" switch), by browse id: their new songs download on Wi-Fi.
     * Kept here, beside the look, because both hosts already keep this store.
     */
    val keepDownloaded: StateFlow<Set<String>> = keepDownloadedFlow.asStateFlow()

    fun setKeepDownloaded(playlistId: String, on: Boolean) {
        val next = if (on) keepDownloadedFlow.value + playlistId else keepDownloadedFlow.value - playlistId
        writeSet(KEY_KEEP_DOWNLOADED, next)
        keepDownloadedFlow.value = next
    }

    /** The songs "Keep downloaded" has already fetched for [playlistId]: one removed from the device later is not fetched again. */
    fun keptSongs(playlistId: String): Set<String> = readSet(KEY_KEPT_PREFIX + playlistId)

    fun addKeptSongs(playlistId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        writeSet(KEY_KEPT_PREFIX + playlistId, keptSongs(playlistId) + ids)
    }

    /** When "Keep downloaded" last went over the kept playlists away from their pages (ms since the epoch; 0 for never). */
    fun keepDownloadedCheckedMs(): Long = store.getString(KEY_KEEP_CHECKED, "").toLongOrNull() ?: 0L

    fun setKeepDownloadedCheckedMs(ms: Long) = store.putString(KEY_KEEP_CHECKED, ms.toString())

    private val smartDownloadsFlow = MutableStateFlow(store.getInt(KEY_SMART_DOWNLOADS, 0).coerceAtLeast(0))

    /**
     * Smart downloads: how many of the listener's most played songs are kept downloaded (0 for off; 25, 50 or 100). The songs
     * download on Wi-Fi once a day, and a song this fetched is removed again once it falls well out of the most played.
     */
    val smartDownloads: StateFlow<Int> = smartDownloadsFlow.asStateFlow()

    fun setSmartDownloads(count: Int) {
        val kept = count.coerceAtLeast(0)
        store.putInt(KEY_SMART_DOWNLOADS, kept)
        smartDownloadsFlow.value = kept
    }

    /** The songs Smart downloads fetched itself: the only ones it ever removes. */
    fun smartKept(): Set<String> = readSet(KEY_SMART_KEPT)

    fun setSmartKept(ids: Set<String>) = writeSet(KEY_SMART_KEPT, ids)

    private fun readSet(key: String): Set<String> = store.getString(key, "").split(SET_SEPARATOR).filter { it.isNotBlank() }.toSet()

    /** At most the last [MAX_SET] entries: a preferences value has a length limit on the desktop (8 KiB). */
    private fun writeSet(key: String, value: Set<String>) = store.putString(key, value.toList().takeLast(MAX_SET).joinToString(SET_SEPARATOR))

    fun setVideoView(view: VideoView) {
        val kept = VideoView.kept(view.ordinal)
        store.putInt(KEY_VIDEO_VIEW, kept.ordinal)
        videoViewFlow.value = kept
    }

    /**
     * Whether the full-screen player (Now Playing's Cinema, Video's and Watch's full screen) fills the screen, cutting the top and
     * bottom (a pinch out), or shows the picture at its own shape (a pinch in; the default). One choice for all three.
     */
    val fullScreenFill: StateFlow<Boolean> = fullScreenFillFlow.asStateFlow()

    fun setFullScreenFill(fill: Boolean) {
        store.putBoolean(KEY_FULL_SCREEN_FILL, fill)
        fullScreenFillFlow.value = fill
    }

    fun setAmoled(on: Boolean) { store.putBoolean(KEY_AMOLED, on); amoledFlow.value = on }

    fun setCoverColours(on: Boolean) { store.putBoolean(KEY_COVER_COLOURS, on); coverColoursFlow.value = on }

    /** [index] is held to 0..2 before it is kept. */
    fun setIntensity(index: Int) {
        val kept = Intensity.clamp(index)
        store.putInt(KEY_INTENSITY, kept)
        intensityFlow.value = kept
    }

    fun setNowPlayingControlsMinimized(minimized: Boolean) {
        store.putBoolean(KEY_NP_CONTROLS_MINIMIZED, minimized)
        npControlsMinimizedFlow.value = minimized
    }

    fun setMode(mode: AppMode) {
        store.putInt(KEY_MODE, mode.ordinal)
        modeFlow.value = mode
    }

    companion object {
        const val KEY_MODE = "app_mode"
        const val KEY_COVER_COLOURS = "cover_colours"
        const val KEY_INTENSITY = "intensity"
        const val KEY_NP_CONTROLS_MINIMIZED = "np_controls_minimized"
        const val KEY_AMOLED = "amoled"
        const val KEY_VIDEO_VIEW = "np_video_view"
        const val KEY_FULL_SCREEN_FILL = "full_screen_fill"
        const val KEY_COMPACT_ROWS = "compact_rows"
        const val KEY_KEEP_DOWNLOADED = "keep_downloaded"
        const val KEY_SMART_DOWNLOADS = "smart_downloads"
        const val KEY_SMART_KEPT = "smart_downloads_kept"
        const val KEY_KEPT_PREFIX = "kept_songs:"
        const val KEY_KEEP_CHECKED = "keep_downloaded_checked"
        private const val SET_SEPARATOR = ","
        private const val MAX_SET = 600

        private fun modeOf(stored: Int): AppMode = AppMode.entries.getOrNull(stored) ?: AppMode.MUSIC
    }
}
