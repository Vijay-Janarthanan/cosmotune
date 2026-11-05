package com.cosmotune.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The desktop's keys over a video (spec 6.6). */
class PlayerKeyMapTest {

    private fun key(name: String, shift: Boolean = false, ctrl: Boolean = false) = PlayerKeyMap.action(name, shift, ctrl)

    @Test
    fun spaceAndKPlayAndPause() {
        assertEquals(PlayerKeyAction.TogglePlay, key("Space"))
        assertEquals(PlayerKeyAction.TogglePlay, key("K"))
        assertEquals(PlayerKeyAction.TogglePlay, key("k"), "letters ignore case")
    }

    @Test
    fun jAndLMoveTenSecondsAndTheArrowsFive() {
        assertEquals(PlayerKeyAction.SeekBy(-10_000), key("J"))
        assertEquals(PlayerKeyAction.SeekBy(10_000), key("L"))
        assertEquals(PlayerKeyAction.SeekBy(-5_000), key("Left"))
        assertEquals(PlayerKeyAction.SeekBy(5_000), key("Right"))
        assertEquals(PlayerKeyMap.JL_SEEK_MS, 10_000)
        assertEquals(PlayerKeyMap.ARROW_SEEK_MS, 5_000)
    }

    @Test
    fun upAndDownChangeTheVolumeAndMMutes() {
        assertEquals(PlayerKeyAction.Volume(PlayerKeyMap.VOLUME_STEP), key("Up"))
        assertEquals(PlayerKeyAction.Volume(-PlayerKeyMap.VOLUME_STEP), key("Down"))
        assertEquals(PlayerKeyAction.ToggleMute, key("M"))
    }

    @Test
    fun fFullWindowTTheatreCCaptionsEscLeaves() {
        assertEquals(PlayerKeyAction.FullWindow, key("F"))
        assertEquals(PlayerKeyAction.Theatre, key("T"))
        assertEquals(PlayerKeyAction.Captions, key("C"))
        assertEquals(PlayerKeyAction.ExitFullWindow, key("Escape"))
    }

    @Test
    fun shiftNAndShiftPMoveInTheQueueAndNotWithoutShift() {
        assertEquals(PlayerKeyAction.Next, key("N", shift = true))
        assertEquals(PlayerKeyAction.Previous, key("P", shift = true))
        assertNull(key("N"))
    }

    @Test
    fun pOpensThePictureInPictureWindowAndShiftPIsPrevious() {
        assertEquals(PlayerKeyAction.PictureInPicture, key("P"))
        assertEquals(PlayerKeyAction.PictureInPicture, key("p"))
        assertNull(key("P", ctrl = true))
    }

    @Test
    fun angleBracketsChangeTheSpeedAsCharactersOrAsShiftedCommaAndPeriod() {
        assertEquals(PlayerKeyAction.Speed(-PlayerKeyMap.SPEED_STEP), key("<"))
        assertEquals(PlayerKeyAction.Speed(PlayerKeyMap.SPEED_STEP), key(">"))
        assertEquals(PlayerKeyAction.Speed(-PlayerKeyMap.SPEED_STEP), key("Comma", shift = true))
        assertEquals(PlayerKeyAction.Speed(PlayerKeyMap.SPEED_STEP), key("Period", shift = true))
        assertNull(key("Comma"), "a bare comma is not a key of the spec")
        assertNull(key("Period"))
        assertEquals(0.25f, PlayerKeyMap.SPEED_STEP)
    }

    @Test
    fun digitsJumpToTenthsOfTheVideo() {
        for (d in 0..9) assertEquals(PlayerKeyAction.SeekToFraction(d / 10f), key(d.toString()))
        assertNull(key("5", shift = true), "shifted digits are other characters")
    }

    @Test
    fun ctrlOrCmdLeavesEveryKeyToTheApp() {
        for (name in listOf("Space", "K", "J", "F", "Left", "5", "M", "Escape")) assertNull(key(name, ctrl = true), name)
    }

    @Test
    fun otherKeysAreNotThePlayers() {
        for (name in listOf("A", "Q", "Enter", "Tab", "", "F5", "Home")) assertNull(key(name), name)
    }
}
