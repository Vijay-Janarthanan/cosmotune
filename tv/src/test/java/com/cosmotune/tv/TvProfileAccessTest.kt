package com.cosmotune.tv

import org.junit.Assert.*
import org.junit.Test

class TvProfileAccessTest {
    @Test fun removingOrSwitchingFromOneVerifiedProfileCannotUnlockTheFallbackProfile() {
        val access = TvProfileAccess()
        assertFalse(access.allows("profile-a", true))
        access.verifiedSelection("profile-a")
        assertTrue(access.allows("profile-a", true))
        assertFalse(access.allows("profile-b", true))
        // Closing an account dialog grants no proof. The fallback still needs its own PIN.
        assertFalse(access.allows("profile-b", true))
        access.verifiedSelection("profile-b")
        assertTrue(access.allows("profile-b", true))
        assertFalse(access.allows("profile-a", true))
    }

    @Test fun reopeningTheAccountPickerRevokesItsPreviousProofAndUnlockedBrowsingStillWorks() {
        val access = TvProfileAccess()
        access.verifiedSelection("profile")
        access.openAccountPicker()
        assertFalse(access.allows("profile", true))
        assertFalse(access.allows(null, true))
        assertTrue(access.allows(null, false))
        assertTrue(access.allows("public", false))
    }
}
