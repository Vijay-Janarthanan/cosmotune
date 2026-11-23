package com.cosmotune.music.playback

import kotlin.math.abs
import kotlin.test.*

class EqPresetTextTest {
    private val autoEq = """
        Preamp: -6.2 dB
        Filter 1: ON LSC Fc 105 Hz Gain 2.3 dB Q 0.70
        Filter 2: ON PK Fc 1000 Hz Gain -3.5 dB Q 1.41
        Filter 3: ON HSC Fc 10000 Hz Gain -4.0 dB Q 0.70
        Filter 4: OFF PK Fc 200 Hz Gain 1.0 dB Q 1.00
    """.trimIndent()

    @Test fun readsPreampAndEveryKindOfFilterAndSkipsSwitchedOffOnes() {
        val result = EqPresetText.parse(autoEq)
        assertEquals(-6.2f, result.preampDb)
        assertEquals(3, result.filters.size)
        assertEquals(EqFilter(FilterKind.LOW_SHELF, 105f, 2.3f, 0.70f), result.filters[0])
        assertEquals(EqFilter(FilterKind.BELL, 1000f, -3.5f, 1.41f), result.filters[1])
        assertEquals(FilterKind.HIGH_SHELF, result.filters[2].kind)
        assertTrue(result.rejected.isEmpty())
    }

    @Test fun acceptsShortShelfNamesCommentsBlankLinesAndWindowsLineEndings() {
        val result = EqPresetText.parse("# my curve\r\n\r\nFilter: ON LS Fc 80 Hz Gain 1 dB Q 0.7\r\nfilter 2: on hs fc 9000 hz gain -2 db q 0.7\r\n")
        assertEquals(2, result.filters.size)
        assertEquals(FilterKind.LOW_SHELF, result.filters[0].kind)
        assertEquals(FilterKind.HIGH_SHELF, result.filters[1].kind)
        assertNull(result.preampDb)
    }

    @Test fun badLinesAreReportedWithTheirLineNumberAndTheGoodOnesStillCount() {
        val result = EqPresetText.parse(
            """
            Preamp: loud
            Filter 1: ON PK Fc 1000 Hz Gain NaN dB Q 1
            Filter 2: ON PK Fc 5 Hz Gain 2 dB Q 1
            Filter 3: ON PK Fc 1000 Hz Gain 99 dB Q 1
            Filter 4: ON PK Fc 1000 Hz Gain 2 dB Q 0
            Filter 5: ON XX Fc 1000 Hz Gain 2 dB Q 1
            nonsense
            Filter 7: ON PK Fc 2000 Hz Gain 1.5 dB Q 2
            """.trimIndent(),
        )
        assertEquals(1, result.filters.size)
        assertEquals(2000f, result.filters.single().frequencyHz)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), result.rejected.map { it.lineNumber })
        assertNull(result.preampDb)
        assertTrue(result.rejected.all { it.reason.isNotBlank() })
    }

    @Test fun preampMustBeAtOrBelowZeroAndAtMostOneIsKept() {
        assertNull(EqPresetText.parse("Preamp: 4 dB").preampDb)
        assertEquals(1, EqPresetText.parse("Preamp: 4 dB").rejected.size)
        val twice = EqPresetText.parse("Preamp: -1 dB\nPreamp: -2 dB")
        assertEquals(-1f, twice.preampDb)
        assertEquals(1, twice.rejected.size)
    }

    @Test fun emptyOrHugeTextYieldsNothingAndNeverThrows() {
        assertTrue(EqPresetText.parse("").filters.isEmpty())
        val huge = (1..500).joinToString("\n") { "Filter $it: ON PK Fc 1000 Hz Gain 1 dB Q 1" }
        assertEquals(EqPresetText.MAX_FILTERS, EqPresetText.parse(huge).filters.size)
        assertTrue(EqPresetText.parse(huge).rejected.isNotEmpty())
    }

    @Test fun exportListsEverySevenBandsAsFiltersAndTheRoundTripReadsThemBack() {
        val bands = listOf(3f, 1.5f, 0f, -2f, 0.5f, 2f, -4f)
        val text = EqPresetText.export(bands, -3.4f)
        assertTrue(text.lines().first() == "Preamp: -3.4 dB", text)
        assertTrue("Filter 1: ON LSC Fc 60 Hz Gain 3.0 dB Q 1.00" in text, text)
        assertTrue("Filter 7: ON HSC Fc 14000 Hz Gain -4.0 dB Q 1.00" in text, text)
        val back = EqPresetText.parse(text)
        assertEquals(-3.4f, back.preampDb)
        assertEquals(7, back.filters.size)
        assertEquals(bands, back.filters.map { it.gainDb })
        assertEquals(EqLayout.MANUAL_BANDS_HZ.toList(), back.filters.map { it.frequencyHz })
        assertTrue(back.rejected.isEmpty())
    }

    @Test fun ourOwnExportComesBackAsTheSameBandsNotAnOverlapSum() {
        val bands = listOf(6f, 4f, 0f, -3f, 2f, 5f, -6f)
        val back = EqPresetText.mapToBands(EqPresetText.parse(EqPresetText.export(bands, 0f)).filters)
        bands.indices.forEach { assertEquals(bands[it], back.bandsDb[it], 0.25f, "band $it: ${back.bandsDb}") }
        assertFalse(back.clipped)
    }

    @Test fun aSingleBellLandsOnTheNearestBandsAndLeavesFarOnesAlone() {
        val mapped = EqPresetText.mapToBands(listOf(EqFilter(FilterKind.BELL, 1000f, 4f, 1f)))
        assertEquals(4f, mapped.bandsDb[3], 0.5f)
        assertTrue(abs(mapped.bandsDb[0]) < 0.6f)
        assertTrue(abs(mapped.bandsDb[6]) < 0.6f)
    }

    @Test fun gainBeyondTheSliderRangeIsClampedAndSaidSo() {
        val mapped = EqPresetText.mapToBands(
            listOf(EqFilter(FilterKind.BELL, 1000f, 20f, 1f), EqFilter(FilterKind.BELL, 1000f, 10f, 1f)),
        )
        assertEquals(EqLayout.MANUAL_RANGE_DB, mapped.bandsDb[3])
        assertTrue(mapped.clipped)
    }

    @Test fun noFiltersMapToFlat() {
        assertEquals(List(7) { 0f }, EqPresetText.mapToBands(emptyList()).bandsDb)
    }

    @Test fun typedBandValuesAreReadWithLimits() {
        assertEquals(2.5f, EqPresetText.parseGain("2.5"))
        assertEquals(-3f, EqPresetText.parseGain(" -3 dB "))
        assertEquals(1.5f, EqPresetText.parseGain("+1,5"))
        assertEquals(12f, EqPresetText.parseGain("40"))
        assertEquals(-12f, EqPresetText.parseGain("-40"))
        assertNull(EqPresetText.parseGain(""))
        assertNull(EqPresetText.parseGain("loud"))
        assertNull(EqPresetText.parseGain("NaN"))
    }
}
