package com.cosmotune.settings

import com.cosmotune.CosmoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CosmoLookSettingsTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    @Test fun startsFromTheDefaultsAndRemembersChanges() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        assertEquals(CosmoConfig.NEW_LOOK_DEFAULT, settings.newLook.value)
        assertTrue(settings.coverColours.value, "colours follow the cover unless switched off")
        settings.setNewLook(true)
        settings.setCoverColours(false)
        val again = CosmoLookSettings(store)
        assertEquals(true, again.newLook.value)
        assertEquals(false, again.coverColours.value)
    }
}
