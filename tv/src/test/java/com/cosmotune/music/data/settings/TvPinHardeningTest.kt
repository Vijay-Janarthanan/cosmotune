package com.cosmotune.music.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The profile PIN cooldown is enforced where the PIN is checked, not only by
 * the keypad that asks for it, and survives a reboot and a reload correctly.
 * (Unit tests see a monotonic clock of 0, so "now" in verification is 0.)
 */
class TvPinHardeningTest {

    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        TvSettings.init(prefs)
        TvPinThrottle.resetAll()
    }

    @Test
    fun `wrong PINs are counted by the check itself and the right one is refused during the cooldown`() {
        TvSettings.setProfilePin("p", "2580")
        repeat(4) { assertFalse(TvSettings.verifyProfilePin("p", "0000")) }
        assertEquals(4, TvPinThrottle.getFailedAttempts("p"))
        assertEquals(0L, TvPinThrottle.getDelayRemainingMs("p", now = 0L))
        assertFalse(TvSettings.verifyProfilePin("p", "1111"))
        assertEquals(10_000L, TvPinThrottle.getDelayRemainingMs("p", now = 0L))
        // During the cooldown even the right PIN does not open the profile, and the refusal is not counted.
        assertFalse(TvSettings.verifyProfilePin("p", "2580"))
        assertEquals(5, TvPinThrottle.getFailedAttempts("p"))
    }

    @Test
    fun `the right PIN after the cooldown opens the profile and clears the count`() {
        TvSettings.setProfilePin("p", "2580")
        // Five failures recorded long enough ago that their 10 s wait is over.
        repeat(5) { TvPinThrottle.recordFailure("p", now = -60_000L) }
        assertEquals(0L, TvPinThrottle.getDelayRemainingMs("p", now = 0L))
        assertTrue(TvSettings.verifyProfilePin("p", "2580"))
        assertEquals(0, TvPinThrottle.getFailedAttempts("p"))
    }

    @Test
    fun `a profile without a PIN collects no failures`() {
        repeat(6) { assertFalse(TvSettings.verifyProfilePin("open", "1234")) }
        assertEquals(0, TvPinThrottle.getFailedAttempts("open"))
    }

    @Test
    fun `cooldowns are per profile`() {
        TvSettings.setProfilePin("a", "1111")
        TvSettings.setProfilePin("b", "2222")
        repeat(5) { TvSettings.verifyProfilePin("a", "0000") }
        assertFalse(TvSettings.verifyProfilePin("a", "1111"))
        assertTrue(TvSettings.verifyProfilePin("b", "2222"))
    }

    @Test
    fun `after a reboot the full wait runs down from the new boot`() {
        repeat(6) { TvPinThrottle.recordFailure("p", now = 9_000_000L) }
        // Reboot: the monotonic clock starts again far below the recorded reading.
        assertEquals(30_000L, TvPinThrottle.getDelayRemainingMs("p", now = 2_000L))
        assertEquals(20_000L, TvPinThrottle.getDelayRemainingMs("p", now = 12_000L))
        assertEquals(0L, TvPinThrottle.getDelayRemainingMs("p", now = 32_001L))
    }

    @Test
    fun `the count and cooldown persist across a restart and belong to their store`() {
        TvSettings.setProfilePin("p", "2580")
        repeat(5) { TvSettings.verifyProfilePin("p", "0000") }
        // A different store (another install or a cleared app) starts clean...
        TvSettings.init(FakeSharedPreferences())
        assertEquals(0, TvPinThrottle.getFailedAttempts("p"))
        // ...and the original store, reloaded as after a process restart, keeps its cooldown.
        TvSettings.init(prefs)
        assertEquals(5, TvPinThrottle.getFailedAttempts("p"))
        assertEquals(10_000L, TvPinThrottle.getDelayRemainingMs("p", now = 0L))
        assertFalse(TvSettings.verifyProfilePin("p", "2580"))
    }
}
