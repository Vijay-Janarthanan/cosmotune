package com.cosmotune.video.account

import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.CommentRating
import com.cosmotune.video.model.CommentWriteTokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a comment list's buttons do: each needs the sign-in, a rating shows at once and goes back when it is refused. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommentInteractionsTest {
    private class Account(signedIn: Boolean = true) : VideoAccount by FakeVideoAccount(signedIn) {
        val rated = mutableListOf<String>()
        val posted = mutableListOf<Pair<String, String>>()
        var failure: Throwable? = null

        override suspend fun rateComment(action: String): Result<Unit> {
            rated += action
            return failure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun postComment(params: String, text: String): Result<Comment> {
            posted += params to text
            return failure?.let { Result.failure(it) } ?: Result.success(comment("p1", text = text))
        }

        override suspend fun postReply(params: String, text: String): Result<Comment> {
            posted += params to text
            return failure?.let { Result.failure(it) } ?: Result.success(comment("p2", text = text))
        }
    }

    private companion object {
        val TOKENS = CommentWriteTokens("L", "UL", "D", "UD", "R")

        fun comment(id: String = "c", likes: Long? = 10, rating: CommentRating = CommentRating.NONE, writes: CommentWriteTokens? = TOKENS, text: String = "Hi") = Comment(
            id = id, authorName = "A", authorChannelId = null, authorAvatarUrl = null, text = text, likeCount = likes, publishedText = null, pinned = false,
            heartedByCreator = false, byCreator = false, edited = false, replyCount = 0, replies = null, rating = rating, writes = writes,
        )
    }

    private fun TestScope.of(account: VideoAccount) = CommentInteractions(account, this)

    @Test fun likeThenUnlikeSendTheirOwnValuesAndMoveTheCount() = runTest(UnconfinedTestDispatcher()) {
        val account = Account()
        val writes = of(account)
        val c = comment()
        writes.toggleLike(c)
        assertEquals(CommentRating.LIKED, writes.ratingOf(c, writes.ratings.value))
        assertEquals(11L, writes.likesOf(c, writes.ratings.value))
        writes.toggleLike(c)
        assertEquals(CommentRating.NONE, writes.ratingOf(c, writes.ratings.value))
        assertEquals(10L, writes.likesOf(c, writes.ratings.value))
        assertEquals(listOf("L", "UL"), account.rated)
    }

    @Test fun dislikingALikedCommentTakesItsLikeBack() = runTest(UnconfinedTestDispatcher()) {
        val account = Account()
        val writes = of(account)
        val c = comment(rating = CommentRating.LIKED)
        writes.toggleDislike(c)
        assertEquals(CommentRating.DISLIKED, writes.ratingOf(c, writes.ratings.value))
        assertEquals(9L, writes.likesOf(c, writes.ratings.value))
        writes.toggleDislike(c)
        assertEquals(CommentRating.NONE, writes.ratingOf(c, writes.ratings.value))
        assertEquals(listOf("D", "UD"), account.rated)
    }

    @Test fun aRefusedRatingGoesBackAndSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val account = Account().apply { failure = AccountFailure.Network("offline", IOException()) }
        val writes = of(account)
        val c = comment()
        writes.toggleLike(c)
        assertEquals(CommentRating.NONE, writes.ratingOf(c, writes.ratings.value))
        assertEquals(10L, writes.likesOf(c, writes.ratings.value))
        assertEquals(CommentInteractions.Failure.RATE, writes.failure.value)
    }

    @Test fun signedOutEveryButtonAsksForTheSignInAndSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val account = Account(signedIn = false)
        val writes = of(account)
        writes.toggleLike(comment())
        assertTrue(writes.signInWanted.value)
        writes.signInHandled()
        assertFalse(writes.signInWanted.value)
        writes.toggleDislike(comment())
        assertTrue(writes.signInWanted.value)
        writes.signInHandled()
        writes.startReply(comment())
        assertTrue(writes.signInWanted.value)
        assertNull(writes.replyingTo.value)
        writes.signInHandled()
        assertNull(writes.post("P", "x"))
        assertTrue(writes.signInWanted.value)
        assertTrue(account.rated.isEmpty() && account.posted.isEmpty())
    }

    @Test fun anExpiredSessionFoundOnTheWayAsksForTheSignInToo() = runTest(UnconfinedTestDispatcher()) {
        val account = Account().apply { failure = AccountFailure.SessionExpired() }
        val writes = of(account)
        writes.toggleLike(comment())
        assertTrue(writes.signInWanted.value)
        assertNull(writes.failure.value)
    }

    @Test fun postingGivesTheCommentAndAFailureSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val account = Account()
        val writes = of(account)
        assertEquals("p1", writes.post("CREATE", "Words")?.id)
        assertEquals(listOf("CREATE" to "Words"), account.posted)
        account.failure = AccountFailure.Changed("refused")
        assertNull(writes.post("CREATE", "Words"))
        assertEquals(CommentInteractions.Failure.POST, writes.failure.value)
    }

    @Test fun aReplyUsesTheCommentsOwnValueAndEndsTheReplying() = runTest(UnconfinedTestDispatcher()) {
        val account = Account()
        val writes = of(account)
        val c = comment()
        writes.startReply(c)
        assertEquals(c, writes.replyingTo.value)
        assertEquals("p2", writes.reply(c, "Same")?.id)
        assertEquals(listOf("R" to "Same"), account.posted)
        assertNull(writes.replyingTo.value)
    }

    @Test fun aCommentWithNoValuesCannotBeRatedOrAnswered() = runTest(UnconfinedTestDispatcher()) {
        val account = Account()
        val writes = of(account)
        writes.toggleLike(comment(writes = null))
        assertEquals(CommentInteractions.Failure.RATE, writes.failure.value)
        assertNull(writes.reply(comment(writes = null), "x"))
        assertEquals(CommentInteractions.Failure.POST, writes.failure.value)
        assertTrue(account.rated.isEmpty() && account.posted.isEmpty())
    }
}
