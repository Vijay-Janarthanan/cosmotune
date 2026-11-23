package com.cosmotune.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeAndLocalSettingsTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
    }

    @Test fun `hidden Home sections and the shelves seen are kept`() {
        val store = MapStore()
        CosmoLookSettings(store).apply {
            setHomeSectionHidden("Quick picks, for you", true)
            setHomeSectionHidden(CosmoLookSettings.HOME_CHIPS, true)
            rememberHomeShelves(listOf("Quick picks, for you", "Mixed for you"))
            rememberHomeShelves(listOf("New releases", "Mixed for you"))
        }
        val again = CosmoLookSettings(store)
        assertEquals(setOf("Quick picks, for you", CosmoLookSettings.HOME_CHIPS), again.hiddenHome.value)
        assertEquals(listOf("New releases", "Mixed for you", "Quick picks, for you"), again.homeShelves.value)
    }

    @Test fun `local rules keep folders with commas whole`() {
        val store = MapStore()
        CosmoLookSettings(store).apply {
            setLocalMinSeconds(60)
            setLocalFolderHidden("/sdcard/Music/Rock, Pop", true)
        }
        val rules = CosmoLookSettings(store).localRules.value
        assertEquals(60, rules.minSeconds)
        assertEquals(setOf("/sdcard/Music/Rock, Pop"), rules.hiddenFolders)
    }

    @Test fun `smart downloads remembers its count and what it fetched`() {
        val store = MapStore()
        CosmoLookSettings(store).apply { setSmartDownloads(50); setSmartKept(setOf("abcdefghijk")) }
        val again = CosmoLookSettings(store)
        assertEquals(50, again.smartDownloads.value)
        assertEquals(setOf("abcdefghijk"), again.smartKept())
    }
}
