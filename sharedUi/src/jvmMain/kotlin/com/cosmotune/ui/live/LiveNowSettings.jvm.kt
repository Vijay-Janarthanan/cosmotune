package com.cosmotune.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.cosmotune.settings.KeyValueStore
import java.util.prefs.Preferences

private class LivePreferencesStore(private val node: Preferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = node.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = node.putBoolean(key, value)
    override fun getInt(key: String, default: Int): Int = node.getInt(key, default)
    override fun putInt(key: String, value: Int) = node.putInt(key, value)
    override fun getString(key: String, default: String): String = node.get(key, default)
    override fun putString(key: String, value: String) = node.put(key, value)
}

private val processWide: LiveNowSettings by lazy {
    LiveNowSettings(LivePreferencesStore(Preferences.userRoot().node("com.cosmotune.desktop").node("live")))
}

@Composable
actual fun rememberLiveNowSettings(): LiveNowSettings = remember { processWide }
