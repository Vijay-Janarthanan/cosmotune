package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Merging the channels' uploads into one feed, grouping it under day overlines, and the start of today (plan task Y-9, step 2; spec 5.7, 8.3). */
class FeedMergeTest {
    private val day = 24 * 60 * 60 * 1000L

    private fun video(id: String, at: Long?, short: Boolean = false, channel: String = "UCchannel", durationSec: Long? = null): VideoItem =
        VideoItem(
            id = id, title = "Title $id", channelId = channel, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
            durationSec = durationSec, viewCount = null, uploadedAtMs = at, uploadedText = null, isShort = short,
        )

    // ---- merge ----

    @Test
    fun theChannelsAreMergedNewestFirst() {
        val merged = FeedMerge.merge(
            mapOf(
                "a" to listOf(video("a1", 300), video("a2", 100)),
                "b" to listOf(video("b1", 400), video("b2", 200)),
            ),
            hideShorts = false,
        )
        assertEquals(listOf("b1", "a1", "b2", "a2"), merged.map { it.id })
    }

    @Test
    fun aVideoInTwoChannelsAppearsOnce() {
        val merged = FeedMerge.merge(
            mapOf("a" to listOf(video("same", 300), video("a2", 100)), "b" to listOf(video("same", 300))),
            hideShorts = false,
        )
        assertEquals(listOf("same", "a2"), merged.map { it.id })
    }

    @Test
    fun ofTwoCopiesTheOneWithADurationIsKept() {
        val noDuration = video("same", 300, channel = "first")
        val withDuration = video("same", 300, channel = "second", durationSec = 61)
        assertEquals("second", FeedMerge.merge(mapOf("a" to listOf(noDuration), "b" to listOf(withDuration)), false).single().channelId)
        assertEquals("second", FeedMerge.merge(mapOf("a" to listOf(withDuration), "b" to listOf(noDuration)), false).single().channelId)
        // Both without one: the first stays.
        val other = video("same", 300, channel = "other")
        assertEquals("first", FeedMerge.merge(mapOf("a" to listOf(noDuration), "b" to listOf(other)), false).single().channelId)
    }

    @Test
    fun shortsAreKeptWhenShownAndDroppedWhenHidden() {
        val perChannel = mapOf("a" to listOf(video("v1", 300), video("s1", 200, short = true), video("v2", 100)))
        assertEquals(listOf("v1", "s1", "v2"), FeedMerge.merge(perChannel, hideShorts = false).map { it.id })
        assertEquals(listOf("v1", "v2"), FeedMerge.merge(perChannel, hideShorts = true).map { it.id })
    }

    @Test
    fun itemsWithoutAnUploadTimeComeLastInTheirOwnOrder() {
        val merged = FeedMerge.merge(
            mapOf("a" to listOf(video("n1", null), video("a1", 100)), "b" to listOf(video("n2", null), video("b1", 200))),
            hideShorts = false,
        )
        assertEquals(listOf("b1", "a1", "n1", "n2"), merged.map { it.id })
    }

    @Test
    fun equalTimesKeepTheOrderTheyCameIn() {
        val merged = FeedMerge.merge(
            mapOf("a" to listOf(video("a1", 100)), "b" to listOf(video("b1", 100)), "c" to listOf(video("c1", 100))),
            hideShorts = false,
        )
        assertEquals(listOf("a1", "b1", "c1"), merged.map { it.id })
    }

    @Test
    fun atMostTwoThousandItemsAreKeptTheNewestOnes() {
        val items = (1..2_500).map { video("v$it", it.toLong()) }
        val merged = FeedMerge.merge(mapOf("a" to items), hideShorts = false)
        assertEquals(2_000, FeedMerge.MAX_ITEMS)
        assertEquals(2_000, merged.size)
        assertEquals("v2500", merged.first().id)
        assertEquals("v501", merged.last().id)
    }

    @Test
    fun theCapCountsWhatIsLeftAfterTheShortsAreDropped() {
        val items = (1..2_100).map { video("v$it", it.toLong(), short = it % 2 == 0) }
        assertEquals(1_050, FeedMerge.merge(mapOf("a" to items), hideShorts = true).size)
        assertEquals(2_000, FeedMerge.merge(mapOf("a" to items), hideShorts = false).size)
    }

    @Test
    fun nothingGivesNothing() {
        assertEquals(emptyList(), FeedMerge.merge(emptyMap(), hideShorts = false))
        assertEquals(emptyList(), FeedMerge.merge(mapOf("a" to emptyList()), hideShorts = true))
    }

    // ---- groups ----

    private val today = 100 * day

    @Test
    fun theBoundariesOfTheGroupsAreInclusiveAtTheirStart() {
        val items = listOf(
            video("today-future", today + 3 * day),
            video("today-late", today + 5 * 60 * 60 * 1000L),
            video("today-start", today),
            video("yesterday-end", today - 1),
            video("yesterday-start", today - day),
            video("week-end", today - day - 1),
            video("week-start", today - 7 * day),
            video("month-end", today - 7 * day - 1),
            video("month-start", today - 30 * day),
            video("older-end", today - 30 * day - 1),
            video("older-none", null),
        )
        val groups = FeedMerge.groups(items, today)
        assertEquals(
            listOf(FeedGroup.TODAY, FeedGroup.YESTERDAY, FeedGroup.THIS_WEEK, FeedGroup.THIS_MONTH, FeedGroup.OLDER),
            groups.map { it.first },
        )
        assertEquals(
            listOf(
                listOf("today-future", "today-late", "today-start"),
                listOf("yesterday-end", "yesterday-start"),
                listOf("week-end", "week-start"),
                listOf("month-end", "month-start"),
                listOf("older-end", "older-none"),
            ),
            groups.map { (_, list) -> list.map { it.id } },
        )
    }

