package com.cosmotune.video.account

import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.CommentRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What a list of comments lets the listener do, apart from any window: like, dislike, reply and post, all of which need the sign-in.
 * Signed out (or expired), every one of them asks for it instead ([signInWanted]: the panel opens the shared account sheet).
 *
 * A rating shows at once and goes back if the account refuses it. It is kept here by comment id ([ratings]) because the list's comments
 * came with a page and do not change. Every call comes from the UI's thread.
 */
class CommentInteractions(private val account: VideoAccount, private val scope: CoroutineScope) {

    /** What a comment shows after the listener rated it: the rating, and how far that moved its like count. */
    data class Rated(val rating: CommentRating, val likeDelta: Long)

    /** What just failed, for one line in the panel; cleared by the next action. */
    enum class Failure { RATE, POST }

    private val rated = MutableStateFlow<Map<String, Rated>>(emptyMap())
    private val wanted = MutableStateFlow(false)
    private val replying = MutableStateFlow<Comment?>(null)
    private val failed = MutableStateFlow<Failure?>(null)

    val ratings: StateFlow<Map<String, Rated>> get() = rated

    /** The listener tried something that needs the sign-in. */
    val signInWanted: StateFlow<Boolean> get() = wanted

    /** The comment the composer is answering, when "Reply" was pressed on one. */
    val replyingTo: StateFlow<Comment?> get() = replying

    val failure: StateFlow<Failure?> get() = failed

    /** Whether the account can write now. */
    val canWrite: Boolean get() = (account.state.value as? AccountState.SignedIn)?.expired == false

    fun signInHandled() {
        wanted.value = false
    }

    /** The sign-in is needed: true when the listener has it, else the account sheet is asked for. */
    fun ensureSignedIn(): Boolean {
        if (canWrite) return true
        wanted.value = true
        return false
    }

    /** The rating [comment] shows now. */
    fun ratingOf(comment: Comment, ratings: Map<String, Rated>): CommentRating = ratings[comment.id]?.rating ?: comment.rating

    /** The like count [comment] shows now; null when it has none to show. */
    fun likesOf(comment: Comment, ratings: Map<String, Rated>): Long? {
        val delta = ratings[comment.id]?.likeDelta ?: 0L
        return ((comment.likeCount ?: 0L) + delta).takeIf { it > 0 }
    }

    /** The like button: on, or off if it was on. */
    fun toggleLike(comment: Comment) = press(comment, CommentRating.LIKED)

    /** The dislike button: on, or off if it was on. */
    fun toggleDislike(comment: Comment) = press(comment, CommentRating.DISLIKED)

    private fun press(comment: Comment, button: CommentRating) {
        if (!ensureSignedIn()) return
        failed.value = null
        val before = rated.value[comment.id]
        val from = before?.rating ?: comment.rating
        val to = if (from == button) CommentRating.NONE else button
        val tokens = comment.writes
        val action = when (to) {
            CommentRating.LIKED -> tokens?.like
            CommentRating.DISLIKED -> tokens?.dislike
            CommentRating.NONE -> if (from == CommentRating.LIKED) tokens?.unlike else tokens?.undislike
        }
        if (action == null) {
            failed.value = Failure.RATE
            return
        }
        val delta = (before?.likeDelta ?: 0L) + likeStep(from, to)
        rated.update { it + (comment.id to Rated(to, delta)) }
        scope.launch {
            val result = account.rateComment(action)
            result.exceptionOrNull()?.let { error ->
                rated.update { if (before == null) it - comment.id else it + (comment.id to before) }
                if (error is AccountFailure.NotSignedIn || error is AccountFailure.SessionExpired) wanted.value = true else failed.value = Failure.RATE
            }
        }
    }

    private fun likeStep(from: CommentRating, to: CommentRating): Long =
        (if (to == CommentRating.LIKED) 1L else 0L) - (if (from == CommentRating.LIKED) 1L else 0L)

    /** "Reply" on [comment]: the composer answers it. */
    fun startReply(comment: Comment) {
        if (!ensureSignedIn()) return
        failed.value = null
        replying.value = comment
    }

    fun cancelReply() {
        replying.value = null
    }

    /** Posts [text] on the video with [params]; the comment as posted, or null when it failed (then [failure] says so). */
    suspend fun post(params: String, text: String): Comment? = write { account.postComment(params, text) }

    /** Posts [text] as an answer to [comment]; the comment as posted, or null when it failed (then [failure] says so). */
    suspend fun reply(comment: Comment, text: String): Comment? {
        val params = comment.writes?.reply
        if (params == null) {
            failed.value = Failure.POST
            return null
        }
        return write { account.postReply(params, text) }?.also { replying.value = null }
    }

    private suspend fun write(call: suspend () -> Result<Comment>): Comment? {
        if (!ensureSignedIn()) return null
        failed.value = null
        val result = try {
            call()
        } catch (e: CancellationException) {
            throw e
        }
        result.exceptionOrNull()?.let { error ->
            if (error is AccountFailure.NotSignedIn || error is AccountFailure.SessionExpired) wanted.value = true else failed.value = Failure.POST
        }
        return result.getOrNull()
    }
}
