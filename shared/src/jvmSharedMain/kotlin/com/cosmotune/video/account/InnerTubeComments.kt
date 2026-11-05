package com.cosmotune.video.account

import com.cosmotune.music.data.DebugLog as Log
import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.CommentPage
import com.cosmotune.video.model.CommentRating
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.CommentWriteTokens
import com.cosmotune.video.model.PageToken
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A video's comments from the site's own `next` endpoint (the WEB client, [YoutubeWeb]): the comment section's token, then one request
 * per page. Signed when a session exists, so each comment also carries what it takes to like, dislike and reply to it
 * ([CommentWriteTokens]) and the first page what it takes to post ([CommentPage.createParams]); signed out the same requests read the
 * same comments with none of that.
 *
 * The shapes follow YTXTube's `CommentFeed` and `CommentPosting` (GPL-3.0): a comment is a `commentViewModel` that names entities kept
 * apart from it under `frameworkUpdates`. The section's token gives the Top order; the header's sort menu holds the token of Newest.
 * Page tokens are the raw continuation with [TOKEN_PREFIX], so the source that wraps this can tell its tokens from NewPipe's.
 */
internal class InnerTubeComments(private val web: YoutubeWeb = InnertubeWeb) {

    /** The first page of [videoId] in order [sort]. Throws what the request throws, or [AccountFailure.Changed] for an answer it cannot read. */
    suspend fun first(videoId: String, sort: CommentSort): CommentPage {
        val watch = post { put("videoId", videoId) }
        val section = commentSection(watch) ?: throw AccountFailure.Changed("The watch page had no comment section")
        val token = firstToken(section)
            ?: return CommentPage(emptyList(), null, null, disabled = true, sortable = false)
        val top = page(token)
        if (sort == CommentSort.TOP) return top.page
        val newest = top.sortTokens.getOrNull(NEWEST_INDEX) ?: return top.page
        return page(newest).page.copy(createParams = top.page.createParams, sortable = top.page.sortable, totalCount = top.page.totalCount)
    }

    /** The comments or replies a page's or a comment's token opens. */
    suspend fun more(token: PageToken): CommentPage = page(token.value.removePrefix(TOKEN_PREFIX)).page

    private suspend fun page(token: String): Parsed = parsePage(post { put("continuation", token) })

    private suspend fun post(body: JsonObjectBuilder.() -> Unit): JsonObject {
        val response = web.post("next", body)
        YoutubeFeedParser.errorOf(response)?.let { throw it }
        return response
    }

    /** One page, and the sort menu's tokens in the site's order (Top, Newest). */
    internal class Parsed(val page: CommentPage, val sortTokens: List<String>)

