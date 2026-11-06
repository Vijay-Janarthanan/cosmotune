package com.cosmotune.video.ui.comments

import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.ui.VideoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitMediaRow
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.account.CommentInteractions
import com.cosmotune.video.comments.CommentsFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.Comment
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_comment_add
import com.cosmotune.video.resources.video_comment_cancel
import com.cosmotune.video.resources.video_comment_failed
import com.cosmotune.video.resources.video_comment_post
import com.cosmotune.video.resources.video_comment_rate_failed
import com.cosmotune.video.resources.video_comment_reply_to
import com.cosmotune.video.resources.video_comment_sign_in
import com.cosmotune.video.ui.account.VideoAccountSheet
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * Writing in the comments: the row that opens the box to write in, the box, and the account sheet that anything needing the sign-in opens.
 * Posting needs the sign-in; signed out the row says so, and pressing it (or a like, or "Reply") opens the shared account sheet.
 */

/** The list's writes, for the comment rows; null where a list has none (the watch page's tile). */
internal val LocalCommentInteractions = compositionLocalOf<CommentInteractions?> { null }


/**
 * The composer over a list. [parent] is the comment whose replies the list shows (its answers go to it); on the comments list it is null,
 * and the post goes on the video, or to the comment whose "Reply" was pressed. It is left out when there is nothing it could do: signed in,
 * on a list that gave no way to post.
 */
@Composable
internal fun CommentComposer(feed: CommentsFeed, parent: Comment?, floating: Boolean) {
    val writes = LocalCommentInteractions.current ?: return
    val state by feed.state.collectAsState()
    val replying by writes.replyingTo.collectAsState()
    val failure by writes.failure.collectAsState()
    val target = replying ?: parent
    val params = state.createParams
    val account by LocalVideoGraph.current.account.state.collectAsState()
    val signedIn = writes.canWrite
    val canPost = if (target != null) target.writes?.reply != null else params != null
    if (signedIn && !canPost && failure == null) return
    var open by rememberSaveable(feed) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(Modifier.fillMaxWidth()) {
        if (signedIn && (open || replying != null)) {
            val name = target?.authorName.orEmpty()
            var text by rememberSaveable(feed) { mutableStateOf("") }
            var sending by remember { mutableStateOf(false) }
            CommentField(
                text = text,
                hint = if (target != null) stringResource(Res.string.video_comment_reply_to, name) else stringResource(Res.string.video_comment_add),
                onText = { text = it },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitActionPill(
                    label = stringResource(Res.string.video_comment_cancel),
                    onClick = {
                        open = false
                        writes.cancelReply()
                    },
                    fonts = fonts,
                    look = look,
                )
                OrbitActionPill(
                    label = stringResource(Res.string.video_comment_post),
                    enabled = text.isNotBlank() && !sending && canPost,
                    onClick = {
                        val body = text.trim()
                        sending = true
                        scope.launch {
                            val answered = target
                            val posted = if (answered != null) writes.reply(answered, body) else params?.let { writes.post(it, body) }
                            sending = false
                            if (posted != null) {
                                // under the comment it answers on the comments list; first among the replies when they are what shows
                                feed.addPosted(posted, after = if (parent == null) answered?.id else null)
                                text = ""
                                open = false
                            }
                        }
                    },
                    fonts = fonts,
                    look = look,
                    primary = true,
                )
            }
        } else {
            val who = account as? AccountState.SignedIn
            val avatar = who?.avatarUrl?.takeIf { signedIn }
            OrbitSongRow(
                title = stringResource(if (signedIn) Res.string.video_comment_add else Res.string.video_comment_sign_in),
                subtitle = who?.displayName?.takeIf { signedIn && it.isNotBlank() }.orEmpty(),
                cover = avatar,
                fonts = fonts,
                look = look,
                onClick = { if (writes.ensureSignedIn()) open = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 4.dp),
                height = 62.dp,
                icon = if (avatar == null) VideoIcons.Comment else null,
            )
        }
        failure?.let { kind ->
            val message = stringResource(if (kind == CommentInteractions.Failure.RATE) Res.string.video_comment_rate_failed else Res.string.video_comment_failed)
            Text(
                text = message,
                style = fonts.body,
                color = look.dim,
                modifier = Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 4.dp),
            )
        }
    }
}

/** The box to write in, focused as it opens. It grows with the text. */
@Composable
private fun CommentField(text: String, hint: String, onText: (String) -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    OrbitInputField(
        value = text,
        onValueChange = onText,
        placeholder = hint,
        fonts = orbitFonts(),
        look = orbitLook(),
        modifier = Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 4.dp).fillMaxWidth().focusRequester(requester),
        singleLine = false,
        sentences = true,
    )
}

/**
 * The shared account sheet, when something needed the sign-in. Signing in shows its screen over the app, and the comment sheet is a
 * window of its own that would stand over that: [onSigningIn] closes it first. A list that signs in or out reads its first page again,
 * because what each comment offers changed with it.
 */
@Composable
internal fun CommentsAccount(feed: CommentsFeed, writes: CommentInteractions, onSigningIn: () -> Unit) {
    val account = LocalVideoGraph.current.account
    val state by account.state.collectAsState()
    val key = (state as? AccountState.SignedIn)?.let { it.active?.id.orEmpty() to it.expired }
    var seen by remember(feed) { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (key != seen) {
            seen = key
            feed.refresh()
        }
    }
    val wanted by writes.signInWanted.collectAsState()
    if (!wanted) return
    val scope = rememberCoroutineScope()
    VideoAccountSheet(
        state = state,
        onSignIn = {
            onSigningIn()
            scope.launch { account.signInRequest() }
        },
        onSwitch = { id -> scope.launch { account.switchAccount(id) } },
        onSignOut = { scope.launch { account.signOut() } },
        onDismiss = writes::signInHandled,
    )
}
