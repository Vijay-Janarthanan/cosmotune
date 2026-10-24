package com.cosmotune.music.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BC-35's borderless fullscreen, with the native frame replaced by a fake (the Win32 window cannot be driven from a test): when
 * the native frame takes the request the placement is left alone and the material is put back on the way out; without it the
 * old placement path is used.
 */
class DesktopWindowModeTest {
    private val calls = mutableListOf<String>()
    private var nativeWorks = true
    private val originalNative = DesktopWindowMode.nativeFullscreen
    private val originalBackdrop = DesktopWindowMode.restoreBackdrop

    @BeforeTest fun fakes() {
        DesktopWindowMode.resetForTest()
        DesktopWindowMode.nativeFullscreen = { on -> calls += "native $on"; nativeWorks }
        DesktopWindowMode.restoreBackdrop = { calls += "backdrop" }
    }

    @AfterTest fun restore() {
        DesktopWindowMode.nativeFullscreen = originalNative
        DesktopWindowMode.restoreBackdrop = originalBackdrop
        DesktopWindowMode.resetForTest()
    }

    @Test fun theNativeFrameFillsTheScreenWithoutTouchingThePlacement() {
        DesktopWindowMode.toggle()
        assertTrue(DesktopWindowMode.fullScreen.value)
        assertEquals(WindowPlacement.Floating, DesktopWindowMode.placement.value, "the Win32 style changes, not the placement")
        DesktopWindowMode.toggle()
        assertFalse(DesktopWindowMode.fullScreen.value)
        assertEquals(listOf("native true", "native false", "backdrop"), calls)
        assertEquals(WindowPlacement.Floating, DesktopWindowMode.placement.value)
    }

    @Test fun withoutTheNativeFrameThePlacementFillsTheScreenAsBefore() {
        nativeWorks = false
        DesktopWindowMode.toggle()
        assertTrue(DesktopWindowMode.fullScreen.value)
        assertTrue(DesktopWindowMode.placement.value != WindowPlacement.Floating, "Maximized or Fullscreen, by platform")
        DesktopWindowMode.toggle()
        assertEquals(WindowPlacement.Floating, DesktopWindowMode.placement.value)
        assertEquals(listOf("native true"), calls, "nothing native to take down")
    }

    @Test fun maximizingOrLeavingFromFullscreenTakesTheNativeWindowDownFirst() {
        DesktopWindowMode.toggle()
        DesktopWindowMode.toggleMaximized()
        assertEquals(listOf("native true", "native false", "backdrop"), calls)
        assertFalse(DesktopWindowMode.fullScreen.value)
        assertEquals(WindowPlacement.Maximized, DesktopWindowMode.placement.value)

        calls.clear()
        DesktopWindowMode.toggleMaximized()
        DesktopWindowMode.toggle()
        DesktopWindowMode.exit()
        assertEquals(listOf("native true", "native false", "backdrop"), calls, "closing the player leaves the native fullscreen")
        assertFalse(DesktopWindowMode.fullScreen.value)
    }

    @Test fun aFailedExitStillLeavesFullscreenButKeepsTheMaterialAlone() {
        DesktopWindowMode.toggle()
        nativeWorks = false
        DesktopWindowMode.toggle()
        assertFalse(DesktopWindowMode.fullScreen.value)
        assertEquals(listOf("native true", "native false"), calls)
    }
}
