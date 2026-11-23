package com.cosmotune.watch

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchSubtitleStyleTest {
    @Test fun oldPreferencesWithoutAStyleReadTheDefault() {
        val read = Json { ignoreUnknownKeys = true }.decodeFromString(WatchPreferences.serializer(), """{"density":"COMPACT"}""")
        assertEquals(WatchSubtitleStyle(), read.subtitles)
        assertEquals(WatchDensity.COMPACT, read.density)
    }

    @Test fun numbersOutsideTheirRangeAreBroughtIn() {
        val style = WatchSubtitleStyle(sizePercent = 900, backgroundPercent = -5, liftPercent = 90).coerced()
        assertEquals(200, style.sizePercent)
        assertEquals(20, style.backgroundPercent)
        assertEquals(40, style.liftPercent)
    }

    @Test fun textGrowsWithThePictureWithinLimits() {
        val style = WatchSubtitleStyle()
        assertEquals(14f, style.textSize(200f))
        assertEquals(34f, style.textSize(2000f))
        assertEquals(51f, WatchSubtitleStyle(sizePercent = 150).textSize(1000f), 0.01f)
    }

    @Test fun stepsMoveOneAtATimeAndStopAtTheEnds() {
        assertEquals(125, WatchSubtitleStyle.next(WatchSubtitleStyle.SIZES, 100, 1))
        assertEquals(200, WatchSubtitleStyle.next(WatchSubtitleStyle.SIZES, 200, 1))
        assertEquals(75, WatchSubtitleStyle.next(WatchSubtitleStyle.SIZES, 80, -1))
    }
}
