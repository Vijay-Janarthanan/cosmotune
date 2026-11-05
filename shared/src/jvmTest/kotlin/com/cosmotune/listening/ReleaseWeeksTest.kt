package com.cosmotune.listening

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseWeeksTest {
    private fun at(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000
    private fun release(id: String, date: String, single: Boolean? = null) =
        ReleaseNotice(id, "artist", "Artist", id, discoveredAt = at(date), single = single)

    // Wednesday 2026-11-04; weeks start on Monday in this test.
    private val now = at("2026-11-04")
    private fun weeks(vararg inbox: ReleaseNotice, hideSingles: Boolean = false) =
        releaseWeeks(inbox.toList(), now, hideSingles, ZoneOffset.UTC, DayOfWeek.MONDAY)

    @Test fun groupsByCalendarWeekNewestFirst() {
        val groups = weeks(
            release("old", "2026-10-01"),
            release("monday", "2026-11-02"),
            release("today", "2026-11-04"),
            release("lastSunday", "2026-11-01"),
            release("lastMonday", "2026-10-26"),
            release("twoWeeks", "2026-10-25"),
        )
        assertEquals(listOf(ReleaseWeek.THIS_WEEK, ReleaseWeek.LAST_WEEK, ReleaseWeek.EARLIER), groups.map { it.first })
        assertEquals(listOf("today", "monday"), groups[0].second.map { it.id })
        assertEquals(listOf("lastSunday", "lastMonday"), groups[1].second.map { it.id })
        assertEquals(listOf("twoWeeks", "old"), groups[2].second.map { it.id })
    }

    @Test fun emptyGroupsAreLeftOut() {
        assertEquals(emptyList(), weeks())
        assertEquals(listOf(ReleaseWeek.EARLIER), weeks(release("old", "2026-01-01")).map { it.first })
    }

    @Test fun aDiscoveryFromTheFutureCountsAsThisWeek() {
        assertEquals(listOf(ReleaseWeek.THIS_WEEK), weeks(release("ahead", "2027-01-01")).map { it.first })
    }

    @Test fun hidingSinglesKeepsWhatTheCatalogueSaidNothingAbout() {
        val shown = weeks(release("single", "2026-11-04", single = true), release("album", "2026-11-04", single = false), release("unknown", "2026-11-04"), hideSingles = true)
        assertEquals(setOf("album", "unknown"), shown.single().second.map { it.id }.toSet())
    }
}
