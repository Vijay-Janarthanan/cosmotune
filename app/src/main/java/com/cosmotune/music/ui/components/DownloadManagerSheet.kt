package com.cosmotune.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import com.cosmotune.ui.bridge.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.cosmotune.ui.bridge.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.R
import com.cosmotune.music.data.model.ROW_ART_PX
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.music.download.DownloadProgress
import com.cosmotune.music.download.DownloadSession
import com.cosmotune.music.download.Downloads
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSheetPill
import com.cosmotune.ui.orbit.OrbitSheetTitle
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * The download indicator in the top bar, beside the account photo.
 *
 * Absent until something is actually downloading, and absent again once the user
 * has looked at the result — neither of which is this composable's decision.
 * [DownloadSession.State.visible] owns both, because "should this be on screen"
 * is a question about a batch of work rather than about a bar, and the same
 * answer has to hold whichever page is showing.
 *
 * It draws the batch's overall progress rather than the running track's. The
 * track is what the notification reports on; from here the interesting number is
 * how much of the *album* is left, and a ring that restarts from zero forty
 * times says nothing about that. Once the queue is quiet the ring is dropped for
 * a tick or a warning — a full circle and a finished job look identical, and only
 * one of them is worth walking over to.
 */
@Composable
fun TopBarDownloadButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val session by DownloadSession.state.collectAsStateWithLifecycle()

    val haptics = rememberHaptics()
    // Animated, because the fraction lands in steps — one track at a time, plus
    // whatever the running one reports — and a ring that jumps in twenty-fifths
    // reads as a stutter rather than as progress.
    val progress by animateFloatAsState(
        targetValue = session.fraction,
        animationSpec = tween(300),
        label = "downloadRingProgress",
    )
    val failed = session.failed > 0
    val tint by animateColorAsState(
        targetValue = when {
            failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(220),
        label = "downloadRingTint",
    )

    AnimatedVisibility(
        visible = session.visible,
        modifier = modifier,
        // Open the slot first; the glyph arrives as the pill approaches its
        // new width. Siblings move with the expanding slot and make room rather
        // than jumping directly to their final positions.
        enter = expandHorizontally(
            animationSpec = tween(260),
            expandFrom = Alignment.End,
        ) + fadeIn(
            animationSpec = tween(durationMillis = 160, delayMillis = 100),
        ),
        exit = fadeOut(
            animationSpec = tween(120),
        ) + shrinkHorizontally(
            animationSpec = tween(durationMillis = 220, delayMillis = 60),
            shrinkTowards = Alignment.End,
        ),
    ) {
        // The kit's round button with the orbit as the progress round it (Orbit rule 2), in the page's ink (the error red on a failure).
        val ink = if (failed) MaterialTheme.colorScheme.error else com.cosmotune.ui.orbit.orbitLook().ink
        val label = when {
            session.busy -> stringResource(R.string.downloads_progress, (session.fraction * 100).toInt())
            failed -> pluralStringResource(R.plurals.downloads_failed_count, session.failed, session.failed)
            else -> stringResource(R.string.downloads_finished)
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            com.cosmotune.ui.orbit.OrbitRoundButton(
                icon = when {
                    failed -> Icons.Rounded.ErrorOutline
                    // A bare arrow inside the ring, not the tray-and-arrow: the ring is already saying "downloading".
                    session.busy -> Icons.Rounded.ArrowDownward
                    else -> Icons.Rounded.DownloadDone
                },
                label = label,
                ink = ink,
                onClick = {
                    haptics.play(Haptic.Select)
                    onClick()
                },
                size = 40.dp,
            )
            if (session.busy) {
                // Never quite zero: a ring pinned at nothing reads as stalled where the first sliver reads as starting.
                com.cosmotune.ui.orbit.OrbitRing(48.dp, ink, progress = { progress.coerceAtLeast(0.02f) }, spinning = false, stroke = 2.dp, moon = 3.dp)
            }
        }
    }
}

/**
 * The list behind that indicator: every track asked for this session, what
 * became of it, and a way out of the ones still going.
 *
 * A list rather than a single line because the thing being reported on is a
 * batch. `SongActionsSheet`'s download row already answers "what about *this*
 * song" perfectly well and is the right size for that question; the question
 * here is the one it cannot answer — forty tracks were asked for, which of them
 * arrived — and that has as many answers as there were tracks.
 *
 * Rows carry the cover, the title and the credit because a filename is not how
 * anybody remembers a song, and because a batch download is precisely when a
 * user cannot tell from a name whether the right thing is being fetched: the
 * track that fails is one of forty and the only way to recognise it is to see it.
 *
 * @param onDismiss closes the sheet. Called by the header's own control rather
 *   than left to the drag, so there is something obvious to press once the list
 *   is read — and the host marks the batch seen on the way out, which is what
 *   takes the indicator down.
 */
