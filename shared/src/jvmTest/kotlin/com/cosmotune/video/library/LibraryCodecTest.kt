package com.cosmotune.video.library

import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.settings.CodecPreference
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.TitleCase
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The documents of spec 8.1 (plan task Y-7, step 2): version 1 reads, every document round-trips, unknown keys are ignored. */
class LibraryCodecTest {

    private val codec = LibraryCodec
    private val sample = FakeVideoSource.videos[2]            // long title, a duration, views
    private val live = FakeVideoSource.videos[10]             // live: no duration
    private val short = FakeVideoSource.shorts[0]

    @Test
    fun theFileNamesAreThoseOfTheSpec() {
        assertEquals(
            listOf(
                "subscriptions.json", "history.json", "watch-later.json", "liked.json", "playlists.json",
                "saved-playlists.json", "searches.json", "settings.json",
            ),
            LibraryFile.entries.map { it.fileName },
        )
    }

    @Test
    fun everyDocumentIsWrittenAtVersionOne() {
        assertEquals(1, LibraryCodec.VERSION)
        val texts = listOf(
            codec.encodeSubscriptions(emptyList()),
            codec.encodeHistory(emptyList(), paused = false),
            codec.encodeItems(emptyList()),
            codec.encodePlaylists(emptyList()),
            codec.encodeSavedPlaylists(emptyList()),
            codec.encodeSearches(emptyList()),
            codec.encodeSettings(VideoSettings()),
        )
        for (text in texts) assertTrue(text.contains("\"version\":1"), text)
    }

    @Test
    fun subscriptionsRoundTrip() {
        val list = listOf(Subscription("UC1", "Northern Signal", "https://example.com/a.png", 1_000, 5_000), Subscription("UC2", "No avatar", null, 2_000, null))
        assertEquals(list, codec.decodeSubscriptions(codec.encodeSubscriptions(list)))
    }

    @Test
    fun historyRoundTripsWithItsPausedFlag() {
        val list = listOf(HistoryEntry(sample, 9_000, 60_000, 1_325_000, 3), HistoryEntry(live, 8_000, 0, 0, 1), HistoryEntry(short, 7_000, 9_000, 17_000, 1))
        assertEquals(HistoryDocument(list, paused = false), codec.decodeHistory(codec.encodeHistory(list, paused = false)))
        assertEquals(HistoryDocument(list, paused = true), codec.decodeHistory(codec.encodeHistory(list, paused = true)))
    }

    @Test
    fun aFullHistoryOfTenThousandEntriesRoundTrips() {
        val entries = (1..LibraryReducers.HISTORY_CAP).map { n ->
            HistoryEntry(sample.copy(id = "video-$n", title = "A video title of a typical length, number $n"), 1_000L * (10_001 - n), n * 1_000L, 1_325_000, 1 + n % 3)
        }
        val text = codec.encodeHistory(entries, paused = false)
        assertEquals(HistoryDocument(entries, paused = false), codec.decodeHistory(text))
    }

    @Test
    fun itemListsRoundTripWithEveryFieldOfTheVideo() {
        val full = VideoItem(
            id = "x", title = "T", channelId = "UC", channelName = "C", channelAvatarUrl = "a", thumbnailUrl = "t", durationSec = 61,
            viewCount = 7, uploadedAtMs = 99, uploadedText = "2 days ago", isLive = true, isUpcoming = true, isShort = true,
        )
        val list = listOf(full, sample, live)
        assertEquals(list, codec.decodeItems(codec.encodeItems(list)))
    }

    @Test
    fun playlistsRoundTripKeepingTheOrderOfTheirItems() {
        val list = listOf(
            LocalPlaylist("p1", "For the drive", listOf(sample, live, short), 10, 20),
            LocalPlaylist("p2", "Empty", emptyList(), 30, 30),
        )
        assertEquals(list, codec.decodePlaylists(codec.encodePlaylists(list)))
    }

    @Test
    fun savedPlaylistsAndSearchesRoundTrip() {
        val saved = listOf(FakeVideoSource.playlist, PlaylistItem("PL2", "Bare", null, null, null, null))
        assertEquals(saved, codec.decodeSavedPlaylists(codec.encodeSavedPlaylists(saved)))
        val searches = listOf("tape echo", "rain sounds")
        assertEquals(searches, codec.decodeSearches(codec.encodeSearches(searches)))
    }

    @Test
    fun nullsAreLeftOutOfTheFile() {
        val text = codec.encodeItems(listOf(live))
        assertTrue(!text.contains("null"), text)
    }

