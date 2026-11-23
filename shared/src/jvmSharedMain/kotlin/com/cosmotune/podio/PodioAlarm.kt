package com.cosmotune.podio

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable

/**
 * A radio alarm (RadioDroid's): [station] starts at [hour]:[minute] on [days] (1 is Monday, 7 Sunday), softly at first when
 * [gentle]. No days means once, at the next time it comes round.
 */
@Serializable data class PodioAlarm(val station: RadioStation, val hour: Int = 7, val minute: Int = 0,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5), val on: Boolean = true, val gentle: Boolean = true)

object PodioAlarms {
    /** The volume steps of a gentle start: about half a minute from quiet to the full volume. */
    val GENTLE_STEPS = listOf(0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f, 1f)
    const val GENTLE_STEP_MS = 5_000L

    /** When [alarm] next goes off after [nowMs], epoch milliseconds; null when it is off. */
    fun next(alarm: PodioAlarm?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (alarm == null || !alarm.on) return null
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val time = LocalTime.of(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59))
        for (ahead in 0..7L) {
            val day: LocalDate = now.toLocalDate().plusDays(ahead)
            if (alarm.days.isNotEmpty() && day.dayOfWeek.value !in alarm.days) continue
            // A time a clock change skips (02:30 on the spring-forward night) comes at the first moment after it.
            val at = ZonedDateTime.of(day, time, zone)
            if (at.toInstant().toEpochMilli() > nowMs) return at.toInstant().toEpochMilli()
        }
        return null
    }

    /** "07:05". */
    fun time(alarm: PodioAlarm): String = "%02d:%02d".format(alarm.hour, alarm.minute)

    /** "every day", "on weekdays", "at weekends", "once", or "on Mon, Wed, Fri". */
    fun days(days: Set<Int>): String = when (days) {
        emptySet<Int>() -> "once"
        (1..7).toSet() -> "every day"
        setOf(1, 2, 3, 4, 5) -> "on weekdays"
        setOf(6, 7) -> "at weekends"
        else -> "on " + days.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
    }

    /** What Settings says: "Radio One at 07:00 on weekdays". */
    fun summary(alarm: PodioAlarm): String = "${alarm.station.name} at ${time(alarm)} ${days(alarm.days)}"

    /** How long until it goes off, as people say it: "in 7 h 20 min", "in 3 min". */
    fun until(atMs: Long, nowMs: Long): String {
        val minutes = ((atMs - nowMs + 59_999) / 60_000).coerceAtLeast(1)
        return when {
            minutes < 60 -> "in $minutes min"
            minutes % 60 == 0L -> "in ${minutes / 60} h"
            else -> "in ${minutes / 60} h ${minutes % 60} min"
        }
    }

    /** After a one-time alarm went off it is turned off; a repeating one stays. */
    fun fired(alarm: PodioAlarm): PodioAlarm = if (alarm.days.isEmpty()) alarm.copy(on = false) else alarm

    val DAY_NAMES = DayOfWeek.entries.map { it.name.lowercase().replaceFirstChar(Char::titlecase).take(3) }
}
