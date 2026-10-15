package com.cosmotune.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore

private const val PREFERENCES_NAME = "cosmotune_look"

private class SharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
}

private var processWide: CosmoLookSettings? = null

@Composable
actual fun rememberCosmoLookSettings(): CosmoLookSettings {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        processWide ?: CosmoLookSettings(
            SharedPreferencesStore(appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)),
        ).also { processWide = it }
    }
}
