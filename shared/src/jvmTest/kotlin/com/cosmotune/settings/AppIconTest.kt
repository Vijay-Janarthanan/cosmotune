package com.cosmotune.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppIconTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
    }

    private val logo = """<svg><rect fill="#000000"/><path fill="#FFB98F" d="M0"/><path fill="#FFB98F" d="M1"/></svg>"""

    @Test fun `the chosen icon is kept and an unknown one reads as the usual icon`() {
        val store = MapStore()
        CosmoLookSettings(store).setAppIcon(AppIcon.DAWN)
        assertEquals(AppIcon.DAWN, CosmoLookSettings(store).appIcon.value)
        assertEquals(AppIcon.COSMOTUNE, AppIcon.named("SPARKLE"))
        assertEquals(AppIcon.COSMOTUNE, CosmoLookSettings(MapStore()).appIcon.value)
    }

    @Test fun `the desktop logo is recoloured tile and mark`() {
        assertEquals(logo, AppIcon.COSMOTUNE.recolourSvg(logo))
        val dawn = AppIcon.DAWN.recolourSvg(logo)
        assertTrue("""<rect fill="#FFB98F"/>""" in dawn)
        assertEquals(2, Regex("#1C1210").findAll(dawn).count())
        val moon = AppIcon.MOONLIGHT.recolourSvg(logo)
        assertTrue("#000000" in moon)
        assertFalse("#FFB98F" in moon)
    }

    @Test fun `every icon has its own launcher entry`() {
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.alias }.toSet().size)
    }
}
