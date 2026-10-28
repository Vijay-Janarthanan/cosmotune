package com.cosmotune.music.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSourceOfferRaceTest {
    private val aac = DesktopStream("fixture:aac", DesktopStreamFormat(codec = "aac", kbps = 320))
    private val flac = DesktopStream("fixture:flac", DesktopStreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 48_000))
    private fun better(previous: DesktopStream?, next: DesktopStream) =
        if (previous?.format?.isLossless == true) previous else next

    @Test fun losslessArrivesEvenWhenAnotherProviderNeverAnswers() = runBlocking {
        val aacReturned = CompletableDeferred<Unit>()
        val stalledCancelled = CompletableDeferred<Unit>()
        val result = withTimeout(2_000) {
            awaitSourceOffer(listOf(
                { aacReturned.complete(Unit); aac },
                { aacReturned.await(); flac },
                { try { awaitCancellation() } finally { stalledCancelled.complete(Unit) } },
            ), 10_000, ::better) { it.format.isLossless }
        }
        assertEquals(flac, result)
        assertTrue(stalledCancelled.isCompleted)
    }

    @Test fun aDeadlineKeepsAnOfferThatAlreadyCompleted() = runBlocking {
        assertEquals(aac, awaitSourceOffer(listOf({ aac }, { awaitCancellation() }), 50, ::better) { false })
    }

    @Test fun anEmptyLookupHasNoOffer() = runBlocking {
        assertNull(awaitSourceOffer(emptyList(), 100, ::better) { true })
    }
}
