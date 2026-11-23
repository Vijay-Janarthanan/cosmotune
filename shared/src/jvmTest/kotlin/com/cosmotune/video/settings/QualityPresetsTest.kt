package com.cosmotune.video.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QualityPresetsTest {
    @Test fun theDefaultsAreBest() = assertEquals(QualityPreset.BEST, QualityPreset.of(VideoSettings()))

    @Test fun aPresetSetsBothQualitiesAndTheCodec() {
        val saved = QualityPreset.DATA_SAVER.applyTo(VideoSettings(codec = CodecPreference.entries.last()))
        assertEquals(480, saved.wifiQuality)
        assertEquals(360, saved.meteredQuality)
        assertEquals(QualityPreset.DATA_SAVER, QualityPreset.of(saved))
    }

    @Test fun anythingElseIsCustom() = assertNull(QualityPreset.of(VideoSettings(wifiQuality = 1440, meteredQuality = 144)))
}
