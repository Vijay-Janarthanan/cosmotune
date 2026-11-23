package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PodioFollowLockTest {
    @Test fun onlyTheRightPinOpensIt() {
        val lock = PodioFollowLock.lock("2468")
        assertTrue(PodioFollowLock.matches(lock, "2468"))
        assertFalse(PodioFollowLock.matches(lock, "1357"))
        assertTrue(PodioFollowLock.matches(null, "anything"), "no lock asks for nothing")
        assertNotEquals(lock.hash, PodioFollowLock.lock("2468").hash, "each lock has its own salt")
    }

    @Test fun aPinIsFourToEightDigits() {
        assertFalse(PodioFollowLock.valid("123")); assertFalse(PodioFollowLock.valid("12a4")); assertTrue(PodioFollowLock.valid("12345678"))
        assertFailsWith<IllegalArgumentException> { PodioFollowLock.lock("12") }
    }
}
