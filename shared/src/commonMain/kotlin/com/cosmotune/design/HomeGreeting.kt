package com.cosmotune.design

/**
 * Which greeting Home's title is, by the hour of the day (the board's "Late Tuesday", `V2.home`): 5 to 11 morning, 12 to 16
 * afternoon, 17 to 21 evening, and from 22 through the night to 4 "late". The words and the day's name are the screen's
 * (`cosmo_home_*`); this is only the decision. Pure.
 */
object HomeGreeting {
    enum class Period { MORNING, AFTERNOON, EVENING, LATE }

    /** The period of [hour] (0 to 23). An hour outside the day wraps (24 is midnight, -1 is 23 o'clock): a title never fails. */
    fun period(hour: Int): Period = when (((hour % 24) + 24) % 24) {
        in 5..11 -> Period.MORNING
        in 12..16 -> Period.AFTERNOON
        in 17..21 -> Period.EVENING
        else -> Period.LATE
    }
}
