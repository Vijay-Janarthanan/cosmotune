package com.cosmotune

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The comparison both update checkers use; 1.0.0 is the first release after the alpha and beta builds. */
class ReleaseVersionTest {

    @Test
    fun theReleaseIsNewerThanTheBuildsThatLedToIt() {
        assertTrue(ReleaseVersion.isNewer("1.0.0", "1.0.0-beta2"))
        assertTrue(ReleaseVersion.isNewer("1.0.0", "1.0.0-beta1"))
        assertTrue(ReleaseVersion.isNewer("1.0.0", "1.0.0-alpha1"))
    }

    @Test
    fun theReleaseIsNotOfferedToItselfOrToALaterBuild() {
        assertFalse(ReleaseVersion.isNewer("1.0.0", "1.0.0"))
        assertFalse(ReleaseVersion.isNewer("1.0.0-beta2", "1.0.0"))
        assertFalse(ReleaseVersion.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun numbersCompareAsNumbers() {
        assertTrue(ReleaseVersion.isNewer("1.10.0", "1.9.0"))
        assertTrue(ReleaseVersion.isNewer("1.0.1", "1.0.0"))
        assertTrue(ReleaseVersion.isNewer("1.0.1-beta1", "1.0.0"))
        assertFalse(ReleaseVersion.isNewer("1.0", "1.0.0"))
    }
}
