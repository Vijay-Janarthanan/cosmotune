package com.cosmotune.music.data.search

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BC-31: the Add-ons search's fan-out, merge and history rules (shared by the phone and the desktop). */
class AddonSearchTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)
    private fun source(name: String, vararg ids: String) = AddonSearchSource(name) { _, _ -> ids.map(::song) }

    @Test fun onlyAnExplicitSubmissionOfTextReachesTheAddons() {
        assertTrue(AddonSearch.shouldSearch("aurora", submitted = true))
        assertFalse(AddonSearch.shouldSearch("aurora", submitted = false), "typing is never sent to a third-party catalogue")
        assertFalse(AddonSearch.shouldSearch("   ", submitted = true))
    }

    @Test fun answersAreInterleavedSoNoCatalogueBuriesTheOthers() = runTest {
        val rows = AddonSearch.search("q", listOf(source("a", "a1", "a2", "a3"), source("b", "b1"), source("c", "c1", "c2")))
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), rows.map { it.videoId })
    }

    @Test fun theQueryIsTrimmedAndEachSourceIsAskedForTheLimitAndHeldToIt() = runTest {
        val asked = mutableListOf<Pair<String, Int>>()
        val greedy = AddonSearchSource("greedy") { q, limit -> asked += q to limit; (1..50).map { song("g$it") } }
        val rows = AddonSearch.search("  aurora  ", listOf(greedy), limit = 5)
        assertEquals(listOf("aurora" to 5), asked)
        assertEquals(5, rows.size)
    }

    @Test fun aBlankQueryOrNoSourceAsksNothing() = runTest {
        var asked = 0
        val counting = AddonSearchSource("c") { _, _ -> asked++; listOf(song("x")) }
        assertEquals(emptyList(), AddonSearch.search(" ", listOf(counting)))
        assertEquals(emptyList(), AddonSearch.search("q", emptyList()))
        assertEquals(0, asked)
    }

    @Test fun aFailingSourceCostsOnlyItsOwnRows() = runTest {
        val broken = AddonSearchSource("broken") { _, _ -> error("HTTP 500") }
        val rows = AddonSearch.search("q", listOf(broken, source("ok", "ok1")))
        assertEquals(listOf("ok1"), rows.map { it.videoId })
    }

    @Test fun aSlowSourceIsLeftOutAfterTheBudgetAndTheRestAreNotHeldBack() = runTest {
        val slow = AddonSearchSource("slow") { _, _ -> delay(60_000); listOf(song("late")) }
        val rows = AddonSearch.search("q", listOf(slow, source("fast", "f1")))
        assertEquals(listOf("f1"), rows.map { it.videoId })
        assertEquals(AddonSearch.TIMEOUT_MS, currentTime, "the search ends at the budget, not when the slow source answers")
    }

    @Test fun cancellingTheSearchCancelsEverySourceRequest() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val hanging = AddonSearchSource("hanging") { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        val job = launch { AddonSearch.search("q", listOf(hanging)) }
        started.await()
        job.cancel()
        advanceUntilIdle()
        assertTrue(cancelled, "a new query or a cleared field stops the add-on request")
    }

    @Test fun aRowTwoPackagesBothReturnIsListedOnceAndTiersStaySeparate() {
        val merged = AddonSearch.interleave(listOf(
            listOf(song("src:a::tidal:1"), song("src:a::tidal:2")),
            listOf(song("src:a::tidal:1")),
        ))
        assertEquals(listOf("src:a::tidal:1", "src:a::tidal:2"), merged.map { it.videoId })
    }

    @Test fun theHistoryKeepsNewestFirstWithoutCaseDuplicatesAndPersistsThroughItsStore() {
        var stored: String? = null
        val history = AddonQueryHistory(read = { stored }, write = { stored = it }, max = 3)
        history.record(" Aurora ")
        history.record("hans zimmer")
        history.record("aurora")
        assertEquals(listOf("aurora", "hans zimmer"), history.recent.value)
        history.record("x"); history.record("y")
        assertEquals(listOf("y", "x", "aurora"), history.recent.value, "capped")
        history.record("   ")
        assertEquals(3, history.recent.value.size, "a blank query is not recorded")
        assertEquals(listOf("y", "x", "aurora"), AddonQueryHistory(read = { stored }, write = {}).recent.value, "read back from the store")
        history.remove("x")
        assertEquals(listOf("y", "aurora"), history.recent.value)
        history.clear()
        assertEquals(emptyList(), AddonQueryHistory(read = { stored }, write = {}).recent.value)
    }

    @Test fun aDamagedStoreReadsAsNoHistory() {
        assertEquals(emptyList(), AddonQueryHistory(read = { "{not json" }, write = {}).recent.value)
        assertEquals(emptyList(), AddonQueryHistory(read = { null }, write = {}).recent.value)
    }
}
