package com.cosmotune.video.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ads, promos and upsells never reach a list; the real videos around them stay, in order. */
class YoutubeAdsFilterTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun video(id: String, channel: String = "A Channel", title: String = "Real $id", extra: String = "", url: String = "/watch?v=$id") =
        """{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"ownerText":{"runs":[{"text":"$channel"}]},""" +
            """"navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"$url"}}}$extra}}"""

    private fun lockup(id: String, type: String = "LOCKUP_CONTENT_TYPE_VIDEO", title: String = "Lockup $id", extra: String = "") =
        """{"lockupViewModel":{"contentId":"$id","contentType":"$type",""" +
            """"metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"}}}$extra}}"""

    /** One entry per ad or promo shape, as the site puts it in a list. */
    private val shapes: Map<String, String> = mapOf(
        "adSlot" to """{"adSlotRenderer":{"adSlotMetadata":{"slotId":"1"}}}""",
        "richAdSlot" to """{"richItemRenderer":{"content":{"adSlotRenderer":{"fulfillmentContent":{"fulfilledLayout":{"inFeedAdLayoutRenderer":""" +
            """{"renderingContent":{"videoRenderer":{"videoId":"adVideo00001","title":{"simpleText":"Buy now"}}}}}}}}}}""",
        "adSlotAndLayout" to """{"adSlotAndLayoutRenderer":{"x":1}}""",
        "promotedSparkles" to """{"promotedSparklesWebRenderer":{"title":{"simpleText":"Sponsored"}}}""",
        "promotedVideo" to """{"promotedVideoRenderer":{"videoId":"promoVideo01"}}""",
        "compactPromotedVideo" to """{"compactPromotedVideoRenderer":{"videoId":"promoVideo02"}}""",
        "searchPyv" to """{"searchPyvRenderer":{"ads":[{"promotedVideoRenderer":{"videoId":"promoVideo03"}}]}}""",
        "inFeedAd" to """{"inFeedAdLayoutRenderer":{"renderingContent":{"videoRenderer":{"videoId":"promoVideo04","title":{"simpleText":"x"}}}}}""",
        "statementBanner" to """{"statementBannerRenderer":{"text":{"simpleText":"Get Premium"}}}""",
        "brandVideoShelf" to """{"brandVideoShelfRenderer":{"title":{"simpleText":"Brand"}}}""",
        "brandVideoSingleton" to """{"brandVideoSingletonRenderer":{"videoId":"promoVideo05"}}""",
        "mealbar" to """{"mealbarPromoRenderer":{"x":1}}""",
        "primetime" to """{"primetimePromoRenderer":{"x":1}}""",
        "feedNudge" to """{"feedNudgeRenderer":{"title":{"simpleText":"Nudge"}}}""",
        "isAdFlag" to video("flagAd00001", extra = ""","isAd":true"""),
        "adSignals" to video("flagAd00002", extra = ""","adSignalsInfo":{"params":[]}"""),
        "promotedContent" to video("flagAd00003", extra = ""","promotedContent":{"a":1}"""),
        "adBadge" to video("flagAd00004", extra = ""","badges":[{"metadataBadgeRenderer":{"label":"Ad","style":"BADGE_STYLE_TYPE_AD"}}]"""),
        "sponsoredBadge" to video("flagAd00005", extra = ""","badges":[{"metadataBadgeRenderer":{"label":"Sponsored"}}]"""),
        "premiumUpsellEndpoint" to video("premium00001", url = "/premium"),
        "offerEndpoint" to video("offer0000001", extra = ""","ypcGetOfferEndpoint":{"params":"x"}"""),
        "trackingUrl" to video("track0000001", url = "https://www.googleadservices.com/pagead/aclk?adurl=x"),
        "foreignUrl" to video("foreign00001", url = "https://shop.example.com/buy"),
        "adLockup" to lockup("adLockup0001", type = "LOCKUP_CONTENT_TYPE_AD"),
        "flaggedLockup" to lockup("adLockup0002", extra = ""","adSignalsInfo":{"a":1}"""),
        "premiumPromoCard" to video("YTpromo00001", channel = "YouTube and YouTube Music", title = "Try YouTube Premium free"),
    )

    private val real = listOf("realVideo001", "realVideo002", "realVideo003")

    private fun rich(inner: String) = if (inner.startsWith("""{"richItemRenderer""")) inner else """{"richItemRenderer":{"content":$inner}}"""

    private fun answerFeed(items: List<String>) = json(
        """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":""" +
            """{"contents":[${items.joinToString(",")}]}}}}]}}}""",
    )

    private fun answerSearch(items: List<String>) = json(
        """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":""" +
            """[{"itemSectionRenderer":{"contents":[${items.joinToString(",")}]}}]}}}}}""",
    )

    private fun answerNext(items: List<String>) = json(
        """{"contents":{"twoColumnWatchNextResults":{"secondaryResults":{"secondaryResults":{"results":[${items.joinToString(",")}]}}}}}""",
    )

    /** Real videos at the start, in the middle of the ad shapes, and at the end. */
    private fun interleaved(wrap: (String) -> String): List<String> {
        val out = ArrayList<String>()
        out += wrap(video(real[0]))
        for ((i, shape) in shapes.values.withIndex()) {
            out += wrap(shape)
            if (i == shapes.size / 2) out += wrap(video(real[1]))
        }
        out += wrap(video(real[2]))
        return out
    }

    @Test fun aFeedKeepsOnlyTheRealVideos() {
        val parsed = YoutubeFeedParser.parse(answerFeed(interleaved(::rich)))
        assertEquals(real, parsed.videos.map { it.id })
    }

    @Test fun eachShapeAloneLeavesNothingInAFeed() {
        for ((name, shape) in shapes) {
            val parsed = YoutubeFeedParser.parse(answerFeed(listOf(rich(shape))))
            assertEquals(emptyList(), parsed.videos.map { it.id }, "$name yields no video")
            assertEquals(0, parsed.playlists.size + parsed.channels.size, "$name yields no list item")
            assertEquals(0, parsed.unreadable, "$name is skipped, not counted as unreadable")
        }
    }

    @Test fun searchResultsKeepOnlyTheRealVideos() {
        assertEquals(real, YoutubeFeedParser.parse(answerSearch(interleaved { it })).videos.map { it.id })
    }

    @Test fun upNextKeepsOnlyTheRealVideos() {
        val parsed = YoutubeFeedParser.parseRelated(answerNext(interleaved { it }), currentId = "current00001")
        assertEquals(real, parsed.videos.map { it.id })
    }

    @Test fun aPhoneShapedNextAnswerIsFilteredToo() {
        val phone = json(
            """{"contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[${interleaved { it }.joinToString(",")}]}}}}}""",
        )
        assertEquals(real, YoutubeFeedParser.parseRelated(phone).videos.map { it.id })
    }

    @Test fun realVideosWithOrdinaryEndpointsAndBadgesAreNotTakenForAds() {
        val verified = video(
            "verified0001",
            extra = ""","ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED","label":"Verified"}}],""" +
                """"badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW","label":"LIVE"}}],""" +
                """"menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"serviceEndpoint":{"commandMetadata":""" +
                """{"webCommandMetadata":{"url":"/youtubei/v1/share/get_share_panel"}}}}}]}},""" +
                """"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/verified0001/hq.jpg","width":480}]}""",
        )
        val titled = video("titled000001", title = "Ad", channel = "YouTube")
        val parsed = YoutubeFeedParser.parse(answerSearch(listOf(verified, titled, lockup("lockup000001"))))
        assertEquals(listOf("verified0001", "titled000001", "lockup000001"), parsed.videos.map { it.id })
    }
}
