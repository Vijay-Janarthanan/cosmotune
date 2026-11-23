package com.cosmotune.video.account

import com.cosmotune.video.chat.ChatBatch
import com.cosmotune.video.chat.ChatMessage
import com.cosmotune.video.chat.ChatPaid
import com.cosmotune.video.chat.ChatRole
import com.cosmotune.video.chat.ChatStart
import com.cosmotune.video.chat.LiveChatSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A stream's chat from the site's own endpoints (the WEB client, signed out unless Music holds a session): the watch page's
 * `next` answer names the chat's first continuation (`conversationBar.liveChatRenderer`), `live_chat/get_live_chat` reads a live
 * chat and `live_chat/get_live_chat_replay` the replay of a finished stream at a point of the video. Read only.
 */
class InnerTubeLiveChat internal constructor(private val web: YoutubeWeb) : LiveChatSource {
    constructor() : this(InnertubeWeb)

    override suspend fun start(videoId: String): ChatStart? = startOf(web.post("next") { put("videoId", videoId) })

    override suspend fun live(token: String): ChatBatch = parse(web.post("live_chat/get_live_chat") { put("continuation", token) })

    override suspend fun replay(token: String, offsetMs: Long): ChatBatch = parse(
        web.post("live_chat/get_live_chat_replay") {
            put("continuation", token)
            putJsonObject("currentPlayerState") { put("playerOffsetMs", offsetMs.coerceAtLeast(0).toString()) }
        },
    )

    companion object {
        /** The chat's first continuation on a watch page, or null when it has no chat. */
        internal fun startOf(watch: JsonObject): ChatStart? {
            val chat = watch.obj("contents").obj("twoColumnWatchNextResults").obj("conversationBar").obj("liveChatRenderer") ?: return null
            val token = chat.arr("continuations")?.firstNotNullOfOrNull { it.obj("reloadContinuationData").str("continuation") }
                ?: return null
            val replay = (chat["isReplay"] as? JsonPrimitive)?.contentOrNull == "true"
            return ChatStart(token, replay)
        }

        /** One answer of either chat endpoint. */
        internal fun parse(response: JsonObject): ChatBatch {
            val chat = response.obj("continuationContents").obj("liveChatContinuation") ?: return ChatBatch(emptyList(), null)
            val messages = ArrayList<ChatMessage>()
            var pinned: ChatMessage? = null
            var unpinned = false
            val deleted = HashSet<String>()
            fun action(node: JsonElement?, offsetMs: Long?) {
                node.obj("addChatItemAction")?.obj("item")?.let { item -> message(item, offsetMs)?.let { messages += it } }
                node.obj("addBannerToLiveChatCommand")?.let { banner ->
                    val contents = banner.obj("bannerRenderer").obj("liveChatBannerRenderer").obj("contents")
                    message(contents, offsetMs)?.let { pinned = it }
                }
                if (node.obj("removeBannerForLiveChatCommand") != null) unpinned = true
                node.obj("markChatItemAsDeletedAction")?.str("targetItemId")?.let { deleted += it }
                node.obj("removeChatItemAction")?.str("targetItemId")?.let { deleted += it }
                node.obj("replayChatItemAction")?.let { replay ->
                    val at = replay.str("videoOffsetTimeMsec")?.toLongOrNull()
                    replay.arr("actions")?.forEach { action(it, at) }
                }
            }
            chat.arr("actions")?.forEach { action(it, null) }
            var next: String? = null
            var delay: Long? = null
            chat.arr("continuations")?.firstOrNull()?.let { c ->
                val data = (c as? JsonObject)?.values?.firstOrNull() as? JsonObject
                next = data.str("continuation")
                delay = (data?.get("timeoutMs") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            }
            return ChatBatch(messages, next, delay, pinned, unpinned && pinned == null, deleted)
        }

        private fun message(item: JsonObject?, offsetMs: Long?): ChatMessage? {
            item ?: return null
            item.obj("liveChatTextMessageRenderer")?.let { return text(it, offsetMs, null) }
            item.obj("liveChatPaidMessageRenderer")?.let { r ->
                val paid = ChatPaid(YoutubeFeedParser.text(r["purchaseAmountText"]).orEmpty(), colour(r["bodyBackgroundColor"]))
                return text(r, offsetMs, paid)
            }
            item.obj("liveChatPaidStickerRenderer")?.let { r ->
                val paid = ChatPaid(YoutubeFeedParser.text(r["purchaseAmountText"]).orEmpty(), colour(r["backgroundColor"]))
                return text(r, offsetMs, paid)
            }
            item.obj("liveChatMembershipItemRenderer")?.let { r ->
                val header = YoutubeFeedParser.text(r["headerSubtext"]) ?: YoutubeFeedParser.text(r["headerPrimaryText"])
                return text(r, offsetMs, null, prefix = header)?.copy(role = ChatRole.MEMBER)
            }
            return null
        }

        private fun text(r: JsonObject, offsetMs: Long?, paid: ChatPaid?, prefix: String? = null): ChatMessage? {
            val id = r.str("id") ?: return null
            val body = runs(r.obj("message")?.arr("runs"))
            val text = listOfNotNull(prefix, body.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (text.isBlank() && paid == null) return null
            return ChatMessage(
                id = id,
                author = YoutubeFeedParser.text(r["authorName"]).orEmpty(),
                authorPhotoUrl = YoutubeFeedParser.bestUrl(r.obj("authorPhoto")?.arr("thumbnails")),
                text = text,
                role = role(r.arr("authorBadges")),
                paid = paid,
                offsetMs = offsetMs,
            )
        }

        private fun runs(runs: JsonArray?): String = runs.orEmpty().joinToString("") { run ->
            run.str("text") ?: run.obj("emoji")?.let { emoji ->
                emoji.str("emojiId")?.takeIf { id -> id.none { it.isLetterOrDigit() || it == '/' } }
                    ?: (emoji["shortcuts"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            }.orEmpty()
        }

        private fun role(badges: JsonArray?): ChatRole {
            var role = ChatRole.NONE
            badges.orEmpty().forEach { b ->
                val badge = b.obj("liveChatAuthorBadgeRenderer") ?: return@forEach
                val icon = badge.obj("icon")?.str("iconType")
                val found = when {
                    icon == "OWNER" -> ChatRole.OWNER
                    icon == "MODERATOR" -> ChatRole.MODERATOR
                    icon == "VERIFIED" -> ChatRole.VERIFIED
                    badge.obj("customThumbnail") != null -> ChatRole.MEMBER
                    else -> null
                }
                if (found != null && found.ordinal < role.ordinal) role = found
            }
            return role
        }

        private fun colour(node: JsonElement?): Long = (node as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0xFF1E88E5
    }
}