    companion object {
        private const val TAG = "VideoAccount"

        /** What marks a [PageToken] as this class's. */
        const val TOKEN_PREFIX = "it:"

        private const val NEWEST_INDEX = 1

        /** The watch page's comment section: the item section the site names so. */
        private fun commentSection(watch: JsonObject): JsonObject? {
            var found: JsonObject? = null
            walk(watch) { _, node -> if (found == null && node.str("sectionIdentifier") == "comment-item-section") found = node }
            return found
        }

        /** Reads one page. An answer that held threads none of which could be read is [AccountFailure.Changed]. */
        internal fun parsePage(response: JsonObject): Parsed {
            val entities = entitiesOf(response)
            val comments = ArrayList<Comment>()
            var total: Long? = null
            var next: String? = null
            var seen = 0
            var sortTokens = emptyList<String>()
            walk(response) { key, node ->
                if (key != "reloadContinuationItemsCommand" && key != "appendContinuationItemsAction") return@walk
                for (element in node.arr("continuationItems").orEmpty()) {
                    val item = element as? JsonObject ?: continue
                    item.obj("commentsHeaderRenderer")?.let { header ->
                        YoutubeFeedParser.parseCount(YoutubeFeedParser.text(header["countText"]))?.let { total = it }
                        sortTokens = sortTokensOf(header)
                    }
                    item.obj("commentThreadRenderer")?.let { thread ->
                        seen += 1
                        val repliesToken = thread.obj("replies")?.let(::firstToken)
                        viewModelIn(thread)?.let { comment(it, entities, repliesToken) }?.let(comments::add)
                    }
                    if (item["commentViewModel"] != null) {
                        seen += 1
                        viewModelIn(item)?.let { comment(it, entities, null) }?.let(comments::add)
                    }
                    item.obj("continuationItemRenderer")?.let { renderer -> firstToken(renderer)?.let { next = it } }
                }
            }
            if (seen > 0 && comments.isEmpty()) {
                Log.w(TAG, "comments: nothing readable on a page that held $seen threads")
                throw AccountFailure.Changed("The comments could not be read")
            }
            val page = CommentPage(
                items = comments.distinctBy { it.id },
                next = next?.let { PageToken(TOKEN_PREFIX + it) },
                totalCount = total,
                disabled = false,
                sortable = sortTokens.size > NEWEST_INDEX,
                createParams = firstString(response, "createCommentParams"),
            )
            return Parsed(page, sortTokens)
        }

        /** The comment in a `comment/create_comment` or `create_comment_reply` answer, or null when its shape cannot be read. */
        internal fun parsePosted(response: JsonObject): Comment? {
            val entities = entitiesOf(response)
            var found: Comment? = null
            walk(response) { key, node ->
                if (found == null && key == "commentViewModel" && node["commentKey"] != null) found = comment(node, entities, null)
            }
            return found
        }

        /** Whether a write's answer says it was done. */
        internal fun wasPosted(response: JsonObject): Boolean {
            val text = response.toString()
            return "STATUS_FAILED" !in text && ("createCommentAction" in text || "createCommentReplyAction" in text || "STATUS_SUCCEEDED" in text)
        }

        private fun entitiesOf(response: JsonObject): Map<String, JsonObject> {
            val entities = HashMap<String, JsonObject>()
            walk(response) { _, node ->
                val key = node.str("entityKey")
                val payload = node.obj("payload")
                if (key != null && payload != null) entities[key] = payload
            }
            return entities
        }

        /** The innermost `commentViewModel` (the site nests one in another): the one that holds the keys. */
        private fun viewModelIn(node: JsonObject): JsonObject? {
            var found: JsonObject? = null
            walk(node) { key, candidate -> if (found == null && key == "commentViewModel" && candidate["commentKey"] != null) found = candidate }
            return found
        }

        private fun comment(view: JsonObject, entities: Map<String, JsonObject>, repliesToken: String?): Comment? {
            val entity = entities[view.str("commentKey")].obj("commentEntityPayload") ?: return null
            val properties = entity.obj("properties") ?: return null
            val id = properties.str("commentId") ?: view.str("commentId") ?: return null
            val author = entity.obj("author")
            val toolbar = entity.obj("toolbar")
            val state = entities[view.str("toolbarStateKey")].obj("engagementToolbarStateEntityPayload")
            val surface = entities[view.str("toolbarSurfaceKey")]
            val replyCount = YoutubeFeedParser.parseCount(toolbar.str("replyCount"))?.toInt()?.takeIf { it > 0 } ?: 0
            val published = properties.str("publishedTime")?.takeIf { it.isNotBlank() }
            val edited = published != null && EDITED.containsMatchIn(published)
            return Comment(
                id = id,
                authorName = author.str("displayName").orEmpty(),
                authorChannelId = author.str("channelId")?.takeIf { it.startsWith("UC") },
                authorAvatarUrl = author.str("avatarThumbnailUrl")?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("//")) "https:$it" else it },
                text = properties.obj("content").str("content").orEmpty().trim(),
                likeCount = YoutubeFeedParser.parseCount(toolbar.str("likeCountNotliked") ?: toolbar.str("likeCountA11y")),
                publishedText = published?.replace(EDITED, "")?.trim(),
                pinned = view["pinnedText"] != null || properties["pinnedText"] != null,
                heartedByCreator = state.str("heartState") == "TOOLBAR_HEART_STATE_HEARTED",
                byCreator = author.primitiveTrue("isCreator"),
                edited = edited,
                replyCount = replyCount,
                replies = repliesToken?.takeIf { replyCount > 0 }?.let { PageToken(TOKEN_PREFIX + it) },
                rating = when (state.str("likeState")) {
                    "TOOLBAR_LIKE_STATE_LIKED" -> CommentRating.LIKED
                    "TOOLBAR_LIKE_STATE_DISLIKED" -> CommentRating.DISLIKED
                    else -> CommentRating.NONE
                },
                writes = surface?.let(::writesOf),
            )
        }

        private val EDITED = Regex("""\s*\((?:edited|modifié|bearbeitet|editado)\)""", RegexOption.IGNORE_CASE)

        private fun writesOf(payload: JsonObject): CommentWriteTokens? {
            val surface = payload.obj("engagementToolbarSurfaceEntityPayload") ?: payload
            val tokens = CommentWriteTokens(
                like = surface.obj("likeCommand")?.let { firstString(it, "action") },
                unlike = surface.obj("unlikeCommand")?.let { firstString(it, "action") },
                dislike = surface.obj("dislikeCommand")?.let { firstString(it, "action") },
                undislike = surface.obj("undislikeCommand")?.let { firstString(it, "action") },
                reply = firstString(surface, "createReplyParams"),
            )
            return tokens.takeUnless { it.like == null && it.dislike == null && it.reply == null }
        }

        private fun sortTokensOf(header: JsonObject): List<String> {
            val menu = header.obj("sortMenu").obj("sortFilterSubMenuRenderer").arr("subMenuItems").orEmpty()
            return menu.mapNotNull { firstToken(it) }
        }

        /** The first continuation token under [node]. */
        private fun firstToken(node: JsonElement?): String? = firstString(node, "token")

        private fun firstString(node: JsonElement?, name: String): String? = when (node) {
            is JsonObject -> node.str(name) ?: node.values.firstNotNullOfOrNull { firstString(it, name) }
            is JsonArray -> node.firstNotNullOfOrNull { firstString(it, name) }
            else -> null
        }

        private fun JsonObject?.primitiveTrue(name: String): Boolean = (this?.get(name) as? JsonPrimitive)?.contentOrNull == "true"

        /** Every object under [node], with the key it is held under ("" for the root and array items). */
        private fun walk(node: JsonElement?, key: String = "", visit: (String, JsonObject) -> Unit) {
            when (node) {
                is JsonObject -> {
                    visit(key, node)
                    node.forEach { (name, value) -> walk(value, name, visit) }
                }
                is JsonArray -> node.forEach { walk(it, "", visit) }
                else -> Unit
            }
        }
    }
}
