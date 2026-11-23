package com.cosmotune.podio

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodioAlarmTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val station = RadioStation("r1", "Radio One", "https://radio.example/live")
    private fun at(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    @Test fun aWeekdayAlarmSkipsTheWeekend() {
        val alarm = PodioAlarm(station, 7, 0)
        // Friday 2026-11-27 at 08:00: already past, so Monday.
        assertEquals(at("2026-11-30T07:00"), PodioAlarms.next(alarm, at("2026-11-27T08:00"), zone))
        // Monday at 06:59: this morning.
        assertEquals(at("2026-11-30T07:00"), PodioAlarms.next(alarm, at("2026-11-30T06:59"), zone))
    }

    @Test fun aOnceAlarmIsTheNextTimeAndTurnsItselfOff() {
        val once = PodioAlarm(station, 6, 30, days = emptySet())
        assertEquals(at("2026-11-24T06:30"), PodioAlarms.next(once, at("2026-11-23T22:00"), zone))
        assertFalse(PodioAlarms.fired(once).on)
        assertTrue(PodioAlarms.fired(PodioAlarm(station)).on)
        assertNull(PodioAlarms.next(once.copy(on = false), at("2026-11-23T22:00"), zone))
    }

    @Test fun aClockChangeNightStillWakes() {
        val berlin = ZoneId.of("Europe/Berlin")
        val alarm = PodioAlarm(station, 2, 30, days = (1..7).toSet())
        val night = ZonedDateTime.of(java.time.LocalDateTime.parse("2026-03-29T00:00"), berlin).toInstant().toEpochMilli()
        val next = PodioAlarms.next(alarm, night, berlin)!!
        assertEquals(3, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), berlin).hour)
    }

    @Test fun itSaysWhenInPlainWords() {
        assertEquals("Radio One at 07:00 on weekdays", PodioAlarms.summary(PodioAlarm(station)))
        assertEquals("at weekends", PodioAlarms.days(setOf(6, 7)))
        assertEquals("on Mon, Wed", PodioAlarms.days(setOf(3, 1)))
        assertEquals("in 7 h 20 min", PodioAlarms.until(at("2026-11-24T07:00"), at("2026-11-23T23:40")))
        assertEquals("in 1 min", PodioAlarms.until(at("2026-11-24T07:00"), at("2026-11-24T06:59:30")))
    }
}
