package com.cosmotune.music.desktop

import com.cosmotune.ui.theme.ThemeChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The desktop's theme choice kept and read back, in a map rather than the desktop's real preferences: what a choice stores, that a
 * new reading (the next launch) finds it, and that nothing stored, or an old value, follows the system.
 */
class DesktopThemeSettingTest {
    private val store = mutableMapOf<String, String>()
    private fun setting() = DesktopThemeSetting(read = { store[KEY] }, write = { store[KEY] = it })

    @Test fun nothingStoredFollowsTheSystem() {
        assertEquals(ThemeChoice.SYSTEM, setting().choice.value)
        assertNull(store[KEY], "reading stores nothing")
    }

    @Test fun eachChoiceIsReadBackByTheNextLaunch() {
        for (choice in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK, ThemeChoice.SYSTEM)) {
            val now = setting()
            now.set(choice)
            assertEquals(choice, now.choice.value, "the running app sees it at once")
            assertEquals(choice.name, store[KEY], "kept by its name")
            assertEquals(choice, setting().choice.value, "and the next launch reads it back")
        }
    }

    @Test fun anOldOrUnknownValueFollowsTheSystem() {
        store[KEY] = "AMOLED"
        assertEquals(ThemeChoice.SYSTEM, setting().choice.value)
    }

    @Test fun choosingWhatIsAlreadyChosenWritesNothing() {
        var writes = 0
        val same = DesktopThemeSetting(read = { "DARK" }, write = { writes++ })
        same.set(ThemeChoice.DARK)
        assertEquals(0, writes)
        same.set(ThemeChoice.LIGHT)
        assertEquals(1, writes)
    }

    @Test fun theKeyIsTheAppearanceSettingsOwn() {
        assertEquals("theme_mode", DesktopAppearanceSettings.KEY_THEME_MODE)
    }

    private companion object { const val KEY = DesktopAppearanceSettings.KEY_THEME_MODE }
}
