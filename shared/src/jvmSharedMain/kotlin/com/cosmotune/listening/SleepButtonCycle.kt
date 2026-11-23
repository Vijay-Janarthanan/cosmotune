package com.cosmotune.listening

/**
 * The sleep timer's button on the phone notification (the idea from Kreate, see NOTICE.md): each tap moves to the next length,
 * off, 15, 30, then 60 minutes, then off again, measured from the tap. A running timer moves to the first length well past what is
 * left, so a timer set from the player joins the cycle where it fits.
 */
object SleepButtonCycle {
    val LENGTHS = listOf(15, 30, 60)

    /** The minutes the next tap sets, or null when it turns the timer off. [remainingMinutes] is null while none runs. */
    fun next(remainingMinutes: Int?): Int? {
        if (remainingMinutes == null) return LENGTHS.first()
        return LENGTHS.firstOrNull { it > remainingMinutes + 2 }
    }

    /** What the button says to a screen reader. */
    fun label(remainingMinutes: Int?): String = when (remainingMinutes) {
        null -> "Sleep timer"
        else -> "Sleep timer, $remainingMinutes min left"
    }
}
