package com.cosmotune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first Home load is tried once more, a few seconds later, only when it failed because a host name could
 * not be looked up (no network yet, DNS not answering yet) and has not been retried before. Everything else
 * (a rejected request, a timeout, an empty message) shows its error at once, as before.
 */
class StartupRetryTest {

    // --- the messages that count as "the name could not be looked up" ------------------------------

    @Test
    fun theMessageAndroidGivesForAMissingNetworkMatches() {
        assertTrue(
            StartupRetry.isNameResolutionFailure("Unable to resolve host \"music.youtube.com\": No address associated with hostname"),
        )
    }

    @Test
    fun theAppsTwoExistingPhrasesMatch() {
        // The two phrases MainViewModel.friendly() already turns into "No internet connection".
        assertTrue(StartupRetry.isNameResolutionFailure("Could not resolve host: music.youtube.com"))
        assertTrue(StartupRetry.isNameResolutionFailure("Unable to resolve something else"))
    }

    @Test
    fun nameResolutionWordingMatches() {
        assertTrue(StartupRetry.isNameResolutionFailure("Temporary failure in name resolution"))
        assertTrue(StartupRetry.isNameResolutionFailure("music.youtube.com: Name resolution failed"))
    }

    @Test
    fun noAddressAssociatedWithHostnameMatchesOnItsOwn() {
        assertTrue(StartupRetry.isNameResolutionFailure("No address associated with hostname"))
        assertTrue(StartupRetry.isNameResolutionFailure("java.net.UnknownHostException: No address associated with hostname"))
    }

    @Test
    fun matchingIgnoresCapitalLetters() {
        assertTrue(StartupRetry.isNameResolutionFailure("UNABLE TO RESOLVE HOST \"X\""))
        assertTrue(StartupRetry.isNameResolutionFailure("no address associated with HOSTNAME"))
        assertTrue(StartupRetry.isNameResolutionFailure("TEMPORARY FAILURE IN NAME RESOLUTION"))
    }

    // --- what must not count ------------------------------------------------------------------------

    @Test
    fun aRejectedRequestDoesNotMatch() {
        assertFalse(StartupRetry.isNameResolutionFailure("401"))
        assertFalse(StartupRetry.isNameResolutionFailure("HTTP 401 Unauthorized"))
        assertFalse(StartupRetry.isNameResolutionFailure("403 Forbidden: the request was rejected"))
    }

    @Test
    fun aTimeoutDoesNotMatch() {
        assertFalse(StartupRetry.isNameResolutionFailure("timeout"))
        assertFalse(StartupRetry.isNameResolutionFailure("Read timed out"))
        assertFalse(StartupRetry.isNameResolutionFailure("Request timeout has expired [url=https://music.youtube.com, request_timeout=15000 ms]"))
    }

    @Test
    fun otherConnectionFailuresDoNotMatch() {
        assertFalse(StartupRetry.isNameResolutionFailure("Connection refused"))
        assertFalse(StartupRetry.isNameResolutionFailure("Software caused connection abort"))
        assertFalse(StartupRetry.isNameResolutionFailure("Something went wrong"))
    }

    @Test
    fun noMessageDoesNotMatch() {
        assertFalse(StartupRetry.isNameResolutionFailure(null))
        assertFalse(StartupRetry.isNameResolutionFailure(""))
        assertFalse(StartupRetry.isNameResolutionFailure("   "))
    }

    // --- whether to retry ---------------------------------------------------------------------------

    @Test
    fun aNameResolutionFailureIsRetriedOnceAndOnlyOnce() {
        val message = "Unable to resolve host \"music.youtube.com\": No address associated with hostname"
        assertTrue(StartupRetry.shouldRetry(message, alreadyRetried = false))
        assertFalse(StartupRetry.shouldRetry(message, alreadyRetried = true))
    }

    @Test
    fun otherFailuresAreNeverRetriedWhateverTheFlagSays() {
        listOf("401", "timeout", "Connection refused", "", null).forEach { message ->
            assertFalse(StartupRetry.shouldRetry(message, alreadyRetried = false), "retried $message")
            assertFalse(StartupRetry.shouldRetry(message, alreadyRetried = true), "retried $message")
        }
    }

    @Test
    fun theWaitIsThreeSeconds() {
        assertEquals(3000L, StartupRetry.DELAY_MS)
    }
}