    @Test
    fun emptyGroupsAreLeftOut() {
        val groups = FeedMerge.groups(listOf(video("a", today - 2 * day), video("b", today - 40 * day)), today)
        assertEquals(listOf(FeedGroup.THIS_WEEK, FeedGroup.OLDER), groups.map { it.first })
        assertEquals(emptyList(), FeedMerge.groups(emptyList(), today))
    }

    @Test
    fun theOrderInsideAGroupIsTheOrderOfTheInput() {
        val groups = FeedMerge.groups(listOf(video("x", today + 30), video("y", today + 10), video("z", today + 20)), today)
        assertEquals(listOf("x", "y", "z"), groups.single().second.map { it.id })
    }

    @Test
    fun groupsFollowTheMergedFeedAsTheTabDrawsIt() {
        val now = today + 12 * 60 * 60 * 1000L
        val merged = FeedMerge.merge(
            mapOf(
                "a" to listOf(video("a1", now - 1_000), video("a2", today - day / 2), video("a3", today - 3 * day)),
                "b" to listOf(video("b1", now - 2_000), video("b2", today - 20 * day), video("b3", today - 90 * day)),
            ),
            hideShorts = false,
        )
        val groups = FeedMerge.groups(merged, today)
        assertEquals(
            listOf(
                FeedGroup.TODAY to listOf("a1", "b1"),
                FeedGroup.YESTERDAY to listOf("a2"),
                FeedGroup.THIS_WEEK to listOf("a3"),
                FeedGroup.THIS_MONTH to listOf("b2"),
                FeedGroup.OLDER to listOf("b3"),
            ),
            groups.map { (group, list) -> group to list.map { it.id } },
        )
    }

    @Test
    fun anyListWithATimePerElementCanBeGrouped() {
        data class Watched(val id: String, val at: Long?)
        val history = listOf(Watched("w1", today + 1), Watched("w2", today - 3 * day), Watched("w3", null), Watched("w4", today - day))
        val groups = FeedMerge.groups(history, today) { it.at }
        assertEquals(
            listOf(
                FeedGroup.TODAY to listOf("w1"),
                FeedGroup.YESTERDAY to listOf("w4"),
                FeedGroup.THIS_WEEK to listOf("w2"),
                FeedGroup.OLDER to listOf("w3"),
            ),
            groups.map { (group, list) -> group to list.map { it.id } },
        )
    }

    // ---- FeedDay ----

    private fun utc(text: String): Long = Instant.parse(text).toEpochMilli()

    @Test
    fun startOfTodayInUtc() {
        assertEquals(utc("2026-10-16T00:00:00Z"), FeedDay.startOfToday(utc("2026-10-16T12:34:56Z"), ZoneOffset.UTC))
        assertEquals(utc("2026-10-16T00:00:00Z"), FeedDay.startOfToday(utc("2026-10-16T00:00:00Z"), ZoneOffset.UTC))
        assertEquals(utc("2026-10-16T00:00:00Z"), FeedDay.startOfToday(utc("2026-10-16T23:59:59.999Z"), ZoneOffset.UTC))
    }

    @Test
    fun startOfTodayFollowsTheZoneNotUtc() {
        // 17:30 on the 16th in Kolkata (UTC+5:30): the day began at 18:30 UTC on the 15th.
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals(utc("2026-10-15T18:30:00Z"), FeedDay.startOfToday(utc("2026-10-16T12:00:00Z"), kolkata))
        // 05:00 UTC on the 16th is still the 15th in Los Angeles (UTC-7 in October): its day began at 07:00 UTC.
        val losAngeles = ZoneId.of("America/Los_Angeles")
        assertEquals(utc("2026-10-15T07:00:00Z"), FeedDay.startOfToday(utc("2026-10-16T05:00:00Z"), losAngeles))
    }

    @Test
    fun startOfTodayOnTheDayTheClocksGoBackIsStillMidnight() {
        // Berlin, 2026-10-25: summer time ends at 03:00, the day has 25 hours and begins at 22:00 UTC on the 24th.
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(utc("2026-10-24T22:00:00Z"), FeedDay.startOfToday(utc("2026-10-25T12:00:00Z"), berlin))
        // The next day began at 23:00 UTC (winter time).
        assertEquals(utc("2026-10-25T23:00:00Z"), FeedDay.startOfToday(utc("2026-10-26T12:00:00Z"), berlin))
    }

    @Test
    fun startOfTodayDefaultsToTheDevicesZone() {
        val now = utc("2026-10-16T12:00:00Z")
        val start = FeedDay.startOfToday(now)
        assertEquals(FeedDay.startOfToday(now, ZoneId.systemDefault()), start)
        assertTrue(start <= now && now - start <= 25 * 60 * 60 * 1000L)
    }
}
