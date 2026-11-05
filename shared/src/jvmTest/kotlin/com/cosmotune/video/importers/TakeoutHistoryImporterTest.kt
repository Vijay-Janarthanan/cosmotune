package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.C0
import com.cosmotune.video.importers.ImportFixtures.C1
import com.cosmotune.video.importers.ImportFixtures.V0
import com.cosmotune.video.importers.ImportFixtures.V1
import com.cosmotune.video.importers.ImportFixtures.V2
import com.cosmotune.video.importers.ImportFixtures.V3
import com.cosmotune.video.importers.ImportFixtures.V4
import com.cosmotune.video.importers.ImportFixtures.V7
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TakeoutHistoryImporterTest {
    private val preview = TakeoutHistoryImporter.parse(ImportFixtures.text("watch-history.json"))

    @Test
    fun readsWatchedVideosNewestFirstAndSkipsAdsRemovedVideosMusicAndSearches() {
        assertEquals(listOf(V0, V1, V2, V3, V4, V7), preview.history.map { it.item.id })
        assertEquals(4, preview.skipped, "the ad, the removed video, the Music entry and the search")
        assertNull(preview.reason)
        assertEquals(preview.history.sortedByDescending { it.lastWatchedMs }, preview.history)
    }

    @Test
    fun aVideoWatchedSeveralTimesIsOneEntryWithTheNewestTimeAndACount() {
        val first = preview.history.first()
        assertEquals(V0, first.item.id)
        assertEquals(ImportFixtures.ms("2026-10-15T08:30:00.123Z"), first.lastWatchedMs)
        assertEquals(2, first.watchCount)
        assertEquals(1, preview.history[1].watchCount)
    }

    @Test
    fun noPositionAndNoDurationSoNoWatchedBar() {
        assertTrue(preview.history.all { it.positionMs == 0L && it.durationMs == 0L && it.item.durationSec == null })
        assertTrue(preview.history.none { it.isWatched })
        assertTrue(preview.history.all { it.item.watchedFraction(it.positionMs, it.durationMs) == null })
    }

    @Test
    fun theTitleLosesItsWatchedPrefixAndTheChannelComesFromTheFirstSubtitle() {
        val byId = preview.history.associateBy { it.item.id }
        val first = byId.getValue(V0).item
        assertEquals("How to plane a board by hand", first.title)
        assertEquals("Northern Lights Workshop", first.channelName)
        assertEquals(C0, first.channelId)
        assertEquals("Paper, Scissors & Stone", byId.getValue(V1).item.channelName)
        assertEquals(C1, byId.getValue(V1).item.channelId)
        assertEquals("A slow stew, \"the long way\"", byId.getValue(V2).item.title)
        val unlinked = byId.getValue(V7).item
        assertEquals("Unlinked channel", unlinked.channelName)
        assertNull(unlinked.channelId, "a subtitle with no address gives no channel id")
        assertEquals("https://i.ytimg.com/vi/$V0/mqdefault.jpg", first.thumbnailUrl)
    }

    private fun entry(id: String, title: String, time: String, extra: String = "") =
        """{"header":"YouTube","title":"$title","titleUrl":"https://www.youtube.com/watch?v=$id","time":"$time","products":["YouTube"]$extra}"""

    private val ids = listOf(ImportFixtures.V0, ImportFixtures.V1, ImportFixtures.V2, ImportFixtures.V3, ImportFixtures.V4, ImportFixtures.V5)

    @Test
    fun aLocalisedPrefixIsRecognisedFromTheTitlesTheWayFreeTubeDoes() {
        val titles = listOf("Hobeln lernen", "Papier falten", "Langsamer Eintopf", "Werkstatt Tour", "Meißel schärfen", "Ein Lied")
        val json = ids.mapIndexed { i, id -> entry(id, "Angesehen: ${titles[i]}", "2026-10-1${i}T08:00:00Z") }.joinToString(",", "[", "]")
        val result = TakeoutHistoryImporter.parse(json)
        assertEquals(titles.toSet(), result.history.map { it.item.title }.toSet())
    }

    @Test
    fun withTooFewTitlesToInferFromOnlyTheEnglishPrefixGoes() {
        val json = listOf(
            entry(V0, "Watched One", "2026-10-15T08:00:00Z"),
            entry(V1, "Angesehen: Two", "2026-10-14T08:00:00Z"),
            entry(V2, "Watched Three", "2026-10-13T08:00:00Z"),
        ).joinToString(",", "[", "]")
        val titles = TakeoutHistoryImporter.parse(json).history.associate { it.item.id to it.item.title }
        assertEquals("One", titles.getValue(V0))
        assertEquals("Angesehen: Two", titles.getValue(V1), "not guessed from so few titles")
        assertEquals("Three", titles.getValue(V2))
    }

    @Test
    fun aSharedSeriesNameIsNotTakenForThePrefixWhenTheTitlesAreEnglish() {
        val json = ids.mapIndexed { i, id -> entry(id, "Watched Lecture series part ${i + 1}", "2026-10-1${i}T08:00:00Z") }.joinToString(",", "[", "]")
        val titles = TakeoutHistoryImporter.parse(json).history.map { it.item.title }.toSet()
        assertEquals((1..6).map { "Lecture series part $it" }.toSet(), titles)
    }

    @Test
    fun localisedPrefixIsTheCommonStartCutBackToASpace() {
        assertEquals("Angesehen: ", TakeoutHistoryImporter.localisedPrefix(listOf("Angesehen: Aa", "Angesehen: Ab", "Angesehen: Ac", "Angesehen: Ad", "Angesehen: Ae")))
        assertEquals("Watched ", TakeoutHistoryImporter.localisedPrefix(emptyList()))
        // no space in the shared start (Chinese has none): nothing is guessed
        assertEquals("Watched ", TakeoutHistoryImporter.localisedPrefix(listOf("已观看A", "已观看B", "已观看C", "已观看D", "已观看E")))
    }

    @Test
    fun anEntryWithoutProductsIsReadButOneFromAnotherProductIsNot() {
        val json = """[
            {"title":"Watched A","titleUrl":"https://www.youtube.com/watch?v=$V0","time":"2026-10-15T08:00:00Z"},
            {"title":"Watched B","titleUrl":"https://www.youtube.com/watch?v=$V1","time":"2026-10-14T08:00:00Z","products":["Search"]},
            {"title":"Watched C","titleUrl":"https://www.youtube.com/watch?v=$V2","time":"2026-10-13T08:00:00Z","products":["YouTube","Other"],"details":null}
        ]"""
        val result = TakeoutHistoryImporter.parse(json)
        assertEquals(listOf(V0, V2), result.history.map { it.item.id }, "a null details is not an ad")
        assertEquals(1, result.skipped)
    }

    @Test
    fun anEntryWithoutAReadableTimeOrIdIsSkipped() {
        val json = """[
            {"title":"Watched A","titleUrl":"https://www.youtube.com/watch?v=$V0"},
            {"title":"Watched B","titleUrl":"https://www.youtube.com/watch?v=$V1","time":"yesterday"},
            {"title":"Watched C","titleUrl":"https://www.youtube.com/watch?v=short","time":"2026-10-13T08:00:00Z"},
            7
        ]"""
        val result = TakeoutHistoryImporter.parse(json)
        assertTrue(result.history.isEmpty())
        assertEquals(4, result.skipped)
        assertEquals(ImportReason.NOTHING_FOUND, result.reason)
    }

    @Test
    fun aVideoAddressWithOtherParametersGivesItsId() {
        val json = "[" + entry(V0, "Watched X", "2026-10-15T08:00:00Z").replace("watch?v=$V0", "watch?v=$V0&t=33s&list=PLx") + "]"
        assertEquals(listOf(V0), TakeoutHistoryImporter.parse(json).history.map { it.item.id })
    }

    @Test
    fun brokenFilesAreRefused() {
        assertEquals(ImportReason.UNREADABLE, TakeoutHistoryImporter.parse("<html>").reason)
        assertEquals(ImportReason.UNREADABLE, TakeoutHistoryImporter.parse("{}").reason)
        assertEquals(ImportReason.NOTHING_FOUND, TakeoutHistoryImporter.parse("[]").reason)
    }
}
