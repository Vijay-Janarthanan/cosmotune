package com.cosmotune.video.design

/** The unit of a relative age (spec 5.0: "3 days ago"); the plural strings of Video mode are one per unit. */
enum class AgeUnit { NOW, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

/** A relative age: [count] of [unit] (always 0 for [AgeUnit.NOW]). */
data class Age(val unit: AgeUnit, val count: Int)

/** A duration split for speaking it ("1 h 2 min"): whole [hours], then [minutes] and [seconds] below an hour. */
data class DurationParts(val hours: Int, val minutes: Int, val seconds: Int)

/**
 * The words and numbers the cards print (plan task Y-13, step 1; spec 5.0): view counts, ages and durations. Pure, so the
 * rules are tested without a window. Nothing here touches the JVM's formatting (this is common code, which no build step checks
 * against the common library): digits are written by hand, and the decimal point is always ".".
 */
object VideoText {
    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60 * SECOND_MS
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /** Weeks start at 7 days, months at 30 and years at 365 (spec 5.0 / plan Y-13 step 1). */
    private const val WEEK_DAYS = 7L
    private const val MONTH_DAYS = 30L
    private const val YEAR_DAYS = 365L

    /**
     * A count as the lists print it: 999 is "999", 1,234 is "1.2K", 12,345 is "12K", 1,234,567 is "1.2M", 2,100,000,000 is "2.1B".
     * One decimal below ten of a unit, none from ten on, and no ".0" ("1K"). The decimal is cut, not rounded, the way the source's
     * own counts read ("1.2K" for 1,299), so a count never rounds up into the next unit (999,999 is "999K", not "1000K"). A
     * negative count reads as "0". The billions go on past a thousand ("1000B").
     */
    fun compact(count: Long): String {
        if (count < 1_000L) return count.coerceAtLeast(0L).toString()
        val (unit, suffix) = when {
            count < 1_000_000L -> 1_000L to "K"
            count < 1_000_000_000L -> 1_000_000L to "M"
            else -> 1_000_000_000L to "B"
        }
        // tenths of the unit, by integer division (no floating point: no rounding up, no overflow near Long.MAX_VALUE)
        val tenths = count / (unit / 10)
        val whole = tenths / 10
        val decimal = tenths % 10
        return if (whole >= 10 || decimal == 0L) "$whole$suffix" else "$whole.$decimal$suffix"
    }

    /**
     * How long ago [epochMs] was at [nowMs], as the unit and count the plural strings take: under a minute (and anything from the
     * future, a clock a little ahead) is [AgeUnit.NOW]; then minutes, hours, days (to 6), weeks from 7 days, months from 30,
     * years from 365. Whole units, cut.
     */
    fun age(epochMs: Long, nowMs: Long): Age {
        val ago = nowMs - epochMs
        if (ago < MINUTE_MS) return Age(AgeUnit.NOW, 0)
        if (ago < HOUR_MS) return Age(AgeUnit.MINUTES, (ago / MINUTE_MS).toInt())
        if (ago < DAY_MS) return Age(AgeUnit.HOURS, (ago / HOUR_MS).toInt())
        val days = ago / DAY_MS
        return when {
            days < WEEK_DAYS -> Age(AgeUnit.DAYS, days.toInt())
            days < MONTH_DAYS -> Age(AgeUnit.WEEKS, (days / WEEK_DAYS).toInt())
            days < YEAR_DAYS -> Age(AgeUnit.MONTHS, (days / MONTH_DAYS).toInt())
            else -> Age(AgeUnit.YEARS, (days / YEAR_DAYS).toInt())
        }
    }

    /**
     * A duration as the badge prints it: "m:ss" under an hour (minutes not padded: "4:08", "12:05") and "h:mm:ss" from an hour
     * ("1:02:03"). A negative duration reads "0:00".
     */
    fun duration(sec: Long): String {
        val parts = durationParts(sec)
        val seconds = parts.seconds.pad2()
        return if (parts.hours > 0) "${parts.hours}:${parts.minutes.pad2()}:$seconds" else "${parts.minutes}:$seconds"
    }

    /** [sec] split into hours, minutes and seconds (a negative duration is zero); for the spoken form of a badge. */
    fun durationParts(sec: Long): DurationParts {
        val total = sec.coerceAtLeast(0L)
        return DurationParts(hours = (total / 3_600L).toInt(), minutes = ((total % 3_600L) / 60L).toInt(), seconds = (total % 60L).toInt())
    }

    private fun Int.pad2(): String = if (this < 10) "0$this" else toString()
}
