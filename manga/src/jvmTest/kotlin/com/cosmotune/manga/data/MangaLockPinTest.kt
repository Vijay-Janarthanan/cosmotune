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
}
