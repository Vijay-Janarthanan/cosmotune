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

/** The colour of the subtitles' text (ARGB). */
enum class CaptionColor(val argb: Long) { WHITE(0xFFFFFFFF), YELLOW(0xFFFFE94D), CYAN(0xFF8EF0FF), GREEN(0xFFB6F5C4) }

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
    /** The subtitles' text colour. */
    val captionsColor: CaptionColor = CaptionColor.WHITE,
    /** The subtitles sit at the top of the picture instead of the bottom. */
    val captionsTop: Boolean = false,
    /** Members-only and paid videos are left out of every list. */
    val hidePaid: Boolean = false,
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
    val homeAccountEnabled: Boolean = true,
    val homeHiddenVideos: Set<String> = emptySet(),
    val homeHiddenChannels: Set<String> = emptySet(),
    /** Words (or /expressions/) whose videos and comments are hidden everywhere. */
    val hiddenWords: List<String> = emptyList(),
    /** Channels hidden everywhere (id to the name shown in Settings). */
    val hiddenChannels: Map<String, String> = emptyMap(),
    /** Comments with fewer likes than this are hidden (0: none). */
    val commentMinLikes: Long = 0,
    /** The listener's channel groups (Subscriptions' group chips). */
    val feedGroups: List<com.cosmotune.video.feed.FeedChannelGroup> = emptyList(),
    /** Channels whose new videos are announced (the bell beside Subscribe). */
    val bellChannels: Set<String> = emptySet(),
    /** Uploads up to this time have been announced already. */
    val uploadAlertsSeenMs: Long = 0,
    /** Channels where SponsorBlock never skips or mutes (id to name); their segments still show on the bar. */
    val sponsorBlockExcluded: Map<String, String> = emptyMap(),
    /** The volume over the normal level, in percent (100: none; up to 200). */
    val volumeBoost: Int = 100,
    /** Lifts speech and evens out loud and soft parts. */
    val voiceBoost: Boolean = false,
    /** Skips silent parts (where the player can). */
    val skipSilence: Boolean = false,
    /** Channel pages leave out videos watched to the end. */
    val channelHideWatched: Boolean = false,
    /** Automatic library backup every this many days (0: off). */
    val autoBackupDays: Int = 0,
    /** When the last automatic backup was written. */
    val autoBackupLastMs: Long = 0,
    /** Each video plays again at the speed it was last watched at. */
    val rememberSpeed: Boolean = false,
    /** The speed each video was last watched at (with rememberSpeed). */
    val speedByVideo: Map<String, Float> = emptyMap(),
    /** Downloads carry their title, channel, chapters, subtitles and cover inside the file, for other players. */
    val embedDetails: Boolean = true,
    /** Downloads leave out the SponsorBlock segments set to skip. */
    val cutSegments: Boolean = false,
    /** The YouTube app on a phone may cast here with "Link with TV code". */
    val phoneCasting: Boolean = false,
    /** This screen's lounge id, so a linked phone finds it again. */
    val loungeScreenId: String? = null,
    /** A headset's or a car's previous and next keys skip back and on instead of changing the video. */
    val mediaKeysSeek: Boolean = false,
    /** Cards and the watch page say when SponsorBlock labels the whole video (sponsored, self-promotion, exclusive access). */
    val wholeVideoLabels: Boolean = true,
    /** The listener's private random SponsorBlock id, made on the first segment they send. */
    val sponsorBlockUserId: String? = null,
    /** How much of the width at each side full screen's brightness and volume drags take, in percent. */
    val swipeEdgePercent: Int = 40,
    /** A drag in the middle of full screen changes the speed. */
    val swipeSpeed: Boolean = false,
    val smoothness: SmoothnessSettings = SmoothnessSettings() /* frame smoothing: off, so frames are shown as they come */,
) {
    companion object {
        /** The quality rows' choices (spec 5.11): Auto (null), 2160p, 1440p, 1080p, 720p, 480p, 360p, 240p, 144p. */
        /** The swipe area choices, in percent of the width. */
        val SWIPE_EDGE_CHOICES: List<Int> = listOf(25, 30, 35, 40, 45, 50)

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
