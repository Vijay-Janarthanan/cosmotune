package com.cosmotune.video.settings

import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.segments.SegmentCategory
import kotlinx.coroutines.flow.StateFlow

/** What happens with a SponsorBlock category (spec 5.11, 7.1): skip, mute, ask with a pill, show on the bar only, or off. */
enum class SegmentBehaviour { SKIP, MUTE, ASK, SHOW, OFF }

/** The preferred codec (spec 5.11, 6.4: Auto, AV1, VP9, H.264; it overrides when the device can decode it). */
enum class CodecPreference { AUTO, AV1, VP9, H264 }

/** How DeArrow titles are cased (spec 5.11, 7.2: as given, sentence case, title case). */
enum class TitleCase { AS_GIVEN, SENTENCE, TITLE }

/**
 * Video settings (spec 5.11; defaults in the constructor). Qualities are a height, or null for Auto: Auto on Wi-Fi and on
 * mobile data by default (the owner's decision, Q7). [captionsScale] is the size setting (0.8, 1 or 1.25);
 * [hideShorts] is off by default (Shorts shown: the owner's decision, Q6); [region] null is the device's;
 * [downloads] holds "Ask before each download" and the remembered choice (spec 5.16); [homeKiosk] is Home's selected
 * kiosk (spec 5.2: the choice is remembered).
 */
data class VideoSettings(
    val wifiQuality: Int? = null /* null = Auto */,
    val meteredQuality: Int? = null /* Auto too: the owner's decision, Q7 */,
    val codec: CodecPreference = CodecPreference.AUTO,
    val defaultSpeed: Float = 1f,
    val autoplay: Boolean = true,
    val resume: Boolean = true,
    val background: Boolean = true,
    val autoPip: Boolean = true,
    val doubleTapSeconds: Int = 10,
    val captionsLanguage: String? = null,
    val captionsScale: Float = 1f,
    val captionsBackground: Boolean = true,
    val sponsorBlock: Boolean = true,
    val segmentBehaviours: Map<SegmentCategory, SegmentBehaviour> = SegmentCategory.defaults(),
    val segmentsOnBar: Boolean = true,
    val countSkips: Boolean = false,
    val deArrowTitles: Boolean = true,
    val deArrowThumbnails: Boolean = true,
    val titleCase: TitleCase = TitleCase.AS_GIVEN,
    val dislikes: Boolean = true,
    val hideShorts: Boolean = false /* Shorts shown: the owner's decision, Q6 */,
    val region: String? = null /* null = the device's */,
    val contentLanguage: String? = null,
    val downloads: DownloadPrefs = DownloadPrefs(),
    val pauseOnLowBattery: Boolean = true,
    val downloadWifiOnly: Boolean = true,
    val downloadFolder: String? = null,
    val firstRunSeen: Boolean = false,
    val homeKiosk: String = "music",
) {
    companion object {
        /** The quality rows' choices (spec 5.11): Auto (null), 2160p, 1440p, 1080p, 720p, 480p, 360p, 240p, 144p. */
        val QUALITY_CHOICES: List<Int?> = listOf(null, 2160, 1440, 1080, 720, 480, 360, 240, 144)

        /** The double-tap seek choices in seconds (spec 5.11: 5, 10, 15, 30; default 10). */
        val DOUBLE_TAP_CHOICES: List<Int> = listOf(5, 10, 15, 30)

        /** The caption sizes small, medium and large as factors (spec 6.5: 0.8, 1, 1.25). */
        val CAPTION_SCALES: List<Float> = listOf(0.8f, 1f, 1.25f)
    }
}

/** Where Video settings are kept (spec 5.11, 8.1 `settings.json`; the store is plan task Y-7). */
interface VideoSettingsRepository {
    /** The current settings. */
    val settings: StateFlow<VideoSettings>

    /** Replaces the settings with [transform] of the current ones. */
    fun update(transform: (VideoSettings) -> VideoSettings)
}
