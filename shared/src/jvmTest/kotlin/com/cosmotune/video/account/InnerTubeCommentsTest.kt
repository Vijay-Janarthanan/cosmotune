package com.cosmotune.video.account

import com.cosmotune.video.model.CommentRating
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.CommentWriteTokens
import com.cosmotune.video.model.PageToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The comment pages of the `next` endpoint, their continuations and orders, and the payloads of the writes; over a fake [YoutubeWeb]. */
@OptIn(ExperimentalCoroutinesApi::class)
class InnerTubeCommentsTest {
    private class Web(vararg answers: JsonObject) : YoutubeWeb {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        private val queue = ArrayDeque(answers.toList())

        override suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
            calls += endpoint to buildJsonObject(body)
            return queue.removeFirstOrNull() ?: buildJsonObject {}
        }
    }

    private val watch = AccountFixtures.json("comments_watch.json")
    private val page = AccountFixtures.json("comments_page.json")
    private val replies = AccountFixtures.json("comments_replies.json")

    // ---- Reading ------------------------------------------------------------------------------------------------------

    @Test fun theFirstPageIsAskedWithTheWatchPagesSectionToken() = runTest {
        val web = Web(watch, page)
        val first = InnerTubeComments(web).first("vid", CommentSort.TOP)
        assertEquals("vid", web.calls[0].second["videoId"]!!.jsonPrimitive.content)
        assertEquals("SECTION_TOKEN", web.calls[1].second["continuation"]!!.jsonPrimitive.content)
        assertEquals(listOf("c1", "c2"), first.items.map { it.id })
        assertEquals(1234L, first.totalCount)
        assertEquals("CREATE_PARAMS", first.createParams)
        assertTrue(first.sortable, "the sort menu has both orders")
        assertFalse(first.disabled)
    }

    @Test fun aCommentIsReadFromItsEntities() {
        val parsed = InnerTubeComments.parsePage(page).page
        val pinned = parsed.items[0]
        assertEquals("Asha", pinned.authorName)
        assertEquals("UCasha", pinned.authorChannelId)
        assertEquals("https://yt3.ggpht.com/asha", pinned.authorAvatarUrl, "a protocol-relative address is made whole")
        assertEquals("First!", pinned.text)
        assertEquals(1200L, pinned.likeCount)
        assertEquals("2 days ago", pinned.publishedText)
        assertTrue(pinned.edited, "(edited) is a mark, not part of the age")
        assertTrue(pinned.pinned)
        assertTrue(pinned.heartedByCreator)
        assertTrue(pinned.byCreator)
        assertEquals(CommentRating.LIKED, pinned.rating)
        assertEquals(3, pinned.replyCount)
        assertEquals(InnerTubeComments.TOKEN_PREFIX + "REPLIES_TOKEN", pinned.replies?.value)
        assertEquals(CommentWriteTokens("LIKE_c1", "UNLIKE_c1", "DISLIKE_c1", "UNDISLIKE_c1", "REPLY_c1"), pinned.writes)
    }

    @Test fun aSignedOutCommentOffersNoWritesAndNoReplyTokenWithoutReplies() {
        val plain = InnerTubeComments.parsePage(page).page.items[1]
        assertNull(plain.writes)
        assertEquals(0, plain.replyCount)
        assertNull(plain.replies)
        assertEquals(CommentRating.NONE, plain.rating)
        assertFalse(plain.pinned || plain.heartedByCreator || plain.byCreator || plain.edited)
    }

    @Test fun theNextPageTokenIsTheContinuationWithTheMark() = runTest {
        val parsed = InnerTubeComments.parsePage(page).page
        assertEquals(InnerTubeComments.TOKEN_PREFIX + "NEXT_PAGE", parsed.next?.value)
        val web = Web(replies)
        InnerTubeComments(web).more(parsed.next!!)
        assertEquals("NEXT_PAGE", web.calls.single().second["continuation"]!!.jsonPrimitive.content, "the mark is not sent")
    }

    @Test fun repliesAreBareViewModelsAndPageOn() = runTest {
        val web = Web(replies)
        val parsed = InnerTubeComments(web).more(PageToken(InnerTubeComments.TOKEN_PREFIX + "REPLIES_TOKEN"))
        assertEquals("REPLIES_TOKEN", web.calls.single().second["continuation"]!!.jsonPrimitive.content)
        assertEquals(listOf("r1"), parsed.items.map { it.id })
        assertEquals("Thanks", parsed.items.single().text)
        assertEquals(InnerTubeComments.TOKEN_PREFIX + "MORE_REPLIES", parsed.next?.value)
    }

    @Test fun newestIsAskedWithTheSortMenusSecondToken() = runTest {
        val web = Web(watch, page, replies)
        val newest = InnerTubeComments(web).first("vid", CommentSort.NEWEST)
        assertEquals("NEWEST_TOKEN", web.calls[2].second["continuation"]!!.jsonPrimitive.content)
        assertEquals(listOf("r1"), newest.items.map { it.id })
        assertEquals(1234L, newest.totalCount, "the count and what it takes to post stay the first page's")
        assertEquals("CREATE_PARAMS", newest.createParams)
        assertTrue(newest.sortable)
    }

    @Test fun aSectionWithNoTokenIsCommentsTurnedOff() = runTest {
        val off = Json.parseToJsonElement("""{"contents":{"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[{"messageRenderer":{"text":{"simpleText":"Comments are turned off."}}}]}}}""").jsonObject
        val web = Web(off)
        val result = InnerTubeComments(web).first("vid", CommentSort.TOP)
        assertTrue(result.disabled)
        assertTrue(result.items.isEmpty())
        assertEquals(1, web.calls.size)
    }

    @Test fun aWatchPageWithNoSectionIsAFailureSoTheFallbackAnswers() = runTest {
        assertIs<AccountFailure.Changed>(assertFailsWith<AccountFailure> { InnerTubeComments(Web(buildJsonObject {})).first("vid", CommentSort.TOP) })
    }

    @Test fun threadsThatCannotBeReadAreAFailureNotAnEmptyList() {
        val changed = Json.parseToJsonElement("""{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[{"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"old"}}}}]}}]}""").jsonObject
        assertFailsWith<AccountFailure.Changed> { InnerTubeComments.parsePage(changed) }
    }

    // ---- The answer to a post -----------------------------------------------------------------------------------------

    @Test fun thePostedCommentIsReadFromTheAnswer() {
        val answer = AccountFixtures.json("comment_posted.json")
        assertTrue(InnerTubeComments.wasPosted(answer))
        val posted = assertNotNull(InnerTubeComments.parsePosted(answer))
        assertEquals("new1", posted.id)
        assertEquals("Hello there", posted.text)
    }

    @Test fun aFailedStatusIsNotPosted() {
        assertFalse(InnerTubeComments.wasPosted(Json.parseToJsonElement("""{"actionResults":[{"status":"STATUS_FAILED"}]}""").jsonObject))
        assertFalse(InnerTubeComments.wasPosted(buildJsonObject {}))
    }

    // ---- The payloads -------------------------------------------------------------------------------------------------

    private fun TestScope.account(web: YoutubeWeb, signedIn: Boolean = true): InnerTubeVideoAccount {
        val cookie = if (signedIn) "SID=1; SAPISID=secret" else null
        return InnerTubeVideoAccount(web, MutableStateFlow(cookie), MutableStateFlow<AccountHost?>(null), backgroundScope, { 1000L })
    }

    @Test fun postingSendsTheTextAndTheCreateParams() = runTest(UnconfinedTestDispatcher()) {
        val web = Web(AccountFixtures.json("comment_posted.json"))
        val posted = account(web).postComment("CREATE_PARAMS", "Hello there").getOrThrow()
        val (endpoint, body) = web.calls.single()
        assertEquals("comment/create_comment", endpoint)
        assertEquals("Hello there", body["commentText"]!!.jsonPrimitive.content)
        assertEquals("CREATE_PARAMS", body["createCommentParams"]!!.jsonPrimitive.content)
        assertEquals(setOf("commentText", "createCommentParams"), body.keys)
        assertEquals("new1", posted.id)
    }

    @Test fun replyingSendsTheTextAndTheReplyParams() = runTest(UnconfinedTestDispatcher()) {
        val web = Web(AccountFixtures.json("comment_posted.json"))
        account(web).postReply("REPLY_c1", "Same").getOrThrow()
        val (endpoint, body) = web.calls.single()
        assertEquals("comment/create_comment_reply", endpoint)
        assertEquals("Same", body["commentText"]!!.jsonPrimitive.content)
        assertEquals("REPLY_c1", body["createReplyParams"]!!.jsonPrimitive.content)
    }

    @Test fun ratingSendsTheActionAsTheOneItemOfActions() = runTest(UnconfinedTestDispatcher()) {
        val web = Web(buildJsonObject {})
        account(web).rateComment("LIKE_c1").getOrThrow()
        val (endpoint, body) = web.calls.single()
        assertEquals("comment/perform_comment_action", endpoint)
        assertEquals(listOf("LIKE_c1"), body["actions"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun aRefusedPostIsAFailure() = runTest(UnconfinedTestDispatcher()) {
        val web = Web(Json.parseToJsonElement("""{"actionResults":[{"status":"STATUS_FAILED"}]}""").jsonObject)
        assertIs<AccountFailure.Changed>(account(web).postComment("P", "x").exceptionOrNull())
    }

    @Test fun anAnswerThatCannotBeReadStillShowsWhatWasSent() = runTest(UnconfinedTestDispatcher()) {
        val web = Web(Json.parseToJsonElement("""{"actionResults":[{"status":"STATUS_SUCCEEDED"}]}""").jsonObject)
        val posted = account(web).postComment("P", "Words").getOrThrow()
        assertEquals("Words", posted.text)
        assertEquals("posted-1000", posted.id)
    }

    @Test fun signedOutNothingIsSent() = runTest(UnconfinedTestDispatcher()) {
        val web = Web()
        val account = account(web, signedIn = false)
        assertIs<AccountFailure.NotSignedIn>(account.postComment("P", "x").exceptionOrNull())
        assertIs<AccountFailure.NotSignedIn>(account.postReply("P", "x").exceptionOrNull())
        assertIs<AccountFailure.NotSignedIn>(account.rateComment("A").exceptionOrNull())
        assertTrue(web.calls.isEmpty())
    }
}
