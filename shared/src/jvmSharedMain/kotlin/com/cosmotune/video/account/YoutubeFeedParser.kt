package com.cosmotune.video.account

import com.cosmotune.music.data.DebugLog as Log
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the lists of the YouTube site's youtubei answers (home, subscriptions, library, playlists, history, channels) into the
 * app's own models.
 *
 * Shapes handled, newest first: `lockupViewModel` (video, Short and playlist lockups, the site's current list item),
 * `shortsLockupViewModel`, `videoRenderer`, `gridVideoRenderer`, `compactVideoRenderer`, `playlistVideoRenderer`,
 * `reelItemRenderer`, `playlistRenderer`, `gridPlaylistRenderer`, `channelRenderer`, `gridChannelRenderer`, wrapped in whatever
 * containers the answer uses (`richGridRenderer` / `richItemRenderer`, `sectionListRenderer`, `itemSectionRenderer`,
 * `shelfRenderer`, `gridRenderer`, continuation actions...). The walk does not depend on the wrappers: it looks for the items.
 *
 * Verification status: the anonymous shapes (search, channel tabs, public playlists, watch-page related) were checked against
 * real answers saved as test fixtures. The signed-in answers (what-to-watch, subscriptions, library, history, channels) use the same item
 * renderers but could not be fetched without an account, so those lists are NOT live-verified; they are parsed from the shapes
 * the site documents through open-source clients and from the anonymous answers.
 *
 * Nothing here throws on an unknown shape: an item that cannot be read is counted in [ParsedFeed.unreadable] and logged by shape
 * name only (never by content), and the caller turns "no item readable but something was there" into [AccountFailure.Changed].
 */
internal object YoutubeFeedParser {
    private const val TAG = "VideoAccount"

    /** What one answer holds. [videoIdsSeen] counts distinct video ids found outside every item this parser understood. */
    data class ParsedFeed(
        val videos: List<VideoItem>,
        val playlists: List<PlaylistItem>,
        val channels: List<ChannelItem>,
        val continuation: String?,
        val unreadable: Int,
        val videoIdsSeen: Int,
        val loggedOut: Boolean,
    ) {
        /** Whether anything at all was read. */
        val isEmpty: Boolean get() = videos.isEmpty() && playlists.isEmpty() && channels.isEmpty()

        /** The answer clearly held list items (or video ids) that nothing here could read: the site changed. */
        val looksChanged: Boolean get() = isEmpty && (unreadable > 0 || videoIdsSeen > 0)
    }

    /** Parses the list items and the continuation token of a browse, search or continuation answer. */
    fun parse(response: JsonObject): ParsedFeed {
        val walk = Walk()
        // The header, top bar, sidebar and framework updates hold no list items; walking only the content roots keeps the
        // account button's and the guide's own entries out of the lists.
        for (root in ROOTS) response[root]?.let { walk.visit(it, 0) }
        val ids = walk.videos.mapTo(HashSet()) { it.id }
        val seen = walk.looseVideoIds.count { it !in ids }
        if (walk.unreadable > 0 || (walk.videos.isEmpty() && seen > 0)) {
            Log.w(TAG, "feed: read ${walk.videos.size} videos, ${walk.playlists.size} playlists, ${walk.channels.size} channels; " +
                "${walk.unreadable} unreadable [${walk.unreadableShapes.joinToString()}], $seen loose video ids")
        }
        return ParsedFeed(
            videos = walk.videos.distinctBy { it.id },
            playlists = walk.playlists.distinctBy { it.id },
            channels = walk.channels.distinctBy { it.id },
            continuation = walk.continuation(),
            unreadable = walk.unreadable,
            videoIdsSeen = seen,
            loggedOut = isLoggedOut(response),
        )
    }

    /** The subscribed channels the guide lists (the signed-in answer of `guide`): a fallback for the channels page. */
    fun parseGuideChannels(response: JsonObject): List<ChannelItem> {
        val out = ArrayList<ChannelItem>()
        fun entries(node: JsonElement?, inSubscriptions: Boolean) {
            when (node) {
                is JsonObject -> for ((key, value) in node) {
                    if (key == "guideEntryRenderer" && inSubscriptions && value is JsonObject) guideChannel(value)?.let(out::add)
                    else entries(value, inSubscriptions || key == "guideSubscriptionsSectionRenderer")
                }
                is JsonArray -> node.forEach { entries(it, inSubscriptions) }
                else -> Unit
            }
        }
        entries(response["items"], false)
        return out.distinctBy { it.id }
    }

    private fun guideChannel(entry: JsonObject): ChannelItem? {
        val id = entry.obj("navigationEndpoint").obj("browseEndpoint").str("browseId")?.takeIf { it.startsWith("UC") } ?: return null
        val name = text(entry["formattedTitle"]) ?: return null
        return ChannelItem(id, name, bestUrl(entry.obj("thumbnail").arr("thumbnails")), null, null, null)
    }

    /**
     * Whether the answer says the request was anonymous (`logged_in` 0 in the service tracking params): a signed-in list asked
     * with a dead session is answered like this, with a sign-in prompt in place of the list.
     */
    fun isLoggedOut(response: JsonObject): Boolean {
        val services = response.obj("responseContext").arr("serviceTrackingParams") ?: return false
        for (service in services) {
            val params = (service as? JsonObject)?.arr("params") ?: continue
            for (param in params) {
                val p = param as? JsonObject ?: continue
                if (p.str("key") == "logged_in") return p.str("value") == "0"
            }
        }
        return false
    }

    /** A `{"error": {...}}` body's failure (the site answers some refused calls with status 200), or null when there is none. */
    fun errorOf(response: JsonObject): AccountFailure? {
        val error = response.obj("error") ?: return null
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val status = error.str("status").orEmpty()
        val message = error.str("message").orEmpty().take(120)
        return when {
            code == 401 || status == "UNAUTHENTICATED" -> AccountFailure.SessionExpired(message.ifBlank { "The sign-in expired" })
            code == 429 || status == "RESOURCE_EXHAUSTED" -> AccountFailure.RateLimited()
            else -> AccountFailure.Changed("Refused: ${message.ifBlank { status.ifBlank { code?.toString() ?: "unknown" } }}")
        }
    }

    // ---- The walk -----------------------------------------------------------------------------------------------------

    private val ROOTS = listOf("contents", "onResponseReceivedActions", "onResponseReceivedEndpoints", "continuationContents", "items")

    private class Walk {
        val videos = ArrayList<VideoItem>()
        val playlists = ArrayList<PlaylistItem>()
        val channels = ArrayList<ChannelItem>()
        val looseVideoIds = LinkedHashSet<String>()
        val unreadableShapes = LinkedHashSet<String>()
        var unreadable = 0
        private var tokenDepth = Int.MAX_VALUE
        private var token: String? = null

        fun continuation(): String? = token

        private fun offerToken(value: String?, depth: Int) {
            if (value.isNullOrBlank() || depth > tokenDepth) return
            // Shallowest wins, and among equals the last: a list's own "more" item closes the list, nested shelves are deeper.
            tokenDepth = depth
            token = value
        }

        fun visit(node: JsonElement?, depth: Int) {
            when (node) {
                is JsonArray -> for (child in node) visit(child, depth + 1)
                is JsonObject -> visitObject(node, depth)
                else -> Unit
            }
        }

        private fun visitObject(node: JsonObject, depth: Int) {
            for ((key, value) in node) {
                if (value !is JsonObject) {
                    if (key == "continuations") continuationData(value, depth)
                    if (key == "videoId" && value is JsonPrimitive) value.contentOrNull?.let(looseVideoIds::add)
                    if (value is JsonArray) visit(value, depth)
                    continue
                }
                when (key) {
                    "lockupViewModel" -> lockup(value)
                    "shortsLockupViewModel" -> item(key) { shortsLockup(value)?.let(videos::add) }
                    "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer", "reelItemRenderer" ->
                        item(key) { videoRenderer(value, key)?.let(videos::add) }
                    "playlistRenderer", "gridPlaylistRenderer", "compactPlaylistRenderer" -> item(key) { playlistRenderer(value)?.let(playlists::add) }
                    "channelRenderer", "gridChannelRenderer", "compactChannelRenderer" -> item(key) { channelRenderer(value)?.let(channels::add) }
                    "continuationItemRenderer" -> offerToken(
                        value.obj("continuationEndpoint").obj("continuationCommand").str("token")
                            ?: value.obj("button").obj("buttonRenderer").obj("command").obj("continuationCommand").str("token"),
                        depth,
                    )
                    "continuationItemViewModel" -> offerToken(
                        value.obj("continuationCommand").obj("innertubeCommand").obj("continuationCommand").str("token")
                            ?: value.obj("continuationCommand").obj("continuationCommand").str("token"),
                        depth,
                    )
                    // Ads, banners and prompts sit in the same lists and are not content.
                    "adSlotRenderer", "feedNudgeRenderer", "mealbarPromoRenderer", "statementBannerRenderer", "bannerPromoRenderer" -> Unit
                    else -> visit(value, depth + 1)
                }
            }
        }

        private fun continuationData(value: JsonElement, depth: Int) {
            for (entry in (value as? JsonArray).orEmpty()) {
                val data = entry as? JsonObject ?: continue
                offerToken(
                    data.obj("nextContinuationData").str("continuation") ?: data.obj("reloadContinuationData").str("continuation"),
                    depth,
                )
            }
        }

        private inline fun item(shape: String, read: () -> Unit) {
            try {
                val before = videos.size + playlists.size + channels.size
                read()
                if (videos.size + playlists.size + channels.size == before) unreadable(shape)
            } catch (e: Exception) {
                unreadable("$shape!")
            }
        }

        private fun unreadable(shape: String) {
            unreadable += 1
            unreadableShapes.add(shape)
        }

        private fun lockup(node: JsonObject) {
            val type = node.str("contentType").orEmpty()
            val id = node.str("contentId")
            when {
                type.contains("SHORT") || type.endsWith("_VIDEO") -> item("lockup:$type") { videoLockup(node, id, type.contains("SHORT"))?.let(videos::add) }
                type.endsWith("_PLAYLIST") || type.endsWith("_MIX") -> item("lockup:$type") { playlistLockup(node, id)?.let(playlists::add) }
                type.endsWith("_CHANNEL") -> item("lockup:$type") { channelLockup(node, id)?.let(channels::add) }
                // Podcasts, albums, courses, products: nothing Video mode lists.
                else -> Unit
            }
        }
    }

    // ---- Item readers -------------------------------------------------------------------------------------------------

    private class Part(val text: String, val browseId: String?)

    private fun rows(meta: JsonObject?): List<List<Part>> {
        val rows = meta.obj("metadata").obj("contentMetadataViewModel").arr("metadataRows") ?: return emptyList()
        return rows.mapNotNull { row ->
            val parts = (row as? JsonObject)?.arr("metadataParts") ?: return@mapNotNull null
            parts.mapNotNull { part ->
                val textObj = (part as? JsonObject).obj("text") ?: return@mapNotNull null
                val content = textObj.str("content")?.trim() ?: return@mapNotNull null
                val browse = textObj.arr("commandRuns")?.firstNotNullOfOrNull {
                    (it as? JsonObject).obj("onTap").obj("innertubeCommand").obj("browseEndpoint").str("browseId")
                }
                Part(content, browse)
            }.takeIf { it.isNotEmpty() }
        }
    }

    private fun badges(lockup: JsonObject): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun collect(node: JsonElement?) {
            when (node) {
                is JsonObject -> for ((key, value) in node) {
                    if (key == "thumbnailBadgeViewModel" && value is JsonObject) out.add(value) else collect(value)
                }
                is JsonArray -> node.forEach(::collect)
                else -> Unit
            }
        }
        collect(lockup["contentImage"])
        return out
    }

    private fun videoLockup(node: JsonObject, id: String?, isShort: Boolean): VideoItem? {
        id ?: return null
        val meta = node.obj("metadata").obj("lockupMetadataViewModel") ?: return null
        val title = meta.obj("title").str("content") ?: return null
        val thumb = bestUrl(firstSources(node["contentImage"])) ?: thumbnailFallback(id)
        val badgeList = badges(node)
        val duration = badgeList.firstNotNullOfOrNull { parseDuration(it.str("text")) }
        val live = badgeList.any { it.str("badgeStyle").orEmpty().contains("LIVE") }
        val upcoming = badgeList.any { it.str("badgeStyle").orEmpty().contains("UPCOMING") }
        val avatarNode = meta.obj("image").obj("decoratedAvatarViewModel")
        val avatar = bestUrl(avatarNode.obj("avatar").obj("avatarViewModel").obj("image").arr("sources"))
        val avatarChannel = avatarNode.obj("rendererContext").obj("commandContext").obj("onTap").obj("innertubeCommand")
            .obj("browseEndpoint").str("browseId")
        val all = rows(meta)
        // A list that names the channel puts it on its own first row; a channel's own tab has no such row.
        val channelRow = all.firstOrNull()?.takeIf { avatarNode != null || it.firstOrNull()?.browseId != null }
        val stats = if (channelRow != null) all.getOrNull(1) else all.firstOrNull()
        // Views then age; a row with one part (a live count, or a hidden count) cannot say which it is, and is shown as text.
        val twoParts = (stats?.size ?: 0) >= 2
        val views = if (twoParts || live) stats?.getOrNull(0)?.text else null
        val age = if (live) null else if (twoParts) stats?.getOrNull(1)?.text else stats?.getOrNull(0)?.text
        return VideoItem(
            id = id,
            title = title,
            channelId = channelRow?.firstOrNull()?.browseId ?: avatarChannel,
            channelName = channelRow?.firstOrNull()?.text,
            channelAvatarUrl = avatar,
            thumbnailUrl = thumb,
            durationSec = duration,
            viewCount = parseCount(views),
            uploadedAtMs = null,
            uploadedText = age,
            isLive = live,
            isUpcoming = upcoming,
            isShort = isShort,
        )
    }

    private fun shortsLockup(node: JsonObject): VideoItem? {
        val id = node.str("entityId")?.removePrefix("shorts-shelf-item-")?.takeIf { it.length == 11 }
            ?: node.obj("onTap").obj("innertubeCommand").obj("reelWatchEndpoint").str("videoId")
            ?: return null
        val overlay = node.obj("overlayMetadata")
        val title = overlay.obj("primaryText").str("content") ?: node.str("accessibilityText")?.substringBefore(" - play Short") ?: return null
        val thumb = bestUrl(firstSources(node["thumbnailViewModel"] ?: node["thumbnail"])) ?: thumbnailFallback(id)
        return VideoItem(
            id = id, title = title, channelId = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = thumb,
            durationSec = null, viewCount = parseCount(overlay.obj("secondaryText").str("content")), uploadedAtMs = null,
            uploadedText = null, isShort = true,
        )
    }

    private fun playlistLockup(node: JsonObject, rawId: String?): PlaylistItem? {
        val id = rawId?.removePrefix("VL") ?: return null
        val meta = node.obj("metadata").obj("lockupMetadataViewModel") ?: return null
        val title = meta.obj("title").str("content") ?: return null
        val count = badges(node).firstNotNullOfOrNull { leadingNumber(it.str("text")) }
        val owner = rows(meta).firstOrNull()?.firstOrNull()?.takeIf { it.browseId != null }
        return PlaylistItem(
            id = id,
            title = title,
            thumbnailUrl = bestUrl(firstSources(node["contentImage"])),
            ownerName = owner?.text,
            ownerId = owner?.browseId,
            videoCount = count,
        )
    }

    private fun channelLockup(node: JsonObject, id: String?): ChannelItem? {
        id ?: return null
        val meta = node.obj("metadata").obj("lockupMetadataViewModel") ?: return null
        val title = meta.obj("title").str("content") ?: return null
        val parts = rows(meta).flatten().map { it.text }
        val handle = parts.firstOrNull { it.startsWith("@") }
        val subs = parts.firstOrNull { !it.startsWith("@") && it.any(Char::isDigit) }
        return ChannelItem(id, title, bestUrl(firstSources(node["contentImage"])), parseCount(subs), null, null, handle = handle)
    }

    private fun videoRenderer(node: JsonObject, kind: String): VideoItem? {
        val id = node.str("videoId") ?: return null
        val title = text(node["title"]) ?: text(node["headline"]) ?: return null
        val owner = node.obj("ownerText") ?: node.obj("longBylineText") ?: node.obj("shortBylineText")
        val ownerRun = owner.arr("runs")?.firstOrNull() as? JsonObject
        val overlays = node.arr("thumbnailOverlays").orEmpty().mapNotNull { (it as? JsonObject)?.obj("thumbnailOverlayTimeStatusRenderer") }
        val overlayStyle = overlays.firstOrNull()?.str("style").orEmpty()
        val badgeStyles = node.arr("badges").orEmpty().mapNotNull { (it as? JsonObject).obj("metadataBadgeRenderer")?.str("style") }
        val live = overlayStyle == "LIVE" || badgeStyles.any { it.contains("LIVE_NOW") }
        val upcoming = overlayStyle == "UPCOMING" || node.obj("upcomingEventData") != null
        val short = kind == "reelItemRenderer" || overlayStyle == "SHORTS" ||
            node.obj("navigationEndpoint").obj("reelWatchEndpoint") != null
        val duration = parseDuration(text(node["lengthText"])) ?: overlays.firstNotNullOfOrNull { parseDuration(text(it["text"])) }
        val views = text(node["viewCountText"]) ?: text(node["shortViewCountText"])
        val avatar = node.obj("channelThumbnailSupportedRenderers").obj("channelThumbnailWithLinkRenderer")
            .obj("thumbnail").arr("thumbnails")
        return VideoItem(
            id = id,
            title = title,
            channelId = ownerRun.obj("navigationEndpoint").obj("browseEndpoint").str("browseId"),
            channelName = ownerRun.str("text"),
            channelAvatarUrl = bestUrl(avatar),
            thumbnailUrl = bestUrl(node.obj("thumbnail").arr("thumbnails")) ?: thumbnailFallback(id),
            durationSec = if (live) null else duration,
            viewCount = parseCount(views),
            uploadedAtMs = null,
            uploadedText = text(node["publishedTimeText"]),
            isLive = live,
            isUpcoming = upcoming,
            isShort = short,
        )
    }

    private fun playlistRenderer(node: JsonObject): PlaylistItem? {
        val id = node.str("playlistId")?.removePrefix("VL") ?: return null
        val title = text(node["title"]) ?: return null
        val thumbs = node.arr("thumbnails")?.firstOrNull().let { (it as? JsonObject).arr("thumbnails") }
            ?: node.obj("thumbnailRenderer").obj("playlistVideoThumbnailRenderer").obj("thumbnail").arr("thumbnails")
        val owner = node.obj("longBylineText") ?: node.obj("shortBylineText")
        val run = owner.arr("runs")?.firstOrNull() as? JsonObject
        val count = node.str("videoCount") ?: text(node["videoCountText"]) ?: text(node["videoCountShortText"])
        return PlaylistItem(
            id = id,
            title = title,
            thumbnailUrl = bestUrl(thumbs),
            ownerName = run.str("text"),
            ownerId = run.obj("navigationEndpoint").obj("browseEndpoint").str("browseId"),
            videoCount = leadingNumber(count),
        )
    }

    private fun channelRenderer(node: JsonObject): ChannelItem? {
        val id = node.str("channelId") ?: return null
        val name = text(node["title"]) ?: return null
        // The site moved the handle into `subscriberCountText` and the subscriber count into `videoCountText`: tell them apart by shape.
        val first = text(node["subscriberCountText"])
        val second = text(node["videoCountText"])
        val handle = listOf(first, second).firstOrNull { it?.startsWith("@") == true }
        val subs = listOf(first, second).firstOrNull { it != null && !it.startsWith("@") && it.any(Char::isDigit) }
        val videos = if (handle == null) leadingNumber(second) else null
        val badge = node.arr("ownerBadges").orEmpty().any { it.toString().contains("VERIFIED") }
        return ChannelItem(
            id = id,
            name = name,
            avatarUrl = bestUrl(node.obj("thumbnail").arr("thumbnails")),
            subscriberCount = parseCount(subs),
            videoCount = videos,
            description = text(node["descriptionSnippet"]),
            verified = badge,
            handle = handle,
        )
    }

    // ---- Small readers ------------------------------------------------------------------------------------------------

    /** Text of a `{simpleText}`, `{runs:[{text}]}` or `{content}` object, or a plain string; null when blank. */
    fun text(node: JsonElement?): String? = when (node) {
        is JsonPrimitive -> node.contentOrNull
        is JsonObject -> node.str("simpleText") ?: node.str("content")
            ?: node.arr("runs")?.joinToString("") { (it as? JsonObject).str("text").orEmpty() }
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** The largest picture of a `thumbnails` / `sources` list, protocol-relative addresses made absolute. */
    fun bestUrl(list: JsonArray?): String? {
        val best = list?.mapNotNull { it as? JsonObject }
            ?.filter { it.str("url") != null }
            ?.maxByOrNull { (it["width"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 }
            ?: return null
        val url = best.str("url") ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    /** The first `sources` (or `thumbnails`) list found under [node], whatever the wrappers between are called. */
    private fun firstSources(node: JsonElement?): JsonArray? = when (node) {
        is JsonObject -> (node["sources"] as? JsonArray)?.takeIf { s -> s.any { (it as? JsonObject)?.get("url") != null } }
            ?: (node["thumbnails"] as? JsonArray)
            ?: node.values.firstNotNullOfOrNull { firstSources(it) }
        is JsonArray -> node.firstNotNullOfOrNull { firstSources(it) }
        else -> null
    }

    private fun thumbnailFallback(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    private val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

    /** "6:10:58" or "43:17" as seconds; null for anything else ("LIVE", "Shorts", "39 videos"). */
    fun parseDuration(text: String?): Long? {
        val t = text?.trim() ?: return null
        if (!DURATION.matches(t)) return null
        return t.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() }
    }

    private val SCALED = Regex("""(\d+(?:[.,]\d+)?)\s*([KkMmBb])(?![A-Za-z])""")

    /** "58,393,726 views" as 58393726, "1.2M views" as 1200000; null when no number leads the text (other languages may not read). */
    fun parseCount(text: String?): Long? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        SCALED.find(t)?.let { m ->
            val number = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val scale = when (m.groupValues[2].lowercase()) {
                "k" -> 1_000.0
                "m" -> 1_000_000.0
                else -> 1_000_000_000.0
            }
            return Math.round(number * scale)
        }
        return leadingNumber(t)
    }

    /** The integer a text starts with ("1,234 videos" is 1234), or null. */
    fun leadingNumber(text: String?): Long? {
        val digits = text?.trim()?.takeWhile { it.isDigit() || it == ',' || it == '.' || it == ' ' || it == ' ' }
            ?.filter { it.isDigit() }
        return digits?.takeIf { it.isNotEmpty() && it.length < 18 }?.toLong()
    }
}

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

internal fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

internal fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
