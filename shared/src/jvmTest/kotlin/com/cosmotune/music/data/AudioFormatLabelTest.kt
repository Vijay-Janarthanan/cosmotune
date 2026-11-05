package com.cosmotune.music.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioFormatLabelTest {
    @Test fun measuredEncodedBitrateIsShownWithDecoderFormat() {
        val stats = NerdStats.Snapshot("audio/opus", 160, 48000, 2,
            bitrateProvenance = TelemetryProvenance.MEASURED)
        assertEquals("Opus · 160 kbps · 48.0 kHz", stats.audioFormatLabel())
    }

    @Test fun pcmThroughputIsNeverLabelledAsEncodedBitrate() {
        val stats = NerdStats.Snapshot("audio/flac", null, 44100, 2, bitDepth = 24,
            pcmDataRateKbps = 2116)
        assertEquals("FLAC · 24-bit · 44.1 kHz", stats.audioFormatLabel())
    }

    @Test fun derivedBitrateIsOmittedAndMissingMeasurementsStayEmpty() {
        val stats = NerdStats.Snapshot(null, 1411, null, null,
            bitrateProvenance = TelemetryProvenance.DERIVED)
        assertNull(stats.audioFormatLabel())
    }
}
