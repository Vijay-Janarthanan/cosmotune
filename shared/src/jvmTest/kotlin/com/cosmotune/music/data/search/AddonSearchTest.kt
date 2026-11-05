package com.cosmotune.music.data.search

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
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

    @Test fun streamReportsEachSourceAsItAnswersSoAFastOneIsNotHeldBack() = runTest {
        val slow = AddonSearchSource("Slow", id = "slow") { _, _ -> delay(5_000); listOf(song("s1")) }
        val fast = AddonSearchSource("Fast", id = "fast") { _, _ -> delay(100); listOf(song("f1")) }
        val updates = AddonSearch.stream("q", listOf(slow, fast)).toList()
        assertEquals(3, updates.size, "everyone searching, then one update per source")
        assertTrue(updates[0].sources.all { it.status == AddonSourceStatus.SEARCHING })
        assertEquals(AddonSourceStatus.DONE, updates[1].source("fast")?.status)
        assertEquals(AddonSourceStatus.SEARCHING, updates[1].source("slow")?.status, "the fast answer is out before the slow one")
        assertEquals(listOf("f1"), updates[1].songs.map { it.videoId })
        assertEquals(listOf("s1", "f1"), updates[2].songs.map { it.videoId })
        assertFalse(updates[2].searching)
    }

    @Test fun eachSourceReportsWhyItHasNoRows() = runTest {
        val locked = AddonSearchSource("Locked", id = "locked") { _, _ -> throw AddonSearchAccessRequired() }
        val lyrics = AddonSearchSource("Lyrics", id = "lyrics") { _, _ -> throw AddonSearchUnsupported() }
        val leaky = AddonSearchSource("Leaky", id = "leaky") { _, _ -> error("GET https://host/rest/search3?u=me&t=secret failed") }
        val broken = AddonSearchSource("Broken", id = "broken") { _, _ -> error("HTTP 500") }
        // A timeout raised inside the source's own call is its failure only, not the cancellation of the whole search.
        val inner = AddonSearchSource("Inner", id = "inner") { _, _ -> withTimeout(10) { delay(1_000) }; emptyList() }
        val slow = AddonSearchSource("Slow", id = "slow") { _, _ -> delay(60_000); emptyList() }
        val last = AddonSearch.stream("q", listOf(locked, lyrics, leaky, broken, inner, slow, source("ok", "ok1"))).toList().last()
        assertEquals(AddonSourceStatus.NEEDS_VERIFICATION, last.source("locked")?.status)
        assertEquals(AddonSourceStatus.UNSUPPORTED, last.source("lyrics")?.status)
        assertEquals("Search failed", last.source("leaky")?.reason, "an address or token in a message is never shown")
        assertEquals("HTTP 500", last.source("broken")?.reason)
        assertEquals(AddonSourceStatus.TIMED_OUT, last.source("inner")?.status)
        assertEquals(AddonSourceStatus.TIMED_OUT, last.source("slow")?.status)
        assertEquals(listOf("ok1"), last.songs.map { it.videoId })
    }

    @Test fun aQueuedSourcesBudgetStartsWhenItsWorkStarts() = runTest {
        // Like a provider package waiting 20 s for one of the shared runtimes, then answering 8 s later.
        val queued = AddonSearchSource("Queued", id = "queued", queueTimeoutMs = 60_000) { _, _ ->
            delay(20_000)
            addonSearchStarted()()
            delay(8_000)
            listOf(song("late-but-fine"))
        }
        val result = AddonSearch.searchOne("q", queued)
        assertEquals(AddonSourceStatus.DONE, result.status, "28 s in all, but only 8 s of its own work")
        assertEquals(28_000, currentTime)
        val stuck = AddonSearchSource("Stuck", id = "stuck", queueTimeoutMs = 30_000) { _, _ -> delay(120_000); emptyList() }
        assertEquals(AddonSourceStatus.TIMED_OUT, AddonSearch.searchOne("q", stuck).status, "a slot that never comes is left out")
        val unqueued = AddonSearchSource("Plain", id = "plain") { _, _ -> delay(20_000); addonSearchStarted()(); delay(8_000); emptyList() }
        assertEquals(AddonSourceStatus.TIMED_OUT, AddonSearch.searchOne("q", unqueued).status, "an ordinary source has its budget from the start")
    }

    @Test fun aChoiceRestrictsTheSearchToOneSourceAndAllAsksEveryone() {
        val sources = listOf(AddonSearchSource("A", id = "a") { _, _ -> emptyList() }, AddonSearchSource("B", id = "b") { _, _ -> emptyList() })
        assertEquals(listOf("a", "b"), AddonSearch.select(sources, AddonSearch.ALL).map { it.id })
        assertEquals(listOf("b"), AddonSearch.select(sources, "b").map { it.id })
        assertEquals(listOf("a", "b"), AddonSearch.select(sources, "removed").map { it.id }, "a source since removed falls back to all")
    }

    @Test fun theChoiceIsRememberedAndTheAddonsSideKeepsItsLastSource() {
        var stored: String? = null
        val choice = AddonSearchChoice(read = { stored }, write = { stored = it })
        assertEquals(AddonSearch.YOUTUBE, choice.selected.value, "YouTube Music until something else is picked")
        assertEquals(AddonSearch.ALL, choice.lastAddonChoice)
        choice.select("sflx-qobuz")
        choice.select(AddonSearch.YOUTUBE)
        assertEquals(AddonSearch.YOUTUBE, stored)
        assertEquals("sflx-qobuz", choice.lastAddonChoice, "switching back to the add-ons returns to the provider picked before")
        choice.select("sflx-qobuz")
        val reopened = AddonSearchChoice(read = { stored }, write = {})
        assertEquals("sflx-qobuz", reopened.selected.value)
        assertEquals("sflx-qobuz", reopened.lastAddonChoice)
    }

    private fun TestScope.session() = AddonSearchSession(backgroundScope)

    @Test fun theSessionRetriesOneSourceAndMergesItsAnswerInPlace() = runTest {
        var verified = false
        val locked = AddonSearchSource("Locked", id = "locked") { _, _ -> if (verified) listOf(song("v1")) else throw AddonSearchAccessRequired() }
        val session = session()
        session.start("q", listOf(locked, source("ok", "ok1")))
        runCurrent()
        assertEquals(AddonSourceStatus.NEEDS_VERIFICATION, session.state.value?.source("locked")?.status)
        verified = true
        session.retry("locked")
        assertEquals(AddonSourceStatus.SEARCHING, session.state.value?.source("locked")?.status)
        runCurrent()
        assertEquals(listOf("v1", "ok1"), session.state.value?.songs?.map { it.videoId })
        assertEquals(AddonSourceStatus.DONE, session.state.value?.source("ok")?.status, "the other source is not asked again")
    }

    @Test fun aNewSearchCancelsTheOldOneAndALateAnswerCannotLandInIt() = runTest {
        var cancelled = false
        val hanging = AddonSearchSource("Hanging", id = "h") { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        val session = session()
        session.start("first", listOf(hanging))
        runCurrent()
        session.start("second", listOf(source("ok", "ok1")))
        runCurrent()
        assertTrue(cancelled)
        assertEquals("second", session.state.value?.query)
        assertEquals(listOf("ok1"), session.state.value?.songs?.map { it.videoId })
        session.cancel()
        assertEquals(null, session.state.value)
        session.retry("ok")
        advanceTimeBy(1)
        assertEquals(null, session.state.value, "nothing to retry once the search is gone")
    }

    @Test fun theMergedListIsLoadingUntilTheFirstAnswerThenGrows() {
        val searching = AddonSearchProgress("q", listOf(AddonSourceResult("a", "A", AddonSourceStatus.SEARCHING)))
        assertEquals(com.cosmotune.music.data.model.UiState.Loading, searching.asUiState())
        val partial = AddonSearchProgress("q", listOf(
            AddonSourceResult("a", "A", AddonSourceStatus.SEARCHING),
            AddonSourceResult("b", "B", AddonSourceStatus.DONE, listOf(song("b1"))),
        ))
        assertEquals(com.cosmotune.music.data.model.UiState.Success(listOf(song("b1"))), partial.asUiState())
    }
}
