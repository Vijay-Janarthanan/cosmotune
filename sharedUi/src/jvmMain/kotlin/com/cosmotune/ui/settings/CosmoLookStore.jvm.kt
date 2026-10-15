package com.cosmotune.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import java.util.prefs.Preferences

private class PreferencesStore(private val node: Preferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = node.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = node.putBoolean(key, value)
    override fun getInt(key: String, default: Int): Int = node.getInt(key, default)
    override fun putInt(key: String, value: Int) = node.putInt(key, value)
}

// Our own child of the desktop settings node, apart from the app's own keys and from an installed BitChord.
private val processWide: CosmoLookSettings by lazy {
    CosmoLookSettings(PreferencesStore(Preferences.userRoot().node("com.cosmotune.desktop").node("look")))
}

@Composable
actual fun rememberCosmoLookSettings(): CosmoLookSettings = remember { processWide }