    @Test
    fun version1ReadsFromAHandWrittenDocument() {
        val text = """{"version":1,"subscriptions":[{"channelId":"UC1","name":"Name","avatarUrl":null,"subscribedAtMs":5,"lastUploadMs":6}]}"""
        assertEquals(listOf(Subscription("UC1", "Name", null, 5, 6)), codec.decodeSubscriptions(text))
        val history = """{"version":1,"paused":true,"entries":[{"item":{"id":"v1","title":"A"},"lastWatchedMs":1,"positionMs":2,"durationMs":3,"watchCount":4}]}"""
        val doc = codec.decodeHistory(history)
        assertTrue(doc.paused)
        assertEquals(HistoryEntry(VideoItem("v1", "A", null, null, null, null, null, null, null, null), 1, 2, 3, 4), doc.entries.single())
    }

    @Test
    fun unknownKeysAreIgnoredAtEveryLevel() {
        val text = """{"version":1,"future":{"a":1},"subscriptions":[{"channelId":"UC1","name":"N","subscribedAtMs":1,"newField":[1,2],"another":"x"}]}"""
        assertEquals(listOf("UC1"), codec.decodeSubscriptions(text).map { it.channelId })
    }

    @Test
    fun aDocumentWithoutAVersionReadsAsVersionOne() {
        assertEquals(listOf("rain"), codec.decodeSearches("""{"searches":["rain"]}"""))
    }

    @Test
    fun anEntryWithoutAnIdIsDropped() {
        val text = """{"version":1,"items":[{"title":"No id"},{"id":"ok","title":"Fine"},{"id":"","title":"Blank"}]}"""
        assertEquals(listOf("ok"), codec.decodeItems(text).map { it.id })
    }

    @Test
    fun aNewerVersionIsRefusedSoThatItsFileIsKeptAside() {
        val ex = assertFailsWith<LibraryFormatException> { codec.decodeSearches("""{"version":2,"searches":["rain"]}""") }
        assertTrue(ex.message!!.contains("version 2"), ex.message)
    }

    @Test
    fun versionZeroOrNotANumberIsRefused() {
        assertFailsWith<LibraryFormatException> { codec.decodeSearches("""{"version":0,"searches":[]}""") }
        assertFailsWith<LibraryFormatException> { codec.decodeSearches("""{"version":"one","searches":[]}""") }
    }

    @Test
    fun textThatIsNotADocumentIsRefused() {
        assertFailsWith<LibraryFormatException> { codec.decodeSubscriptions("") }
        assertFailsWith<LibraryFormatException> { codec.decodeSubscriptions("not json at all") }
        assertFailsWith<LibraryFormatException> { codec.decodeSubscriptions("[1,2,3]") }
        assertFailsWith<LibraryFormatException> { codec.decodeSubscriptions("""{"version":1,"subscriptions":"oops"}""") }
        assertFailsWith<LibraryFormatException> { codec.decodeSubscriptions("""{"version":1,"subscriptions":[""") }
    }

    // ------------------------------------------------------------------ settings

    private val everyFieldChanged = VideoSettings(
        wifiQuality = 1080, meteredQuality = 240, codec = CodecPreference.AV1, defaultSpeed = 1.5f, autoplay = false, resume = false,
        background = false, autoPip = false, doubleTapSeconds = 30, captionsLanguage = "hi", captionsScale = 1.25f,
        captionsBackground = false, sponsorBlock = false,
        segmentBehaviours = SegmentCategory.defaults() + (SegmentCategory.SPONSOR to SegmentBehaviour.ASK) + (SegmentCategory.FILLER to SegmentBehaviour.SKIP),
        segmentsOnBar = false, countSkips = true, deArrowTitles = false, deArrowThumbnails = false, titleCase = TitleCase.SENTENCE,
        dislikes = false, hideShorts = true, region = "IN", contentLanguage = "ta",
        downloads = DownloadPrefs(askBeforeEach = false, remembered = DownloadRequest(DownloadQuality.Rung(720, 30), SubtitlePick(true, "en"))),
        pauseOnLowBattery = false, downloadWifiOnly = false, downloadFolder = "content://tree/primary%3AMovies", firstRunSeen = true,
        homeKiosk = "gaming",
    )

    @Test
    fun everyFieldOfTheSettingsRoundTrips() {
        assertEquals(everyFieldChanged, codec.decodeSettings(codec.encodeSettings(everyFieldChanged)))
        assertEquals(VideoSettings(), codec.decodeSettings(codec.encodeSettings(VideoSettings())))
    }

