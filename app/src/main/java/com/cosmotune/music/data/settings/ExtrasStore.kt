package com.cosmotune.music.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.cosmotune.music.data.NetworkPolicy
import com.cosmotune.settings.Extras
import com.cosmotune.settings.KeyValueStore

/** The shared [com.cosmotune.settings.ExtrasSettings] on the phone, kept in their own SharedPreferences file. */
object ExtrasStore {
    private const val PREFERENCES_NAME = "cosmotune_extras"

    private class Store(private val preferences: SharedPreferences) : KeyValueStore {
        override fun getBoolean(key: String, default: Boolean) = preferences.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
        override fun getInt(key: String, default: Int) = preferences.getInt(key, default)
        override fun putInt(key: String, value: Int) = preferences.edit().putInt(key, value).apply()
        override fun getString(key: String, default: String) = preferences.getString(key, default) ?: default
        override fun putString(key: String, value: String) = preferences.edit().putString(key, value).apply()
    }

    /** First thing at start, before any HTTP client is built, so every client after it follows the proxy. */
    fun install(context: Context) {
        NetworkPolicy.installAsDefault()
        Extras.install(Store(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)))
    }
}
