package com.cosmotune.music.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The toggles read far from the Settings sheet that sets them.
 *
 * Mirrors Android's `AppSettings` for these few: a composable deep in the player or the lyrics
 * pane can collect one without it being threaded down through every caller.
 */
internal object DesktopAppearanceSettings {

    private fun flag(key: String, default: Boolean) =
        MutableStateFlow(DesktopPersistence().boolean(key, default))

    private val _reduceAnimation = flag(KEY_REDUCE_ANIMATION, false)
    private val _reduceDynamicBlur = flag(KEY_REDUCE_DYNAMIC_BLUR, false)
    private val _hideVolumeBar = flag(KEY_HIDE_VOLUME_BAR, false)
    private val _ambientBackdrop = flag(KEY_AMBIENT_BACKDROP, false)

    /** Freezes the main player's gradient instead of drifting. */
    val reduceAnimation: StateFlow<Boolean> = _reduceAnimation

    /** Swaps frosted glass for solid fills across the app. */
    val reduceDynamicBlur: StateFlow<Boolean> = _reduceDynamicBlur

    /** Removes the volume slider from the main player. */
    val hideVolumeBar: StateFlow<Boolean> = _hideVolumeBar

    /** Diffuses current artwork colors under the window glass chrome like Apple Music. */
    val ambientBackdrop: StateFlow<Boolean> = _ambientBackdrop

    fun setReduceAnimation(value: Boolean) = write(KEY_REDUCE_ANIMATION, value, _reduceAnimation)

    fun setReduceDynamicBlur(value: Boolean) = write(KEY_REDUCE_DYNAMIC_BLUR, value, _reduceDynamicBlur)

    fun setHideVolumeBar(value: Boolean) = write(KEY_HIDE_VOLUME_BAR, value, _hideVolumeBar)

    fun setAmbientBackdrop(value: Boolean) = write(KEY_AMBIENT_BACKDROP, value, _ambientBackdrop)

    private val _openCanvasEnabled = flag(KEY_OPENCANVAS_ENABLED, true)
    private val _openCanvasResolution = MutableStateFlow(
        DesktopPersistence().string(KEY_OPENCANVAS_RESOLUTION, "720p")
    )

    /** Enables OpenCanvas dynamic video canvas streaming. */
    val openCanvasEnabled: StateFlow<Boolean> = _openCanvasEnabled

    /** Full synced vertical music video mode instead of hook loop. */

    /** Preferred stream resolution for OpenCanvas (e.g., 360p, 480p, 720p, 1080p). */
    val openCanvasResolution: StateFlow<String> = _openCanvasResolution

    fun setOpenCanvasEnabled(value: Boolean) {
        write(KEY_OPENCANVAS_ENABLED, value, _openCanvasEnabled)
        // what was found under the old setting must not be served under the new one
        DesktopCanvasClient.forgetAll()
    }


    fun setOpenCanvasResolution(value: String) {
        DesktopPersistence().saveString(KEY_OPENCANVAS_RESOLUTION, value)
        _openCanvasResolution.value = value
        DesktopCanvasClient.forgetAll()
    }

    private fun write(key: String, value: Boolean, into: MutableStateFlow<Boolean>) {
        DesktopPersistence().saveBoolean(key, value)
        into.value = value
    }

    internal const val KEY_REDUCE_ANIMATION = "reduce_animation"
    internal const val KEY_REDUCE_DYNAMIC_BLUR = "reduce_dynamic_blur"
    internal const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    internal const val KEY_AMBIENT_BACKDROP = "ambient_backdrop"
    internal const val KEY_OPENCANVAS_ENABLED = "opencanvas_enabled"
    internal const val KEY_OPENCANVAS_RESOLUTION = "opencanvas_resolution"
}