    @Test
    fun theSettingsDocumentCoversEveryFieldOfVideoSettings() {
        val fields = VideoSettings::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(
            29, fields.size,
            "VideoSettings changed: ${fields.map { it.name }}. Add the new field to SettingsDocument in LibraryCodec.kt and to everyFieldChanged here.",
        )
    }

    @Test
    fun segmentBehavioursAreStoredAsApiNameToBehaviourName() {
        val text = codec.encodeSettings(everyFieldChanged)
        assertTrue(text.contains("\"sponsor\":\"ASK\""), text)
        assertTrue(text.contains("\"filler\":\"SKIP\""), text)
        assertTrue(text.contains("\"selfpromo\":\"ASK\""), text)
        assertTrue(text.contains("\"music_offtopic\":\"SHOW\""), text)
    }

    @Test
    fun anUnknownCategoryIsIgnoredAndAnUnknownBehaviourFallsBackToTheDefault() {
        val text = """{"version":1,"segmentBehaviours":{"sponsor":"MUTE","selfpromo":"EXPLODE","made_up_category":"SKIP","intro":"OFF"}}"""
        val behaviours = codec.decodeSettings(text).segmentBehaviours
        assertEquals(SegmentBehaviour.MUTE, behaviours[SegmentCategory.SPONSOR])
        assertEquals(SegmentCategory.SELF_PROMO.defaultBehaviour, behaviours[SegmentCategory.SELF_PROMO])
        assertEquals(SegmentBehaviour.OFF, behaviours[SegmentCategory.INTRO])
        assertEquals(SegmentCategory.entries.toSet(), behaviours.keys, "every category still has a behaviour")
    }

    @Test
    fun aCategoryMissingFromTheFileKeepsItsDefault() {
        val behaviours = codec.decodeSettings("""{"version":1,"segmentBehaviours":{}}""").segmentBehaviours
        assertEquals(SegmentCategory.defaults(), behaviours)
    }

    @Test
    fun unknownNamesOfTheOtherChoicesFallBackToTheirDefaults() {
        val s = codec.decodeSettings("""{"version":1,"codec":"WARP","titleCase":"SHOUTING","homeKiosk":"gaming"}""")
        assertEquals(CodecPreference.AUTO, s.codec)
        assertEquals(TitleCase.AS_GIVEN, s.titleCase)
        assertEquals("gaming", s.homeKiosk)
    }

    @Test
    fun anEmptyOrMinimalSettingsDocumentIsTheDefaults() {
        assertEquals(VideoSettings(), codec.decodeSettings("""{"version":1}"""))
        assertEquals(VideoSettings(), codec.decodeSettings("{}"))
    }

    @Test
    fun valuesThatCannotBeUsedFallBackToTheDefaults() {
        val s = codec.decodeSettings(
            """{"version":1,"wifiQuality":-5,"meteredQuality":0,"defaultSpeed":0,"doubleTapSeconds":7,"captionsScale":3.0}""",
        )
        assertNull(s.wifiQuality)
        assertNull(s.meteredQuality)
        assertEquals(1f, s.defaultSpeed)
        assertEquals(10, s.doubleTapSeconds)
        assertEquals(1f, s.captionsScale)
    }

    @Test
    fun theDownloadChoiceIsStoredWhateverTheQualityKind() {
        for (quality in listOf(DownloadQuality.Auto, DownloadQuality.Rung(2160, 60), DownloadQuality.AudioOnly)) {
            val s = VideoSettings(downloads = DownloadPrefs(true, DownloadRequest(quality, SubtitlePick(false, null))))
            assertEquals(s, codec.decodeSettings(codec.encodeSettings(s)))
        }
        val unknown = codec.decodeSettings("""{"version":1,"downloads":{"quality":{"kind":"warp"}}}""")
        assertEquals(DownloadQuality.Auto, unknown.downloads.remembered.quality)
    }

    @Test
    fun theRememberedSubtitleLanguageKeepsTheEmptyTagApartFromNone() {
        // Y-34: "" is "each video's original language" to the download engine, null "the app's language first"; a restart keeps them apart
        for (language in listOf("", null, "en", "pt-BR")) {
            val s = VideoSettings(downloads = DownloadPrefs(true, DownloadRequest(DownloadQuality.Auto, SubtitlePick(true, language))))
            assertEquals(language, codec.decodeSettings(codec.encodeSettings(s)).downloads.remembered.subtitles.language, "language \"$language\"")
        }
    }

    @Test
    fun aNewerSettingsVersionIsRefused() {
        assertFailsWith<LibraryFormatException> { codec.decodeSettings("""{"version":3}""") }
    }
}
