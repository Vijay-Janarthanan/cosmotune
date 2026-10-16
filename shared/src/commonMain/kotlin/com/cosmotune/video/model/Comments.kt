package com.cosmotune.video.model

/**
 * One comment (spec 5.9): the author, the text, the like count, the source's age text, the pinned, hearted, by-creator
 * and edited marks, and its replies ([replyCount], and the token that opens them through `VideoSource.replies`, or null).
 */
data class Comment(
    val id: String,
    val authorName: String,
    val authorChannelId: String?,
    val authorAvatarUrl: String?,
    val text: String,
    val likeCount: Long?,
    val publishedText: String?,
    val pinned: Boolean,
    val heartedByCreator: Boolean,
    val byCreator: Boolean,
    val edited: Boolean,
    val replyCount: Int,
    val replies: PageToken?,
)

/**
 * One page of comments or replies (spec 5.9): the comments, the next page, the total when the source gives it, whether
 * comments are turned off ("Comments are turned off" as an empty state), and whether the Top and Newest chips apply.
 */
data class CommentPage(
    val items: List<Comment>,
    val next: PageToken?,
    val totalCount: Long?,
    val disabled: Boolean,
    val sortable: Boolean,
)

/** The comment order (spec 5.9: the chips Top and Newest when the source offers them). */
enum class CommentSort { TOP, NEWEST }
