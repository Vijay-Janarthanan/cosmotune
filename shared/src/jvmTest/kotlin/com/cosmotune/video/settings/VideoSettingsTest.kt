package com.cosmotune.video.settings

import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.segments.SegmentCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Video settings' defaults are those of spec 5.11 (with the owner's decisions Q6 and Q7), one assertion per field. */
class VideoSettingsTest {

    private val s = VideoSettings()

    @Test
    fun playbackDefaults() {
        assertNull(s.wifiQuality)                  // Auto on Wi-Fi
        assertNull(s.meteredQuality)               // Auto on mobile data too (Q7)
        assertEquals(CodecPreference.AUTO, s.codec)
        assertEquals(1f, s.defaultSpeed)
        assertTrue(s.autoplay)
        assertTrue(s.resume)
        assertTrue(s.background)
        assertTrue(s.autoPip)
        assertEquals(10, s.doubleTapSeconds)
    }

    @Test
    fun captionDefaults() {
        assertNull(s.captionsLanguage)             // off by default
        assertEquals(1f, s.captionsScale)          // medium
        assertTrue(s.captionsBackground)
    }

    @Test
    fun segmentAndBrandingDefaults() {
        assertTrue(s.sponsorBlock)
        assertEquals(SegmentCategory.defaults(), s.segmentBehaviours)
        assertTrue(s.segmentsOnBar)
        assertFalse(s.countSkips)                  // off: the segment UUID identifies the video (7.1)
        assertTrue(s.deArrowTitles)
        assertTrue(s.deArrowThumbnails)
        assertEquals(TitleCase.AS_GIVEN, s.titleCase)
        assertTrue(s.dislikes)
    }

    @Test
    fun contentDefaults() {
        assertFalse(s.hideShorts)                  // Shorts shown (Q6)
        assertNull(s.region)                       // the device's
        assertNull(s.contentLanguage)
        assertEquals("music", s.homeKiosk)
        assertFalse(s.firstRunSeen)
    }

    @Test
    fun downloadDefaults() {
        assertEquals(DownloadPrefs(), s.downloads)
        assertTrue(s.downloads.askBeforeEach)
        assertEquals(DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null)), s.downloads.remembered)
        assertTrue(s.pauseOnLowBattery)
        assertTrue(s.downloadWifiOnly)
        assertNull(s.downloadFolder)
    }

    @Test
    fun theChoicesOfTheSettingsRowsAreThoseOfSpec511() {
        assertEquals(listOf(null, 2160, 1440, 1080, 720, 480, 360, 240, 144), VideoSettings.QUALITY_CHOICES)
        assertEquals(listOf(5, 10, 15, 30), VideoSettings.DOUBLE_TAP_CHOICES)
        assertEquals(listOf(0.8f, 1f, 1.25f), VideoSettings.CAPTION_SCALES)
    }

    @Test
    fun theFakeRepositoryAppliesUpdates() {
        val repo = FakeVideoSettingsRepository()
        assertEquals(VideoSettings(), repo.settings.value)
        repo.update { it.copy(meteredQuality = 144, hideShorts = true) }
        assertEquals(144, repo.settings.value.meteredQuality)
        assertTrue(repo.settings.value.hideShorts)
    }
}
