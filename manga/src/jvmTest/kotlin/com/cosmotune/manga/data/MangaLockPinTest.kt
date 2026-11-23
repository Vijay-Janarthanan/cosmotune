package com.cosmotune.manga.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaLockPinTest {
    @Test fun aPinIsFourToTwelveDigits() {
        assertNull(MangaLockPin.problem("1234"))
        assertNull(MangaLockPin.problem("123456789012"))
        assertEquals("Use at least 4 digits", MangaLockPin.problem("123"))
        assertEquals("Use at most 12 digits", MangaLockPin.problem("1234567890123"))
        assertEquals("Use digits only", MangaLockPin.problem("12a4"))
    }

    @Test fun theStoredFormNeverHoldsThePin() {
        val stored = MangaLockPin.hash("4821")
        assertFalse("4821" in stored)
        assertTrue(stored.startsWith("v1:"))
        assertNotEquals(stored, MangaLockPin.hash("4821"), "each hash has its own salt")
    }

    @Test fun onlyTheRightPinMatches() {
        val stored = MangaLockPin.hash("4821")
        assertTrue(MangaLockPin.matches("4821", stored))
        assertFalse(MangaLockPin.matches("4822", stored))
        assertFalse(MangaLockPin.matches("", stored))
        assertFalse(MangaLockPin.matches("4821", null))
        assertFalse(MangaLockPin.matches("4821", "garbage"))
    }

    @Test fun forgettingThePinAsksForTheComputersSignInWhereThereIsOne() {
        assertEquals(MangaPinReset.SignIn("your Windows password"), MangaPinRecovery.plan("your Windows password"))
        assertEquals(MangaPinReset.ClearAndUnlock, MangaPinRecovery.plan(null))
        assertEquals(MangaPinReset.ClearAndUnlock, MangaPinRecovery.plan(" "))
    }

    @Test fun aFailedOrCancelledSignInNeverReachesANewPin() {
        val plan = MangaPinReset.SignIn("Touch ID")
        var flow = MangaPinRecoveryFlow().forgot()
        assertEquals(MangaPinStep.EXPLAIN, flow.step)
        flow = flow.confirm(plan)
        assertEquals(MangaPinStep.CHECKING, flow.step)
        flow = flow.checked(false)
        assertEquals(MangaPinStep.EXPLAIN, flow.step)
        assertEquals("The sign-in did not go through. Manga stays locked.", flow.message)
        assertEquals(MangaPinStep.NEW_PIN, flow.confirm(plan).checked(true).step)
        assertEquals(MangaPinStep.ENTER, flow.confirm(plan).checked(true).cancel().step)
        // Only a sign-in in progress can pass; a stray answer while the explanation shows changes nothing.
        assertEquals(MangaPinStep.EXPLAIN, MangaPinRecoveryFlow().forgot().checked(true).step)
        assertEquals(MangaPinStep.ENTER, MangaPinRecoveryFlow().checked(true).step)
    }

    @Test fun clearingInsteadIsOnlyOfferedWithoutASignInAndIsExplainedFirst() {
        val flow = MangaPinRecoveryFlow().forgot()
        assertEquals(MangaPinStep.CHECKING, flow.confirm(MangaPinReset.SignIn("Windows Hello")).step)
        assertEquals(MangaPinStep.CLEARED, flow.confirm(MangaPinReset.ClearAndUnlock).step)
        assertEquals(MangaPinStep.ENTER, MangaPinRecoveryFlow().confirm(MangaPinReset.ClearAndUnlock).step, "nothing is cleared before the explanation was shown")
        val text = MangaPinRecovery.explanation(MangaPinReset.ClearAndUnlock)
        assertTrue("turns the lock off" in text && "history" in text && "hidden images" in text && "library" in text)
        assertTrue("Nothing is cleared" in MangaPinRecovery.explanation(MangaPinReset.SignIn("Windows Hello")))
    }

    @Test fun aResetWithoutSignInTurnsTheLockOffAndClearsWhatItGuarded() {
        val progress = MangaProgress("b", "c", 3, 20)
        val state = MangaLibraryState(lockManga = true, lockPin = MangaLockPin.hash("4821"), progress = mapOf("b" to progress),
            readLog = mapOf("c" to 1L), readingTime = mapOf("2026-11-23" to 5L), hiddenImages = mapOf("h" to MangaHiddenImage("b", 1L)),
            readChapters = setOf("c"), activeBookId = "b", activeChapterId = "c", notes = mapOf("b" to "note"))
        val cleared = MangaPinRecovery.afterClear(state)
        assertFalse(cleared.lockManga)
        assertNull(cleared.lockPin)
        assertTrue(cleared.progress.isEmpty() && cleared.readLog.isEmpty() && cleared.readingTime.isEmpty() && cleared.hiddenImages.isEmpty())
        assertNull(cleared.activeBookId)
        assertNull(cleared.activeChapterId)
        assertEquals(setOf("c"), cleared.readChapters, "read chapters and the library stay")
        assertEquals(state.notes, cleared.notes)
    }

    @Test fun afterSigningInTheOldPinGoesAndNothingElse() {
        val state = MangaLibraryState(lockManga = true, lockPin = MangaLockPin.hash("4821"), progress = mapOf("b" to MangaProgress("b", "c", 3, 20)),
            hiddenImages = mapOf("h" to MangaHiddenImage("b", 1L)))
        val renewed = MangaPinRecovery.afterSignIn(state, MangaLockPin.hash("7777"))
        assertTrue(renewed.lockManga)
        assertTrue(MangaLockPin.matches("7777", renewed.lockPin))
        assertFalse(MangaLockPin.matches("4821", renewed.lockPin))
        assertEquals(state.progress, renewed.progress)
        assertEquals(state.hiddenImages, renewed.hiddenImages)
        val off = MangaPinRecovery.afterSignIn(state, null)
        assertFalse(off.lockManga)
        assertNull(off.lockPin)
        assertEquals(state.progress, off.progress)
    }
}
