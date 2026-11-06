package com.cosmotune.settings

import com.cosmotune.CosmoConfig
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
}

/** The switches of the new look (spec section 7; the v2 Settings board) and the colour intensity. Main thread only. */
class CosmoLookSettings(private val store: KeyValueStore) {
    private val newLookFlow = MutableStateFlow(store.getBoolean(KEY_NEW_LOOK, CosmoConfig.NEW_LOOK_DEFAULT))
    private val coverColoursFlow = MutableStateFlow(store.getBoolean(KEY_COVER_COLOURS, true))
    private val intensityFlow = MutableStateFlow(Intensity.clamp(store.getInt(KEY_INTENSITY, Intensity.DEFAULT)))
    private val modeFlow = MutableStateFlow(modeOf(store.getInt(KEY_MODE, AppMode.MUSIC.ordinal)))
    private val npControlsMinimizedFlow = MutableStateFlow(store.getBoolean(KEY_NP_CONTROLS_MINIMIZED, false))
    private val amoledFlow = MutableStateFlow(store.getBoolean(KEY_AMOLED, false))
    private val videoViewFlow = MutableStateFlow(VideoView.kept(store.getInt(KEY_VIDEO_VIEW, VideoView.WHOLE.ordinal)))
    val newLook: StateFlow<Boolean> = newLookFlow.asStateFlow()
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

    fun setVideoView(view: VideoView) {
        val kept = VideoView.kept(view.ordinal)
        store.putInt(KEY_VIDEO_VIEW, kept.ordinal)
        videoViewFlow.value = kept
    }

    fun setAmoled(on: Boolean) { store.putBoolean(KEY_AMOLED, on); amoledFlow.value = on }

    fun setNewLook(on: Boolean) { store.putBoolean(KEY_NEW_LOOK, on); newLookFlow.value = on }
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
        const val KEY_NEW_LOOK = "new_look"
        const val KEY_COVER_COLOURS = "cover_colours"
        const val KEY_INTENSITY = "intensity"
        const val KEY_NP_CONTROLS_MINIMIZED = "np_controls_minimized"
        const val KEY_AMOLED = "amoled"
        const val KEY_VIDEO_VIEW = "np_video_view"

        private fun modeOf(stored: Int): AppMode = AppMode.entries.getOrNull(stored) ?: AppMode.MUSIC
    }
}
