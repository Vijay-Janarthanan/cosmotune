package com.cosmotune.ui

import com.cosmotune.music.ui.player.RepeatModes
import com.cosmotune.ui.nowplaying.NowPlayingActive
import com.cosmotune.ui.nowplaying.activeChips
import com.cosmotune.ui.sleep.SleepTimerControl
import com.cosmotune.ui.sleep.SleepTimerReading
import com.cosmotune.ui.sleep.sleepCountdown
import com.cosmotune.ui.sleep.sleepTimerMenuItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the player says about a running sleep timer, and the rule that a feature in force stays in sight as a chip.
 */
class SleepTimerUiTest {

    private class FakeTimer(var left: Long? = null) : SleepTimerControl {
        override val minutes = MutableStateFlow<Int?>(null)
        override val afterTrack = MutableStateFlow(false)
        val calls = mutableListOf<String>()
        override fun remainingMs(): Long? = left
        override fun start(minutes: Int) { calls += "start $minutes" }
        override fun startAfterTrack() { calls += "after" }
        override fun extend() { calls += "extend" }
        override fun cancel() { calls += "cancel" }
    }

    @Test
    fun theCountdownRoundsUpSoItNeverSaysZeroWhileTheMusicPlays() {
        assertEquals("23:41", sleepCountdown(23 * 60_000L + 40_001L))
        assertEquals("23:41", sleepCountdown(23 * 60_000L + 41_000L))
        assertEquals("0:01", sleepCountdown(1L))
        assertEquals("0:00", sleepCountdown(0L))
        assertEquals("1:00:00", sleepCountdown(60 * 60_000L))
    }

    @Test
    fun thePillAndTheMenuRowSayWhatIsLeft() {
        val timed = SleepTimerReading(23 * 60_000L + 41_000L, afterTrack = false)
        assertEquals("23:41", timed.short())
        assertEquals("23:41 left", timed.line())
        val after = SleepTimerReading(null, afterTrack = true)
        assertEquals("After this song", after.short())
        assertEquals("After this song", after.line())
    }

    @Test
    fun aRunningTimersMenuOffersExtendAndTurnOffFirst() {
        val timer = FakeTimer(left = 10 * 60_000L)
        val items = sleepTimerMenuItems(timer, SleepTimerReading(timer.left, false), minutes = 30)
        assertEquals(listOf("Extend", "Turn off timer", "After this song", "15 minutes", "30 minutes", "45 minutes", "1 hour"), items.map { it.title })
        assertTrue(items.single { it.title == "30 minutes" }.selected)
        items.first().onClick()
        items[1].onClick()
        assertEquals(listOf("extend", "cancel"), timer.calls)
        // Extend keeps the menu up, so the new time left is seen.
        assertTrue(items.first().keepOpen)
    }

    @Test
    fun anIdleTimersMenuOnlyOffersTheChoices() {
        val timer = FakeTimer()
        val items = sleepTimerMenuItems(timer, null, minutes = null)
        assertEquals(listOf("After this song", "15 minutes", "30 minutes", "45 minutes", "1 hour"), items.map { it.title })
        items[2].onClick()
        assertEquals(listOf("start 30"), timer.calls)
    }

    @Test
    fun everyActiveFeatureIsAChipAndNothingOffIs() {
        val none = activeChips(NowPlayingActive(), transportOnCard = false, {}, {}, {}, {}, {})
        assertTrue(none.isEmpty())
        val all = activeChips(
            NowPlayingActive(shuffle = true, repeatMode = RepeatModes.ONE, crossfade = true, equaliser = true, castingTo = "Living room", inRoom = true),
            transportOnCard = false, {}, {}, {}, {}, {},
        )
        assertEquals(listOf("Repeat one", "Shuffle", "Crossfade", "Equaliser", "Living room", "In a room"), all.map { it.title })
    }

    @Test
    fun automixNamesItselfRatherThanCrossfade() {
        val chips = activeChips(NowPlayingActive(crossfade = true, automix = true), transportOnCard = false, {}, {}, {}, {}, {})
        assertEquals(listOf("Automix"), chips.map { it.title })
    }

    @Test
    fun theWidePageLeavesShuffleAndRepeatToItsCard() {
        val chips = activeChips(NowPlayingActive(shuffle = true, repeatMode = RepeatModes.ALL, equaliser = true), transportOnCard = true, {}, {}, {}, {}, {})
        assertEquals(listOf("Equaliser"), chips.map { it.title })
    }
}
