package com.cosmotune.settings

import com.cosmotune.design.AppMode
import com.cosmotune.design.VideoView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CosmoLookSettingsTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    /** A store from before the intensity setting: it keeps the two default bodies of [KeyValueStore.getInt] and [KeyValueStore.putInt]. */
    private class BooleansOnlyStore : KeyValueStore {
        val map = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    @Test fun startsFromTheDefaultsAndRemembersChanges() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        assertTrue(settings.coverColours.value, "colours follow the cover unless switched off")
        settings.setCoverColours(false)
        assertEquals(false, CosmoLookSettings(store).coverColours.value)
    }

    @Test fun theOldLookSwitchAnOlderBuildStoredIsIgnored() {
        // An install that had turned the old look back on kept `new_look = false`: the look is the same, and nothing else moves.
        val store = MapStore().apply { map["new_look"] = false }
        val settings = CosmoLookSettings(store)
        assertTrue(settings.coverColours.value)
        assertEquals(AppMode.MUSIC, settings.mode.value)
        assertEquals(false, store.map["new_look"], "and nothing writes it back")
    }

    @Test fun theVideoViewStartsWholeAndKeepsWholeOrFillOnly() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        assertEquals(VideoView.WHOLE, settings.videoView.value, "Whole is the default")
        settings.setVideoView(VideoView.FILL)
        assertEquals(VideoView.FILL, CosmoLookSettings(store).videoView.value, "Fill is kept")
        settings.setVideoView(VideoView.CINEMA)
        assertEquals(VideoView.WHOLE, CosmoLookSettings(store).videoView.value, "Cinema is entered per visit, never kept")
        assertEquals(VideoView.WHOLE, CosmoLookSettings(BooleansOnlyStore()).videoView.value)
    }

    @Test fun intensityStartsBalancedAndRemembersTheChoice() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        assertEquals(1, settings.intensity.value, "Balanced is the default")
        settings.setIntensity(2)
        assertEquals(2, settings.intensity.value)
        assertEquals(2, store.map["intensity"], "kept under the key `intensity`")
        assertEquals(2, CosmoLookSettings(store).intensity.value)
        settings.setIntensity(0)
        assertEquals(0, CosmoLookSettings(store).intensity.value)
    }

    @Test fun anIntensityOutsideTheThreeIsHeldInsideThem() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        settings.setIntensity(7)
        assertEquals(2, settings.intensity.value)
        settings.setIntensity(-3)
        assertEquals(0, settings.intensity.value)
        store.map["intensity"] = 99
        assertEquals(2, CosmoLookSettings(store).intensity.value, "a stored value out of range is clamped when read")
    }

    @Test fun amoledStartsOffAndIsRemembered() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        assertEquals(false, settings.amoled.value, "the dark theme keeps its tinted page until AMOLED is switched on")
        settings.setAmoled(true)
        assertEquals(true, settings.amoled.value)
        assertEquals(true, CosmoLookSettings(store).amoled.value, "kept under the key `amoled`")
    }

    @Test fun anEmptyStoreReadsTheMusicMode() {
        assertEquals(AppMode.MUSIC, CosmoLookSettings(MapStore()).mode.value, "Music is the mode until the owner chooses another")
    }

    @Test fun theModeRoundTripsThroughANewSettingsOnTheSameStore() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        settings.setMode(AppMode.VIDEO)
        assertEquals(AppMode.VIDEO, settings.mode.value, "the flow follows at once")
        assertEquals(AppMode.VIDEO.ordinal, store.map["app_mode"], "kept as the enum's ordinal under the key `app_mode`")
        assertEquals(AppMode.VIDEO, CosmoLookSettings(store).mode.value)
        settings.setMode(AppMode.MUSIC)
        assertEquals(AppMode.MUSIC, CosmoLookSettings(store).mode.value)
    }

    @Test fun aStoredModeNoOneKnowsReadsAsMusic() {
        val store = MapStore()
        store.map["app_mode"] = 7
        assertEquals(AppMode.MUSIC, CosmoLookSettings(store).mode.value)
        store.map["app_mode"] = -1
        assertEquals(AppMode.MUSIC, CosmoLookSettings(store).mode.value)
    }

    @Test fun aStoreThatKnowsNothingOfIntegersStaysOnMusic() {
        val store = BooleansOnlyStore()
        val settings = CosmoLookSettings(store)
        assertEquals(AppMode.MUSIC, settings.mode.value)
        settings.setMode(AppMode.VIDEO)
        assertEquals(AppMode.VIDEO, settings.mode.value, "the flow still follows, even where nothing is kept")
        assertEquals(AppMode.MUSIC, CosmoLookSettings(store).mode.value, "and a new settings starts on Music again")
    }

    @Test fun nowPlayingsControlsStartExpandedAndRememberTheChoice() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        assertEquals(false, settings.nowPlayingControlsMinimized.value, "first ever use is expanded")
        settings.setNowPlayingControlsMinimized(true)
        assertEquals(true, settings.nowPlayingControlsMinimized.value, "the flow follows at once")
        assertEquals(true, store.map["np_controls_minimized"], "kept under the key `np_controls_minimized`")
        assertEquals(true, CosmoLookSettings(store).nowPlayingControlsMinimized.value, "and survives a restart")
        settings.setNowPlayingControlsMinimized(false)
        assertEquals(false, CosmoLookSettings(store).nowPlayingControlsMinimized.value)
    }

    @Test fun aStoreThatKnowsNothingOfIntegersStaysBalanced() {
        val store = BooleansOnlyStore()
        val settings = CosmoLookSettings(store)
        assertEquals(1, settings.intensity.value)
        settings.setIntensity(2)
        assertEquals(2, settings.intensity.value, "the flow still follows, even where nothing is kept")
        settings.setCoverColours(false)
        assertEquals(false, CosmoLookSettings(store).coverColours.value)
    }
}
