package com.cosmotune.video.signed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the session learns about the signed clients, and how it orders the next race. */
class ClientRankingTest {
    private var now = 1_000L
    private val ranking = ClientRanking { now }
    private val signed = SignedPlayerClient.SIGNED

    @Test fun withNothingLearnedTheFirstGoesAloneAndTheRestTogether() {
        val waves = ranking.waves(signed)
        assertEquals(listOf(SignedPlayerClient.TV_SAMSUNG), waves.first)
        assertEquals(listOf(SignedPlayerClient.WEB_EMBEDDED, SignedPlayerClient.TV_OLD, SignedPlayerClient.WEB), waves.rest)
        assertTrue(waves.late.isEmpty())
    }

    @Test fun aLastResortIsAlwaysLate() {
        val waves = ranking.waves(SignedPlayerClient.RESCUE)
        assertEquals(listOf(SignedPlayerClient.IOS), waves.first)
        assertTrue(waves.rest.isEmpty())
        assertEquals(listOf(SignedPlayerClient.ANDROID_VR), waves.late)
    }

    @Test fun twoBlamesInARowBenchAClientAndAnAnswerClearsIt() {
        ranking.answered(SignedPlayerClient.WEB_EMBEDDED, listOf(SignedPlayerClient.TV_SAMSUNG))
        assertTrue(ranking.benched().isEmpty(), "one blame is a blip")
        ranking.answered(SignedPlayerClient.WEB_EMBEDDED, listOf(SignedPlayerClient.TV_SAMSUNG))
        assertEquals(setOf(SignedPlayerClient.TV_SAMSUNG), ranking.benched())
        val waves = ranking.waves(signed)
        assertEquals(listOf(SignedPlayerClient.WEB_EMBEDDED), waves.first)
        assertEquals(listOf(SignedPlayerClient.TV_SAMSUNG), waves.late, "asked only when the others failed")
        ranking.answered(SignedPlayerClient.TV_SAMSUNG, emptyList())
        assertTrue(ranking.benched().isEmpty())
    }

    @Test fun anAnswerBetweenTwoBlamesStartsTheCountAgain() {
        ranking.answered(SignedPlayerClient.WEB_EMBEDDED, listOf(SignedPlayerClient.TV_SAMSUNG))
        ranking.answered(SignedPlayerClient.TV_SAMSUNG, emptyList())
        ranking.answered(SignedPlayerClient.WEB_EMBEDDED, listOf(SignedPlayerClient.TV_SAMSUNG))
        assertTrue(ranking.benched().isEmpty())
    }

    @Test fun theBenchEndsAfterItsTime() {
        repeat(ClientRanking.STRIKES) { ranking.answered(SignedPlayerClient.WEB_EMBEDDED, listOf(SignedPlayerClient.TV_SAMSUNG)) }
        now += ClientRanking.BENCH_MS - 1
        assertEquals(setOf(SignedPlayerClient.TV_SAMSUNG), ranking.benched())
        now += 1
        assertTrue(ranking.benched().isEmpty())
        assertEquals(listOf(SignedPlayerClient.TV_SAMSUNG), ranking.waves(signed).first)
        ranking.answered(SignedPlayerClient.WEB_EMBEDDED, listOf(SignedPlayerClient.TV_SAMSUNG))
        assertTrue(ranking.benched().isEmpty(), "and its count starts again")
    }

    @Test fun withEveryUsualClientBenchedTheLateOnesGoOneByOne() {
        repeat(ClientRanking.STRIKES) { ranking.answered(SignedPlayerClient.WEB, listOf(SignedPlayerClient.TV_SAMSUNG, SignedPlayerClient.WEB_EMBEDDED, SignedPlayerClient.TV_OLD)) }
        repeat(ClientRanking.STRIKES) { ranking.answered(SignedPlayerClient.TV_SAMSUNG, listOf(SignedPlayerClient.WEB)) }
        val waves = ranking.waves(listOf(SignedPlayerClient.WEB_EMBEDDED, SignedPlayerClient.TV_OLD, SignedPlayerClient.WEB))
        assertEquals(listOf(SignedPlayerClient.WEB_EMBEDDED), waves.first)
        assertTrue(waves.rest.isEmpty())
        assertEquals(listOf(SignedPlayerClient.TV_OLD, SignedPlayerClient.WEB), waves.late)
    }

    @Test fun anotherAccountForgetsEverything() {
        repeat(ClientRanking.STRIKES) { ranking.answered(SignedPlayerClient.WEB_EMBEDDED, listOf(SignedPlayerClient.TV_SAMSUNG)) }
        ranking.clear()
        assertTrue(ranking.benched().isEmpty())
    }
}
