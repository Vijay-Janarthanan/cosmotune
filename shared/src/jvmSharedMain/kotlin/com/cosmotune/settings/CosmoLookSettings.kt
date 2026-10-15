package com.cosmotune.settings

import com.cosmotune.CosmoConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the look switches are kept: SharedPreferences on Android, java.util.prefs on the desktop, a map in tests. */
interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/** The two switches of the new look (spec section 7; the v2 Settings board). Main thread only. */
class CosmoLookSettings(private val store: KeyValueStore) {
    private val newLookFlow = MutableStateFlow(store.getBoolean(KEY_NEW_LOOK, CosmoConfig.NEW_LOOK_DEFAULT))
    private val coverColoursFlow = MutableStateFlow(store.getBoolean(KEY_COVER_COLOURS, true))
    val newLook: StateFlow<Boolean> = newLookFlow.asStateFlow()
    val coverColours: StateFlow<Boolean> = coverColoursFlow.asStateFlow()

    fun setNewLook(on: Boolean) { store.putBoolean(KEY_NEW_LOOK, on); newLookFlow.value = on }
    fun setCoverColours(on: Boolean) { store.putBoolean(KEY_COVER_COLOURS, on); coverColoursFlow.value = on }

    companion object {
        const val KEY_NEW_LOOK = "new_look"
        const val KEY_COVER_COLOURS = "cover_colours"
    }
}
