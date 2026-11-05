package com.cosmotune.ui

import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.TelemetryProvenance
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.nowplaying.QualityEvidence
import com.cosmotune.ui.nowplaying.QualityTone
import com.cosmotune.ui.nowplaying.catalogueQualityBadge
import com.cosmotune.ui.nowplaying.colours
import com.cosmotune.ui.nowplaying.measuredQualityBadge
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The quality pills: full-resolution text from measured figures, catalogue claims worded as claims, and readable colours. */
class QualityBadgeTest {

    private fun stream(mime: String?, kbps: Int? = null, rate: Int? = null, bits: Int? = null, container: String? = null) =
        NerdStats.Snapshot(mimeType = mime, bitrateKbps = kbps, sampleRateHz = rate, channels = 2, bitDepth = bits, container = container,
            bitrateProvenance = TelemetryProvenance.MEASURED)

    @Test fun losslessNamesTheCodecDepthAndRate() {
        val hiRes = stream("audio/flac", kbps = 2400, rate = 96_000, bits = 24).measuredQualityBadge()!!
        assertEquals("FLAC · 24/96", hiRes.text)
        assertEquals(QualityTone.HI_RES, hiRes.tone)
        assertEquals(QualityEvidence.MEASURED, hiRes.evidence)
        assertEquals("ALAC · 16/44.1", stream("audio/alac", rate = 44_100, bits = 16).measuredQualityBadge()!!.text)
        assertEquals(QualityTone.LOSSLESS, stream("audio/alac", rate = 44_100, bits = 16).measuredQualityBadge()!!.tone)
        assertEquals("FLAC · 48 kHz", stream("audio/flac", rate = 48_000).measuredQualityBadge()!!.text, "only the known half")
        assertEquals("FLAC", stream("audio/flac").measuredQualityBadge()!!.text)
        assertEquals("DSF · 2.8224 MHz", stream("audio/raw", rate = 2_822_400, container = "dsf").measuredQualityBadge()!!.text)
    }

    @Test fun lossyNamesTheCodecAndBitrateAndNeverABitDepth() {
        val aac = stream("audio/mp4a-latm", kbps = 256, rate = 44_100, bits = 24).measuredQualityBadge()!!
        assertEquals("AAC 256", aac.text)
        assertEquals(QualityTone.HIGH, aac.tone)
        assertFalse("24" in aac.text, "a decoder's PCM width is not a lossy source's depth")
        assertEquals(QualityTone.STANDARD, stream("audio/opus", kbps = 160).measuredQualityBadge()!!.tone)
        assertEquals(QualityTone.LOW, stream("audio/opus", kbps = 64).measuredQualityBadge()!!.tone)
        val derived = NerdStats.Snapshot("audio/opus", 160, 48_000, 2, bitrateProvenance = TelemetryProvenance.DERIVED).measuredQualityBadge()!!
        assertEquals("Opus", derived.text, "a bitrate that was not measured is not printed")
    }

    @Test fun atmosIsItsOwnPillAndNeverLossless() {
        val atmos = stream("audio/eac3-joc", kbps = 768).measuredQualityBadge()!!
        assertEquals(QualityTone.ATMOS, atmos.tone)
        assertEquals("Dolby Atmos", atmos.text)
        assertFalse("ossless" in atmos.text)
    }

    @Test fun nothingIsClaimedBeforeTheDecoderMeasured() {
        assertNull(stream(null).measuredQualityBadge())
    }

    @Test fun aCatalogueTagIsWordedAsAnOffer() {
        fun song(tag: String?, hiRes: Boolean = false) = Song("id", "Title", "Artist", null, sourceQuality = tag, sourceHiRes = hiRes)
        val atmos = song("DOLBY").catalogueQualityBadge()!!
        assertEquals("Atmos available", atmos.text, "an Atmos edition in the catalogue is not Atmos playing")
        assertEquals(QualityEvidence.CATALOGUE, atmos.evidence)
        assertTrue("reported by the catalogue" in atmos.spoken)
        assertEquals("Hi-Res", song("FLAC", hiRes = true).catalogueQualityBadge()!!.text)
        assertEquals("Lossless", song("LOSSLESS").catalogueQualityBadge()!!.text)
        assertEquals("High", song("320").catalogueQualityBadge()!!.text)
        assertNull(song("LOW").catalogueQualityBadge())
        assertNull(song(null).catalogueQualityBadge())
    }

    // ---- the colours: 4.5:1 for the text over the pill's fill on the darkest and lightest grounds of each theme ----

    private fun lin(c: Int): Double { val x = c / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
    private fun lum(c: Int) = 0.2126 * lin((c shr 16) and 255) + 0.7152 * lin((c shr 8) and 255) + 0.0722 * lin(c and 255)
    private fun contrast(a: Int, b: Int) = (max(lum(a), lum(b)) + 0.05) / (min(lum(a), lum(b)) + 0.05)
    private fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun ch(s: Int) = ((((fg shr s) and 255) * a) + (((bg shr s) and 255) * (1 - a))).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    @Test fun everyPillReadsOnEveryGroundOfItsTheme() {
        // The neutral roles of DESIGN_SYSTEM.md 2.1: bg, AMOLED black and the three surface tiers.
        val darkGrounds = listOf(0xFF000000.toInt(), 0xFF0F0F11.toInt(), 0xFF17171A.toInt(), 0xFF1F1F23.toInt(), 0xFF29292E.toInt())
        val lightGrounds = listOf(0xFFFFFFFF.toInt(), 0xFFF7F7F8.toInt(), 0xFFEFEFF1.toInt(), 0xFFE4E4E7.toInt())
        for (tone in QualityTone.entries) for (evidence in QualityEvidence.entries) for (dark in listOf(true, false)) {
            val c = tone.colours(dark, evidence)
            for (ground in if (dark) darkGrounds else lightGrounds) {
                val under = over(c.fill, ground)
                assertTrue(contrast(c.ink, under) >= 4.5, "$tone $evidence dark=$dark on ${Integer.toHexString(ground)}: ${contrast(c.ink, under)}")
            }
        }
    }

    @Test fun aCataloguePillHasNoFillSoItLooksApartFromAMeasuredOne() {
        for (tone in QualityTone.entries.filter { it != QualityTone.ATMOS }) {
            assertEquals(0, tone.colours(true, QualityEvidence.CATALOGUE).fill ushr 24, "$tone: outlined only")
            assertTrue(tone.colours(true, QualityEvidence.MEASURED).fill ushr 24 > 0, "$tone: measured is filled")
        }
    }
}
