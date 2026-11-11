package com.cosmotune.watch

import com.cosmotune.watch.store.ProfilePins
import kotlin.test.*

class ProfilePinsTest {
    @Test fun pinsAreSaltedAndMalformedOrWrongPinsFailClosed() {
        val first = ProfilePins.encode("123456")
        val second = ProfilePins.encode("123456")
        assertNotEquals(first, second)
        assertFalse(first.contains("123456"))
        assertTrue(ProfilePins.verify("123456", first))
        assertFalse(ProfilePins.verify("123457", first))
        assertFalse(ProfilePins.verify(null, first))
        assertFalse(ProfilePins.verify("123456", "v1:invalid:hash"))
        assertFailsWith<IllegalArgumentException> { ProfilePins.encode("12") }
    }
}