@Composable
fun DownloadManagerSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session by DownloadSession.state.collectAsStateWithLifecycle()
    val queueError by Downloads.queueStorageError.collectAsStateWithLifecycle()
    val fonts = orbitFonts()
    val look = orbitLook()
    // Newest ask last, the order the queue will actually reach them in.
    val items = remember(session.items) { session.items.sortedBy { it.sequence } }

    Column(modifier.fillMaxWidth()) {
        queueError?.let { Text(it, style = fonts.body, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(18.dp)) }
        OrbitSheetTitle(
            title = stringResource(R.string.downloads),
            subtitle = session.summary(),
            // The batch's own ring, beside the heading it belongs to. The
            // per-row rings below are about one track each; this is the one
            // that answers "how much longer", which is what someone opening
            // this sheet mid-album wants.
            leading = if (session.busy) {
                { OrbitRing(44.dp, look.ink, progress = { session.fraction }, spinning = false, stroke = 3.dp, moon = 5.dp) }
            } else null,
            trailing = {
                if (session.busy) {
                    OrbitSheetPill(
                        label = stringResource(R.string.cancel_all),
                        onClick = {
                            // Only what is still going. Cancelling a finished row
                            // would drop it from the list, which is the one thing
                            // this sheet exists to still be showing.
                            items.filterNot { it.progress.settled }
                                .forEach { Downloads.cancel(it.videoId) }
                        },
                    )
                } else {
                    OrbitSheetPill(
                        label = stringResource(R.string.clear),
                        onClick = {
                            DownloadSession.clear()
                            onDismiss()
                        },
                    )
                }
            },
        )

        // Capped rather than left to grow: a hundred-track playlist would
        // otherwise be a sheet that covers the screen and has to be scrolled
        // back up before anything else can be reached.
        LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.videoId }) { item ->
                DownloadManagerRow(
                    item = item,
                    fonts = fonts,
                    look = look,
                    onCancel = { Downloads.cancel(item.videoId) },
                    onRetry = { Downloads.enqueue(context, item.song, item.from) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** One track: its cover as a disc with its own orbit ring, what it is, and where it has got to. */
@Composable
private fun DownloadManagerRow(
    item: DownloadSession.Item,
    fonts: OrbitFonts,
    look: OrbitLook,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val progress = item.progress
    val failed = progress as? DownloadProgress.Failed
    val running = progress as? DownloadProgress.Running
    val credit = listOfNotNull(
        item.song.artist.takeIf { it.isNotBlank() },
        // The release is worth naming where there is one: in a list of
        // forty rows off three albums, the credit alone does not say
        // which batch a row belongs to.
        item.from?.takeIf { it.isNotBlank() && it != item.song.artist },
    ).joinToString(" · ").ifBlank { stringResource(R.string.unknown_artist) }

    OrbitSongRow(
        title = item.song.title,
        subtitle = when (progress) {
            is DownloadProgress.Done -> stringResource(R.string.saved_to_cosmotune_folder)
            is DownloadProgress.Failed -> progress.reason
            else -> credit
        },
        cover = item.song.artworkAt(ROW_ART_PX),
        fonts = fonts,
        look = look,
        onClick = null,
        endPadding = 8.dp,
        titleContent = {
            ExplicitSongTitle(song = item.song, style = fonts.title, color = look.ink)
        },
        discContent = {
            Disc(item.song.artworkAt(ROW_ART_PX), 44.dp)
            // The ring is the progress: indeterminate (the moon circles) until
            // the first response names a length, a ring frozen at nothing
            // reads as broken.
            if (running != null) {
                OrbitRing(
                    52.dp, look.ink,
                    progress = if (running.fraction > 0f) ({ running.fraction }) else null,
                    spinning = true, stroke = 1.5.dp, moon = 3.dp,
                )
            }
        },
        trailingText = when (progress) {
            is DownloadProgress.Queued -> stringResource(R.string.queued)
            is DownloadProgress.Running ->
                if (progress.fraction > 0f) "${(progress.fraction * 100).toInt()}%" else stringResource(R.string.starting)
            else -> null
        },
        trailing = {
            Spacer(Modifier.width(8.dp))
            // One control, and which one it is follows the row's state: a running
            // track can be stopped, a failed one can be asked for again, and a
            // finished one needs nothing at all.
            when {
                failed != null -> OrbitRoundButton(Icons.Rounded.Refresh, stringResource(R.string.retry), look.ink, onRetry, size = 36.dp)
                !progress.settled -> OrbitRoundButton(Icons.Rounded.Close, stringResource(R.string.cancel), look.ink, onCancel, size = 36.dp)
                else -> Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = look.dim, modifier = Modifier.size(22.dp))
            }
        },
    )
}

/**
 * The one line under the heading — what state the batch as a whole is in.
 *
 * Counts rather than a percentage: the sheet already draws the percentage as a
 * bar, and what a percentage cannot say is that thirty-nine of forty arrived and
 * one did not, which is the only outcome anybody needs to act on.
 */
@Composable
private fun DownloadSession.State.summary(): String {
    val waitingText = if (waiting > 0) {
        pluralStringResource(R.plurals.download_waiting_count, waiting, waiting)
    } else null
    val finishedText = if (finished > 0) {
        pluralStringResource(R.plurals.download_done_count, finished, finished)
    } else null
    val failedText = if (failed > 0) {
        pluralStringResource(R.plurals.download_failed_count, failed, failed)
    } else null
    val parts = listOfNotNull(waitingText, finishedText, failedText)
    return when {
        parts.isEmpty() -> stringResource(R.string.nothing_downloading)
        busy -> parts.joinToString(" · ")
        failed > 0 -> parts.joinToString(" · ")
        else -> pluralStringResource(R.plurals.all_songs_downloaded, finished, finished)
    }
}

/** The progress ring in the bar, sized to the account photo beside it. */
private val RING_SIZE = 26.dp

/** The glyph inside that ring, small enough to leave the stroke clear. */
private val GLYPH_IN_RING = 15.dp

/** With no ring around it, the glyph is a normal bar icon. */
private val GLYPH_SIZE = 22.dp

/** How much of the screen the list may take before it scrolls instead. */
private val LIST_MAX_HEIGHT = 380.dp
