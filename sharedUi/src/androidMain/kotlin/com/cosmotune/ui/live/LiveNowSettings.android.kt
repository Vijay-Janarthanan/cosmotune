package com.cosmotune.ui.live

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.settings.KeyValueStore

private class LiveSharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
    override fun getInt(key: String, default: Int): Int = preferences.getInt(key, default)
    override fun putInt(key: String, value: Int) = preferences.edit().putInt(key, value).apply()
    override fun getString(key: String, default: String): String = preferences.getString(key, default) ?: default
    override fun putString(key: String, value: String) = preferences.edit().putString(key, value).apply()
}

private var processWide: LiveNowSettings? = null

@Composable
actual fun rememberLiveNowSettings(): LiveNowSettings {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        processWide ?: LiveNowSettings(LiveSharedPreferencesStore(appContext.getSharedPreferences("cosmotune_live", Context.MODE_PRIVATE))).also { processWide = it }
    }
}
