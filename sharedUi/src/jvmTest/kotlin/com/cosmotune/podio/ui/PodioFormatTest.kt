package com.cosmotune.podio.ui

import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PodioFormatTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test fun clocksShowHoursOnlyFromAnHourOn() {
        assertEquals("0:00", PodioFormat.clock(-5))
        assertEquals("1:25", PodioFormat.clock(85_000))
        assertEquals("59:59", PodioFormat.clock(3_599_000))
        assertEquals("1:02:09", PodioFormat.clock(3_729_000))
    }

    @Test fun durationsAreWordsAndUnknownLengthsAreBlank() {
        assertEquals("", PodioFormat.duration(0))
        assertEquals("1 min", PodioFormat.duration(20))
        assertEquals("45 min", PodioFormat.duration(45 * 60 + 30))
        assertEquals("2 h", PodioFormat.duration(7200))
        assertEquals("1 h 5 min", PodioFormat.duration(3900))
        assertEquals("12 min left", PodioFormat.remaining(3 * 60_000, 15 * 60_000))
        assertEquals("", PodioFormat.remaining(0, 15 * 60_000))
    }

    @Test fun feedDatesAreShortAndUnreadableOnesAreKeptAsGiven() {
        assertEquals("10 Oct 2019", PodioFormat.date("Thu, 10 Oct 2019 10:00:00 +0000", today, Locale.ENGLISH))
        assertEquals("3 Nov", PodioFormat.date("Tue, 3 Nov 2026 06:00:00 GMT", today, Locale.ENGLISH))
        assertEquals("3 Nov", PodioFormat.date("Tue, 03 Nov 2026 06:00:00 EST", today, Locale.ENGLISH))
        assertEquals("1 Oct 2024", PodioFormat.date("2024-10-01T08:30:00Z", today, Locale.ENGLISH))
        assertEquals("1 Oct 2024", PodioFormat.date("2024-10-01", today, Locale.ENGLISH))
        assertEquals("3 days ago", PodioFormat.date("3 days ago", today, Locale.ENGLISH))
        assertEquals("", PodioFormat.date("  ", today, Locale.ENGLISH))
        val older = PodioFormat.publishedAt("Thu, 10 Oct 2019 10:00:00 +0000")!!
        val newer = PodioFormat.publishedAt("2024-10-01T08:30:00Z")!!
        assertEquals(true, newer > older)
        assertEquals(null, PodioFormat.publishedAt("3 days ago"))
    }

    @Test fun progressFollowsTheLibrary() {
        assertEquals("Played", PodioFormat.heard(completed = true, positionMs = 0, durationMs = 0))
        assertEquals("", PodioFormat.heard(false, 0, 600_000))
        assertEquals("8 min left", PodioFormat.heard(false, 85_000, 600_000))
        assertEquals("Played", PodioFormat.heard(false, 599_000, 600_000))
        assertEquals(0.5f, PodioFormat.progressFraction(false, 300_000, 600_000))
        assertEquals(0f, PodioFormat.progressFraction(true, 300_000, 600_000))
        assertEquals(0f, PodioFormat.progressFraction(false, 300_000, 0))
    }

    @Test fun metadataLeavesOutBlanks() {
        assertEquals("12 Oct · 45 min · Downloaded", PodioFormat.episodeMeta("12 Oct", " ", "45 min", "", "Downloaded"))
        assertEquals("Chennai, India · tamil · MP3 128 kbps", PodioFormat.stationMeta("Chennai, India", "tamil", "MP3", 128))
        assertEquals("India · AAC", PodioFormat.stationMeta("India", "", "AAC", 0))
    }

    @Test fun showNotesBecomePlainText() {
        assertEquals("Hello & welcome\nPart two\n\nThe end",
            PodioFormat.plainText("<p>Hello &amp; welcome</p><p>Part <b>two</b></p><br><br/>The&nbsp;end"))
        assertEquals("abc", PodioFormat.plainText("abcdef", limit = 3))
    }

    @Test fun sidebarRowsMapToSections() {
        assertEquals(PodioSection.RADIO, PodioFormat.section("Radio", false))
        assertEquals(PodioSection.PODCASTS, PodioFormat.section("Podcasts", false))
        assertEquals(PodioSection.GLOBE, PodioFormat.section("Globe", false))
        assertEquals(PodioSection.FOLLOWING, PodioFormat.section("Following", false))
        assertEquals(PodioSection.DOWNLOADS, PodioFormat.section("Downloads", false))
        // The old names land on Following, where the follows, the new episodes and the saved stations now are.
        assertEquals(PodioSection.FOLLOWING, PodioFormat.section("Episodes", false))
        assertEquals(PodioSection.FOLLOWING, PodioFormat.section("Library", false))
        assertEquals(PodioSection.FOLLOWING, PodioFormat.section("Saved", false))
        assertEquals(PodioSection.FOLLOWING, PodioFormat.section("Radio", true))
        assertEquals(PodioSection.RADIO, PodioFormat.section("Anything else", false))
    }

    @Test fun anEpisodeSaysHowMuchIsLeft() {
        assertEquals("", PodioFormat.heard(false, 0, 600_000))
        assertEquals("8 min left", PodioFormat.heard(false, 85_000, 600_000))
        assertEquals("Resume at 1:25", PodioFormat.heard(false, 85_000, 0))
        assertEquals("Played", PodioFormat.heard(true, 0, 600_000))
    }

    @Test fun theSheetSettlesOnTheNearestAnchorOrFlingsToTheNext() {
        val anchors = listOf(160f, 480f, 900f)
        assertEquals(160f, PodioFormat.snap(200f, 0f, anchors))
        assertEquals(480f, PodioFormat.snap(400f, 0f, anchors))
        assertEquals(480f, PodioFormat.snap(200f, -2000f, anchors))
        assertEquals(900f, PodioFormat.snap(900f, -2000f, anchors))
        assertEquals(160f, PodioFormat.snap(470f, 2000f, anchors))
        assertEquals(160f, PodioFormat.snap(160f, 2000f, anchors))
    }
}
