package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The time-of-day title of Home ("Late Tuesday", `V2.home`): which of the four greetings an hour of the day gets. */
class HomeGreetingTest {

    @Test
    fun morningIs5To11() {
        for (hour in 5..11) assertEquals(HomeGreeting.Period.MORNING, HomeGreeting.period(hour), "at $hour")
    }

    @Test
    fun afternoonIs12To16() {
        for (hour in 12..16) assertEquals(HomeGreeting.Period.AFTERNOON, HomeGreeting.period(hour), "at $hour")
    }

    @Test
    fun eveningIs17To21() {
        for (hour in 17..21) assertEquals(HomeGreeting.Period.EVENING, HomeGreeting.period(hour), "at $hour")
    }

    @Test
    fun lateIs22ThroughTheNightTo4() {
        for (hour in listOf(22, 23, 0, 1, 2, 3, 4)) assertEquals(HomeGreeting.Period.LATE, HomeGreeting.period(hour), "at $hour")
    }

    @Test
    fun theEdgesFallOnTheRightSide() {
        assertEquals(HomeGreeting.Period.LATE, HomeGreeting.period(4))
        assertEquals(HomeGreeting.Period.MORNING, HomeGreeting.period(5))
        assertEquals(HomeGreeting.Period.MORNING, HomeGreeting.period(11))
        assertEquals(HomeGreeting.Period.AFTERNOON, HomeGreeting.period(12))
        assertEquals(HomeGreeting.Period.AFTERNOON, HomeGreeting.period(16))
        assertEquals(HomeGreeting.Period.EVENING, HomeGreeting.period(17))
        assertEquals(HomeGreeting.Period.EVENING, HomeGreeting.period(21))
        assertEquals(HomeGreeting.Period.LATE, HomeGreeting.period(22))
    }

    @Test
    fun everyHourOfTheDayHasExactlyOnePeriodAndAllFourAreUsed() {
        val periods = (0..23).map { HomeGreeting.period(it) }
        assertEquals(4, periods.toSet().size)
        assertEquals(7, periods.count { it == HomeGreeting.Period.LATE })
        assertEquals(7, periods.count { it == HomeGreeting.Period.MORNING })
        assertEquals(5, periods.count { it == HomeGreeting.Period.AFTERNOON })
        assertEquals(5, periods.count { it == HomeGreeting.Period.EVENING })
    }

    @Test
    fun anHourOutsideTheDayWrapsInsteadOfFailing() {
        // a title must never crash the page: 24 is midnight, -1 is 23 o'clock
        assertEquals(HomeGreeting.Period.LATE, HomeGreeting.period(24))
        assertEquals(HomeGreeting.Period.LATE, HomeGreeting.period(-1))
        assertEquals(HomeGreeting.Period.MORNING, HomeGreeting.period(29))
    }
}
