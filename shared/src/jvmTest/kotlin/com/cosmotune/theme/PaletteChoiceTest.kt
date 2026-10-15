package com.cosmotune.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PaletteChoiceTest {
    private fun solid(argb: Int, dark: Boolean = true) = CoverPalette.fromPixels(IntArray(64 * 64) { argb }, 64, 64, dark = dark)
    private val green = solid(0xFF466044.toInt())
    private val red = solid(0xFFD4552E.toInt())
    private val brand = CoverPalette.fromPixels(IntArray(0), 0, 0, dark = true)
    private val brandLight = CoverPalette.fromPixels(IntArray(0), 0, 0, dark = false)

    @Test fun aFailedLoadKeepsWhatIsOnScreen() {
        assertNull(PaletteChoice.next(green, true, fresh = null, coverMissing = false, brand = brand))
    }

    @Test fun aSongWithNoCoverGoesToTheBrandPalette() {
        assertEquals(brand, PaletteChoice.next(green, true, null, coverMissing = true, brand = brand))
    }

    @Test fun aNearIdenticalCoverDoesNotReTheme() {
        assertNull(PaletteChoice.next(green, true, fresh = solid(0xFF476145.toInt()), coverMissing = false, brand = brand))
    }

    @Test fun aDifferentCoverIsTheNewTarget() {
        assertEquals(red, PaletteChoice.next(green, true, fresh = red, coverMissing = false, brand = brand))
    }

    @Test fun aThemeFlipWithAFailedLoadDoesNotKeepTheOtherThemesPalette() {
        assertEquals(brandLight, PaletteChoice.next(green, currentMatchesTheme = false, fresh = null, coverMissing = false, brand = brandLight))
    }

    @Test fun aThemeFlipWithAFreshCoverTakesTheFreshOne() {
        val light = solid(0xFF466044.toInt(), dark = false)
        assertEquals(light, PaletteChoice.next(green, currentMatchesTheme = false, fresh = light, coverMissing = false, brand = brandLight))
    }

    @Test fun theBrandPaletteIsNotRepeatedWhenItIsAlreadyShown() {
        assertNull(PaletteChoice.next(brand, true, null, coverMissing = true, brand = brand))
    }

    @Test fun aCoverMissingAfterARealCoverFadesToTheBrandAndARealCoverAfterTheBrandIsNotHeldBack() {
        assertSame(brand, PaletteChoice.next(green, true, null, true, brand))
        assertEquals(green, PaletteChoice.next(brand, true, green, false, brand))
    }

    @Test fun twoTracksOfOneAlbumHoldTheLooserThreshold() {
        // Close enough for one album (0.10), not for two covers (0.03): the preconditions are checked first.
        val near = solid(0xFF2E7A4A.toInt())
        assertNotEquals(green, CoverPalette.settle(green, near, sameAlbum = false), "far enough to re-theme across albums")
        assertEquals(green, CoverPalette.settle(green, near, sameAlbum = true), "near enough to hold on one album")
        assertEquals(near, PaletteChoice.next(green, true, near, false, brand, sameAlbum = false))
        assertNull(PaletteChoice.next(green, true, near, false, brand, sameAlbum = true))
    }
}
