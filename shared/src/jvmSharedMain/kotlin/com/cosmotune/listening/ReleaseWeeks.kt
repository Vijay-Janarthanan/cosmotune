package com.cosmotune.listening

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** The groups of the Releases page, newest first (DESIGN_SYSTEM.md 6.1). */
enum class ReleaseWeek { THIS_WEEK, LAST_WEEK, EARLIER }

/**
 * The inbox as the Releases page lists it: grouped by the calendar week each release was discovered in, the newest first in each
 * group, and with [hideSingles] without what the catalogue called a single. A release the catalogue said nothing about stays. Empty
 * groups are left out. A discovery dated after [now] (a clock set back) counts as this week.
 */
fun releaseWeeks(
    inbox: List<ReleaseNotice>,
    now: Long,
    hideSingles: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
): List<Pair<ReleaseWeek, List<ReleaseNotice>>> {
    val thisWeek = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(firstDay))
    val lastWeek = thisWeek.minusWeeks(1)
    fun weekOf(release: ReleaseNotice): ReleaseWeek {
        val day = Instant.ofEpochMilli(release.discoveredAt).atZone(zone).toLocalDate()
        return when {
            !day.isBefore(thisWeek) -> ReleaseWeek.THIS_WEEK
            !day.isBefore(lastWeek) -> ReleaseWeek.LAST_WEEK
            else -> ReleaseWeek.EARLIER
        }
    }
    val shown = if (hideSingles) inbox.filterNot { it.single == true } else inbox
    val groups = shown.sortedByDescending { it.discoveredAt }.groupBy(::weekOf)
    return ReleaseWeek.entries.mapNotNull { week -> groups[week]?.let { week to it } }
}
