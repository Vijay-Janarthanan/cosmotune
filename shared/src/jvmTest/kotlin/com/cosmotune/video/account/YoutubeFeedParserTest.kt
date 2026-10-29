package com.cosmotune.video.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YoutubeFeedParserTest {
    private fun parse(name: String) = YoutubeFeedParser.parse(AccountFixtures.json(name))

    // ---- Real anonymous answers ---------------------------------------------------------------------------------------

    @Test fun searchAnswerReadsVideoRenderersShortsLockupsAndAMixLockup() {
        val feed = parse("search_mixed.json")
        assertEquals(listOf("n61ULEU7CO0", "lTRiuFIWV54", "ekKUg7Nm0Z8", "axb1HFlJBOA"), feed.videos.map { it.id })
        val video = feed.videos[0]
        assertTrue(video.title.startsWith("Best of lofi hip hop 2021"))
        assertEquals("UCSJ4gkVC6NrvII8umztf0Ow", video.channelId)
        assertEquals("Lofi Girl", video.channelName)
        assertEquals(6 * 3600L + 10 * 60 + 58, video.durationSec)
        assertEquals(58_393_726L, video.viewCount)
        assertEquals("4 years ago", video.uploadedText)
        assertNotNull(video.thumbnailUrl)
        assertFalse(video.isShort || video.isLive)
        val short = feed.videos[2]
        assertTrue(short.isShort)
        assertNull(short.durationSec)
        assertEquals(76L, short.viewCount)
        assertNotNull(short.thumbnailUrl)
        assertTrue(short.title.startsWith("Lofi Hip Hop to Relax"))
        assertEquals(listOf("RDn61ULEU7CO0"), feed.playlists.map { it.id })
        assertEquals("SEARCH_TOKEN_1", feed.continuation)
        assertEquals(0, feed.unreadable)
        assertTrue(feed.loggedOut, "the fixture was recorded without a session")
    }

    @Test fun channelVideosTabReadsLockupsInsideRichItemsWithoutAChannelRow() {
        val feed = parse("channel_videos_grid.json")
        assertEquals(listOf("RE3_PGwDwhw", "plN7JMbadRg", "v9QtM6qnG50"), feed.videos.map { it.id })
        val first = feed.videos[0]
        assertEquals("Lose 150 Lbs Together, Win \$250,000", first.title)
        assertNull(first.channelName)
        assertNull(first.channelAvatarUrl)
        assertEquals(164_000_000L, first.viewCount)
        assertEquals("11 days ago", first.uploadedText)
        assertEquals(43 * 60L + 17, first.durationSec)
        assertNotNull(first.thumbnailUrl)
        assertEquals("CHANNEL_TOKEN_1", feed.continuation)
    }

    @Test fun relatedLockupsReadTheChannelRowTheAvatarAndTheStatsRow() {
        val feed = parse("related_lockups.json")
        assertEquals(listOf("6M6samPEMpM", "ci6ZtPAN0PM", "dGj-FIKWyJI"), feed.videos.map { it.id })
        val first = feed.videos[0]
        assertEquals("Backstreet Boys", first.channelName)
        assertEquals("UC1OR2YNQLZJYFdQjFrPWvVw", first.channelId)
        assertTrue(first.channelAvatarUrl!!.startsWith("https://yt3.ggpht.com/"))
        assertEquals(677_000_000L, first.viewCount)
        assertEquals("15y ago", first.uploadedText)
        assertEquals(6 * 60L + 3, first.durationSec)
        val third = feed.videos[2]
        assertEquals("HistoLEE", third.channelName)
        assertEquals(81L, third.viewCount)
        assertEquals("RELATED_TOKEN_1", feed.continuation)
        assertEquals(0, feed.unreadable)
    }

    @Test fun publicPlaylistPageReadsLockupItemsAndTheContinuationItemViewModel() {
        val feed = parse("playlist_page.json")
        assertEquals(listOf("ZOzk5UuyjTg", "v8iKLt8Gdvk", "Tn5L7y9yznE"), feed.videos.map { it.id })
        val first = feed.videos[0]
        assertEquals("YouTube's Worst Chef", first.title)
        assertEquals("AugustTheDuck", first.channelName)
        assertTrue(first.channelId!!.startsWith("UC"))
        assertEquals(2_400_000L, first.viewCount)
        assertEquals("PLAYLIST_TOKEN_1", feed.continuation)
    }

    @Test fun playlistLockupsReadTitleOwnerAndVideoCount() {
        val feed = parse("playlist_lockups.json")
        assertEquals(3, feed.playlists.size)
        val first = feed.playlists[0]
        assertEquals("PL5vUVoTXTeAMDtV0ZQR5Iu3bb1qwUIwYN", first.id)
        assertEquals("Every august the duck cooking with jack videos(in order).", first.title)
        assertEquals("Chris Maverick", first.ownerName)
        assertEquals("UCZ3_MDy3EoUTZ-DuQEHhxhw", first.ownerId)
        assertEquals(39L, first.videoCount)
        assertNotNull(first.thumbnailUrl)
        assertEquals("The Try Guys", feed.playlists[1].ownerName)
        assertEquals("PLAYLISTS_TOKEN_1", feed.continuation)
        assertTrue(feed.videos.isEmpty())
    }

    @Test fun signedOutWhatToWatchIsEmptyAndSaysSo() {
        val feed = parse("home_signed_out.json")
        assertTrue(feed.isEmpty)
        assertTrue(feed.loggedOut)
        assertFalse(feed.looksChanged)
        assertNull(feed.continuation)
    }

    // ---- Documented signed-in shapes (not live-verified) --------------------------------------------------------------

    @Test fun subscriptionsGridReadsVideosLiveUpcomingShortsAndSkipsAds() {
        val feed = parse("subscriptions_rich_grid.json")
        assertEquals(listOf("subVideo0001", "subLive00001", "subSoon00001", "subShort0001"), feed.videos.map { it.id })
        val video = feed.videos[0]
        assertEquals("Slow morning bread", video.title)
        assertEquals("Kitchen Table", video.channelName)
        assertEquals("UCkitchen00000000000001", video.channelId)
        assertEquals(12 * 60L + 34, video.durationSec)
        assertEquals(1234L, video.viewCount)
        assertEquals("2 hours ago", video.uploadedText)
        assertTrue(video.channelAvatarUrl!!.contains("avatar-"))
        assertEquals("https://i.ytimg.com/vi/subVideo0001/hqdefault.jpg", video.thumbnailUrl)
        val live = feed.videos[1]
        assertTrue(live.isLive)
        assertNull(live.durationSec)
        assertEquals(1200L, live.viewCount)
        assertTrue(feed.videos[2].isUpcoming)
        val short = feed.videos[3]
        assertTrue(short.isShort)
        assertEquals(84_000L, short.viewCount)
        assertEquals("Ten second knife skills", short.title)
        assertEquals("SUBS_TOKEN_1", feed.continuation)
        assertEquals(0, feed.unreadable)
    }

    @Test fun continuationActionsReadTheAppendedItemsAndTheNextToken() {
        val page = parse("continuation_actions.json")
        assertEquals(listOf("subVideo0002"), page.videos.map { it.id })
        assertEquals("SUBS_TOKEN_2", page.continuation)
        val last = parse("continuation_last.json")
        assertEquals(listOf("subVideo0003"), last.videos.map { it.id })
        assertEquals(3900L, last.videos[0].durationSec)
        assertEquals(2_500_000L, last.videos[0].viewCount)
        assertNull(last.continuation)
    }

    @Test fun aPageOfAdsIsEmptyButKeepsItsToken() {
        val ads = parse("continuation_ads_only.json")
        assertTrue(ads.isEmpty)
        assertEquals("SUBS_TOKEN_ADS", ads.continuation)
        assertFalse(ads.looksChanged)
    }

    @Test fun channelsPageReadsBothSubscriberCountShapesAndTheOlderContinuationsToken() {
        val feed = parse("channels_page.json")
        assertEquals(listOf("Kitchen Table", "Trail Notes", "Circuit Garden"), feed.channels.map { it.name })
        val kitchen = feed.channels[0]
        assertEquals("https://yt3.ggpht.com/kitchen=s88", kitchen.avatarUrl)
        assertEquals(1_200_000L, kitchen.subscriberCount)
        assertEquals(340L, kitchen.videoCount)
        assertEquals("Home cooking.", kitchen.description)
        assertTrue(kitchen.verified)
        assertNull(kitchen.handle)
        val trails = feed.channels[1]
        assertEquals("@trailnotes", trails.handle)
        assertEquals(85_000L, trails.subscriberCount)
        assertNull(trails.videoCount)
        assertEquals(430_000L, feed.channels[2].subscriberCount)
        assertEquals("CHANNELS_TOKEN_1", feed.continuation)
    }

    @Test fun guideListsOnlyTheSubscribedChannelsIncludingTheCollapsedOnes() {
        val channels = YoutubeFeedParser.parseGuideChannels(AccountFixtures.json("guide_subscriptions.json"))
        assertEquals(listOf("Kitchen Table", "Trail Notes"), channels.map { it.name })
        assertEquals("UCtrails0000000000000001", channels[1].id)
        assertEquals("https://yt3.ggpht.com/kitchen=s32", channels[0].avatarUrl)
    }

    @Test fun olderPlaylistVideoRenderersAndTheirContinuationsTokenAreRead() {
        val feed = parse("legacy_playlist.json")
        assertEquals(listOf("plVideo00001", "plVideo00002"), feed.videos.map { it.id })
        assertEquals(8 * 60L + 15, feed.videos[0].durationSec)
        assertEquals("Kitchen Table", feed.videos[0].channelName)
        assertEquals("PLAYLIST_TOKEN_OLD", feed.continuation)
    }

    @Test fun libraryPlaylistsReadCountsAndOwners() {
        val feed = parse("library_playlists.json")
        assertEquals(listOf("LL", "WL", "PLweekend0000001", "PLlonglist000002"), feed.playlists.map { it.id })
        assertEquals(12L, feed.playlists[2].videoCount)
        assertEquals(1234L, feed.playlists[3].videoCount)
        assertEquals("Asha Raman", feed.playlists[2].ownerName)
    }

    // ---- The Changed path ----------------------------------------------------------------------------------------------

    @Test fun anUnknownShapeWithVideoIdsLooksChangedAndDoesNotThrow() {
        val feed = parse("changed_shape.json")
        assertTrue(feed.isEmpty)
        assertEquals(1, feed.videoIdsSeen)
        assertTrue(feed.looksChanged)
    }

    @Test fun anItemWithoutATitleIsUnreadableNotACrash() {
        val broken = Json.parseToJsonElement("""{"contents":{"a":{"videoRenderer":{"videoId":"abcdefghijk"}}}}""") as JsonObject
        val feed = YoutubeFeedParser.parse(broken)
        assertEquals(1, feed.unreadable)
        assertTrue(feed.looksChanged)
    }

    @Test fun anEmptyListIsAnAnswerNotAChange() {
        val feed = parse("empty_list.json")
        assertTrue(feed.isEmpty)
        assertFalse(feed.looksChanged)
        assertFalse(feed.loggedOut)
    }

    @Test fun anAnswerWithNothingToWalkIsEmpty() {
        val feed = YoutubeFeedParser.parse(JsonObject(emptyMap()))
        assertTrue(feed.isEmpty)
        assertNull(feed.continuation)
    }

    // ---- Small readers -------------------------------------------------------------------------------------------------

    @Test fun durationsParseAsSecondsAndNothingElseDoes() {
        assertEquals(22258L, YoutubeFeedParser.parseDuration("6:10:58"))
        assertEquals(2597L, YoutubeFeedParser.parseDuration("43:17"))
        assertNull(YoutubeFeedParser.parseDuration("LIVE"))
        assertNull(YoutubeFeedParser.parseDuration("39 videos"))
        assertNull(YoutubeFeedParser.parseDuration(null))
    }

    @Test fun countsParsePlainGroupedAndScaledNumbers() {
        assertEquals(58_393_726L, YoutubeFeedParser.parseCount("58,393,726 views"))
        assertEquals(1_200_000L, YoutubeFeedParser.parseCount("1.2M views"))
        assertEquals(85_000L, YoutubeFeedParser.parseCount("85K subscribers"))
        assertEquals(2_000_000_000L, YoutubeFeedParser.parseCount("2B"))
        assertEquals(1L, YoutubeFeedParser.parseCount("1 view"))
        assertNull(YoutubeFeedParser.parseCount("No views"))
        assertNull(YoutubeFeedParser.parseCount(null))
    }

    @Test fun loggedOutIsReadFromTheServiceTrackingParams() {
        assertTrue(YoutubeFeedParser.isLoggedOut(AccountFixtures.json("home_signed_out.json")))
        assertFalse(YoutubeFeedParser.isLoggedOut(AccountFixtures.json("subscriptions_rich_grid.json")))
        assertFalse(YoutubeFeedParser.isLoggedOut(JsonObject(emptyMap())))
    }

    @Test fun errorBodiesMapToTypedFailures() {
        fun error(body: String) = YoutubeFeedParser.errorOf(Json.parseToJsonElement(body) as JsonObject)
        assertIs<AccountFailure.SessionExpired>(error("""{"error":{"code":401,"status":"UNAUTHENTICATED","message":"x"}}"""))
        assertIs<AccountFailure.RateLimited>(error("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""))
        assertIs<AccountFailure.Changed>(error("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"bad"}}"""))
        assertNull(error("""{"status":"STATUS_SUCCEEDED"}"""))
    }
}
