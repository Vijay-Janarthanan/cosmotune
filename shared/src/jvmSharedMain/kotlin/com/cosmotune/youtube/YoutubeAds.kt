package com.cosmotune.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The one place that says what is an ad, a promotion or an upsell in a YouTube list. Every parser of youtubei lists (Video's feed
 * parser, the Music-side video search, the NewPipe mapper) asks here, so a new list cannot forget it.
 *
 * Two questions: [isAdRenderer] (the item's own key says it is an ad: skip it and everything under it) and [isAdItem] (an item
 * whose renderer is ordinary, such as a `videoRenderer` or a `lockupViewModel`, but which is flagged or points like an ad).
 * [isPromoListing] covers the same promos once they have been read into a title and a channel name.
 *
 * Neutral on purpose: it names no mode, so Music and Video can both use it.
 */
internal object YoutubeAds {
    /** Renderer and view-model keys that are ads, banners or prompts, never content. */
    private val AD_KEYS = setOf(
        "adSlotRenderer", "adSlotAndLayoutRenderer", "adLayoutRenderer", "inFeedAdLayoutRenderer", "displayAdRenderer",
        "videoMastheadAdRenderer", "carouselAdRenderer", "actionCompanionAdRenderer", "imageCompanionAdRenderer",
        "instreamVideoAdRenderer", "adPlacementRenderer", "adBreakServiceRenderer",
        "promotedSparklesWebRenderer", "promotedSparklesTextSearchRenderer", "promotedSparklesRenderer", "promotedVideoRenderer",
        "compactPromotedVideoRenderer", "compactPromotedItemRenderer", "searchPyvRenderer",
        "statementBannerRenderer", "bannerPromoRenderer", "brandVideoShelfRenderer", "brandVideoSingletonRenderer",
        "mealbarPromoRenderer", "primetimePromoRenderer", "feedNudgeRenderer",
    )

    /** Keys whose presence on an item says it is paid for, whatever the renderer is called. */
    private val AD_FLAG_KEYS = setOf("adSignalsInfo", "promotedContent", "adPlacementConfig", "adSlotMetadata", "adClickTrackingParams")

    private val BADGE_WORDS = setOf("ad", "ads", "sponsored", "promoted", "promotion", "paid promotion")

    private val AD_CONTENT_TYPE_WORDS = setOf("AD", "ADS", "PROMO", "PROMOTED", "SPONSORED", "PREMIUM")

    private val OWN_HOST_SUFFIXES = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com", "ggpht.com", "googleusercontent.com", "google.com", "gstatic.com",
    )

    private val AD_URL_PARTS = listOf("/pagead/", "/aclk", "adurl=", "doubleclick.net", "googleadservices.com", "googlesyndication.com")

    private val PREMIUM_PATHS = listOf("/premium", "/youtube_premium", "/musicpremium", "/ypc", "/paid_memberships", "/tv/upsell")

    /** A channel name that only the site's own promotions carry. */
    private val PROMO_CHANNELS = setOf("youtube and youtube music")

    private val PROMO_TITLE = Regex("""^\s*(try|get|join|upgrade to|subscribe to)\b.{0,40}\b(youtube|music)?\s*premium\b""", RegexOption.IGNORE_CASE)

    private const val MAX_DEPTH = 24

    /** Whether the list key [key] is an ad or promo renderer; the item is skipped with everything inside it. */
    fun isAdRenderer(key: String): Boolean = key in AD_KEYS || (key.length > 2 && key.startsWith("ad") && key[2].isUpperCase()) ||
        key.contains("Promoted") || key.contains("Pyv")

    /** Whether the lockup's `contentType` (for example `LOCKUP_CONTENT_TYPE_AD`) names an ad. */
    fun isAdContentType(type: String?): Boolean = type != null && type.split('_').any { it in AD_CONTENT_TYPE_WORDS }

    /**
     * Whether the list item [node] is an ad, a promotion or an upsell: it holds an ad renderer, carries an ad flag, shows an "Ad" or
     * "Sponsored" badge, points at a premium offer or at an address outside YouTube, or is one of the site's own promo cards.
     */
    fun isAdItem(node: JsonObject): Boolean = scan(node, 0, inBadge = false)

    private fun scan(node: JsonElement?, depth: Int, inBadge: Boolean): Boolean {
        if (depth > MAX_DEPTH) return false
        when (node) {
            is JsonObject -> {
                if (node.isFlagged()) return true
                if (node.containsKey("contentType") && isAdContentType((node["contentType"] as? JsonPrimitive)?.contentOrNull)) return true
                for ((key, value) in node) {
                    if (isAdRenderer(key) || key in AD_FLAG_KEYS || key.startsWith("ypc") || key == "offerEndpoint") return true
                    if ((key == "webCommandMetadata" || key == "urlEndpoint") && value is JsonObject) {
                        val url = (value["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (url != null && isAdUrl(url)) return true
                    }
                    if (inBadge && value is JsonPrimitive && value.isString && key in TEXT_KEYS && value.content.trim().lowercase() in BADGE_WORDS) return true
                    if (scan(value, depth + 1, inBadge || key.contains("badge", ignoreCase = true))) return true
                }
            }
            is JsonArray -> for (child in node) if (scan(child, depth + 1, inBadge)) return true
            else -> Unit
        }
        return false
    }

    private val TEXT_KEYS = setOf("label", "text", "simpleText", "content", "accessibilityLabel", "tooltip")

    private fun JsonObject.isFlagged(): Boolean = (this["isAd"] as? JsonPrimitive)?.booleanOrNull == true ||
        (this["isSponsored"] as? JsonPrimitive)?.booleanOrNull == true

    /** Only addresses a user could follow count (`webCommandMetadata.url`, `urlEndpoint.url`), not picture addresses. */
    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (AD_URL_PARTS.any { it in lower }) return true
        val host = lower.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':')
        val path = if (host.isEmpty()) lower else "/" + lower.substringAfter("://").substringAfter('/', "")
        if (host.isNotEmpty() && OWN_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") } &&
            (lower.startsWith("http://") || lower.startsWith("https://"))
        ) {
            return true
        }
        return PREMIUM_PATHS.any { path == it || path.startsWith("$it/") || path.startsWith("$it?") }
    }

    /**
     * Whether a video already read into [title] and [channelName] is one of the site's own promotions ("Try YouTube Premium free" by
     * "YouTube and YouTube Music"), for the parsers that no longer see the JSON.
     */
    fun isPromoListing(title: String?, channelName: String?): Boolean {
        if (channelName?.trim()?.lowercase() in PROMO_CHANNELS) return true
        return title != null && PROMO_TITLE.containsMatchIn(title) && channelName?.trim()?.lowercase()?.startsWith("youtube") == true
    }
}
