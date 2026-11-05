package com.cosmotune.video.feed

import java.time.Instant
import java.time.ZoneId

/** The device's calendar day for the feed's overlines (plan task Y-9): `java.time` lives here so that [FeedMerge] stays pure. */
object FeedDay {
    /**
     * The first instant of the calendar day that contains [nowMs] in [zone] (the device's zone by default), as epoch
     * milliseconds. It is the local midnight, so on the days the clocks change the day is 23 or 25 hours long.
     */
    fun startOfToday(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
