package com.cosmotune.settings

import com.cosmotune.CosmoConfig
import com.cosmotune.design.Intensity
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
    val newLook: StateFlow<Boolean> = newLookFlow.asStateFlow()
    val coverColours: StateFlow<Boolean> = coverColoursFlow.asStateFlow()

    /** How much colour the cover brings: 0 Calm, 1 Balanced (the default), 2 Rich. See [Intensity.multiplier]. */
    val intensity: StateFlow<Int> = intensityFlow.asStateFlow()

    fun setNewLook(on: Boolean) { store.putBoolean(KEY_NEW_LOOK, on); newLookFlow.value = on }
    fun setCoverColours(on: Boolean) { store.putBoolean(KEY_COVER_COLOURS, on); coverColoursFlow.value = on }

    /** [index] is held to 0..2 before it is kept. */
    fun setIntensity(index: Int) {
        val kept = Intensity.clamp(index)
        store.putInt(KEY_INTENSITY, kept)
        intensityFlow.value = kept
    }

    companion object {
        const val KEY_NEW_LOOK = "new_look"
        const val KEY_COVER_COLOURS = "cover_colours"
        const val KEY_INTENSITY = "intensity"
    }
}
