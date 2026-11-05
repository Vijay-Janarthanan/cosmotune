package com.cosmotune.video.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The words and numbers of Video mode's cards (plan task Y-13, step 1): view counts, ages and durations as the lists print them. */
class VideoTextTest {

    // ---- compact ----------------------------------------------------------------------------------------------------------

    @Test
    fun theSpecsCompactExamplesReadAsTheyAreWritten() {
        assertEquals("999", VideoText.compact(999))
        assertEquals("1.2K", VideoText.compact(1_234))
        assertEquals("12K", VideoText.compact(12_345))
        assertEquals("1.2M", VideoText.compact(1_234_567))
        assertEquals("2.1B", VideoText.compact(2_100_000_000L))
    }

    @Test
    fun smallNumbersAreWrittenInFull() {
        assertEquals("0", VideoText.compact(0))
        assertEquals("1", VideoText.compact(1))
        assertEquals("100", VideoText.compact(100))
    }

    @Test
    fun aWholeThousandHasNoDecimalZero() {
        assertEquals("1K", VideoText.compact(1_000))
        assertEquals("1K", VideoText.compact(1_050))
        assertEquals("2M", VideoText.compact(2_000_000))
        assertEquals("3B", VideoText.compact(3_000_000_000L))
    }

    @Test
    fun theDecimalIsCutNotRounded() {
        // the way the source's own counts read: 1,299 views is "1.2K", never "1.3K"
        assertEquals("1.2K", VideoText.compact(1_299))
        assertEquals("9.9K", VideoText.compact(9_999))
        assertEquals("1.9M", VideoText.compact(1_999_999))
    }

    @Test
    fun fromTenOnThereIsNoDecimal() {
        assertEquals("10K", VideoText.compact(10_000))
        assertEquals("99K", VideoText.compact(99_999))
        assertEquals("123K", VideoText.compact(123_456))
        assertEquals("12M", VideoText.compact(12_345_678))
    }

    @Test
    fun aCountNeverRoundsUpIntoTheNextUnit() {
        assertEquals("999K", VideoText.compact(999_999))
        assertEquals("999M", VideoText.compact(999_999_999))
    }

    @Test
    fun theBillionsGoOnPastAThousand() {
        assertEquals("1000B", VideoText.compact(1_000_000_000_000L))
        assertEquals("9223372036B", VideoText.compact(Long.MAX_VALUE))
    }

    @Test
    fun aNegativeCountReadsAsZero() {
        assertEquals("0", VideoText.compact(-5))
    }

    // ---- age --------------------------------------------------------------------------------------------------------------

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 1_792_152_000_000L

    private fun ageAt(ms: Long) = VideoText.age(now - ms, now)

    @Test
    fun underAMinuteIsNow() {
        assertEquals(Age(AgeUnit.NOW, 0), ageAt(0))
        assertEquals(Age(AgeUnit.NOW, 0), ageAt(59 * second + 999))
    }

    @Test
    fun aVideoFromTheFutureIsNow() {
        assertEquals(Age(AgeUnit.NOW, 0), VideoText.age(now + 5 * minute, now))
    }

    @Test
    fun minutesRunFromOneToFiftyNine() {
        assertEquals(Age(AgeUnit.MINUTES, 1), ageAt(minute))
        assertEquals(Age(AgeUnit.MINUTES, 5), ageAt(5 * minute + 30 * second))
        assertEquals(Age(AgeUnit.MINUTES, 59), ageAt(hour - 1))
    }

    @Test
    fun hoursRunFromOneToTwentyThree() {
        assertEquals(Age(AgeUnit.HOURS, 1), ageAt(hour))
        assertEquals(Age(AgeUnit.HOURS, 23), ageAt(day - 1))
    }

    @Test
    fun daysRunFromOneToSix() {
        assertEquals(Age(AgeUnit.DAYS, 1), ageAt(day))
        assertEquals(Age(AgeUnit.DAYS, 3), ageAt(3 * day + 5 * hour))
        assertEquals(Age(AgeUnit.DAYS, 6), ageAt(7 * day - 1))
    }

    @Test
    fun weeksStartAtSevenDays() {
        assertEquals(Age(AgeUnit.WEEKS, 1), ageAt(7 * day))
        assertEquals(Age(AgeUnit.WEEKS, 1), ageAt(13 * day))
        assertEquals(Age(AgeUnit.WEEKS, 2), ageAt(14 * day))
        assertEquals(Age(AgeUnit.WEEKS, 4), ageAt(29 * day))
    }

    @Test
    fun monthsStartAtThirtyDays() {
        assertEquals(Age(AgeUnit.MONTHS, 1), ageAt(30 * day))
        assertEquals(Age(AgeUnit.MONTHS, 1), ageAt(59 * day))
        assertEquals(Age(AgeUnit.MONTHS, 2), ageAt(60 * day))
        assertEquals(Age(AgeUnit.MONTHS, 12), ageAt(364 * day))
    }

    @Test
    fun yearsStartAt365Days() {
        assertEquals(Age(AgeUnit.YEARS, 1), ageAt(365 * day))
        assertEquals(Age(AgeUnit.YEARS, 1), ageAt(729 * day))
        assertEquals(Age(AgeUnit.YEARS, 2), ageAt(730 * day))
        assertEquals(Age(AgeUnit.YEARS, 10), ageAt(3_650 * day))
    }

    // ---- duration ---------------------------------------------------------------------------------------------------------

    @Test
    fun underAnHourIsMinutesAndSecondsWithTheMinutesNotPadded() {
        assertEquals("0:00", VideoText.duration(0))
        assertEquals("0:05", VideoText.duration(5))
        assertEquals("0:59", VideoText.duration(59))
        assertEquals("1:00", VideoText.duration(60))
        assertEquals("4:08", VideoText.duration(248))
        assertEquals("12:05", VideoText.duration(725))
        assertEquals("59:59", VideoText.duration(3_599))
    }

    @Test
    fun fromAnHourOnIsHoursMinutesAndSeconds() {
        assertEquals("1:00:00", VideoText.duration(3_600))
        assertEquals("1:02:03", VideoText.duration(3_723))
        assertEquals("10:00:00", VideoText.duration(36_000))
        assertEquals("100:00:00", VideoText.duration(360_000))
    }

    @Test
    fun aNegativeDurationReadsAsZero() {
        assertEquals("0:00", VideoText.duration(-1))
    }

    @Test
    fun theSpokenPartsOfADuration() {
        assertEquals(DurationParts(0, 0, 45), VideoText.durationParts(45))
        assertEquals(DurationParts(0, 4, 8), VideoText.durationParts(248))
        assertEquals(DurationParts(1, 2, 3), VideoText.durationParts(3_723))
        assertEquals(DurationParts(0, 0, 0), VideoText.durationParts(-9))
    }
}
