package com.cosmotune.video.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_mark_end
import com.cosmotune.video.resources.video_mark_now
import com.cosmotune.video.resources.video_mark_send
import com.cosmotune.video.resources.video_mark_sending
import com.cosmotune.video.resources.video_mark_sent
import com.cosmotune.video.resources.video_mark_start
import com.cosmotune.video.resources.video_mark_title
import com.cosmotune.video.resources.video_mark_too_many
import com.cosmotune.video.resources.video_mark_there
import com.cosmotune.video.resources.video_mark_refused
import com.cosmotune.video.resources.video_mark_failed
import com.cosmotune.media.segments.SegmentCategory
import com.cosmotune.media.segments.SegmentSubmission
import com.cosmotune.media.segments.SubmitResult
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.settings.categoryTitle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.util.UUID

/**
 * "Mark a segment" in the player's settings: Start and End take the playhead when tapped, one category is ticked, and Send gives the
 * part to SponsorBlock, which shows it to everyone once others agree. The listener's random SponsorBlock id is made on the first
 * send and kept in the settings.
 */
@Composable
internal fun MarkSegmentRows(playback: VideoPlayback, state: PlaybackUiState, menu: Boolean, back: @Composable (String) -> Unit) {
    val graph = LocalVideoGraph.current
    val scope = rememberCoroutineScope()
    val accent = Color(LocalCosmoColors.current.palette.accent)
    val videoId = state.item?.id.orEmpty()
    var startMs by rememberSaveable(videoId) { mutableStateOf(-1L) }
    var endMs by rememberSaveable(videoId) { mutableStateOf(-1L) }
    var category by rememberSaveable(videoId) { mutableStateOf(SegmentCategory.SPONSOR) }
    var result by rememberSaveable(videoId) { mutableStateOf<SubmitResult?>(null) }
    var sending by rememberSaveable(videoId) { mutableStateOf(false) }
    val now = stringResource(Res.string.video_mark_now)

    back(stringResource(Res.string.video_mark_title))
    CosmoActionRow(CosmoIcons.Previous, stringResource(Res.string.video_mark_start), value = if (startMs < 0) now else time(startMs), menu = menu) {
        startMs = playback.positionMs()
        if (endMs in 0..startMs) endMs = -1
        result = null
    }
    CosmoActionRow(CosmoIcons.Next, stringResource(Res.string.video_mark_end), value = if (endMs < 0) now else time(endMs), menu = menu) {
        endMs = playback.positionMs()
        if (startMs > endMs) startMs = -1
        result = null
    }
    for (c in SegmentSubmission.CATEGORIES) {
        val picked = c == category
        CosmoActionRow(
            icon = if (picked) CosmoIcons.Check else BlankIcon,
            label = categoryTitle(c),
            tint = if (picked) accent else null,
            menu = menu,
        ) { category = c; result = null }
    }
    val submission = SegmentSubmission(videoId, "", state.durationMs, startMs, endMs, category)
    val ready = videoId.isNotEmpty() && startMs >= 0 && endMs >= 0 && submission.isValid && !sending && result != SubmitResult.SENT
    CosmoActionRow(
        VideoIcons.Upload,
        stringResource(Res.string.video_mark_send),
        value = when {
            sending -> stringResource(Res.string.video_mark_sending)
            else -> result?.let { resultText(it) }
        },
        enabled = ready,
        menu = menu,
    ) {
        sending = true
        scope.launch {
            val userId = graph.settings.settings.value.sponsorBlockUserId ?: newUserId().also { id ->
                graph.settings.update { it.copy(sponsorBlockUserId = it.sponsorBlockUserId ?: id) }
            }
            result = graph.segments.submit(submission.copy(userId = userId))
            sending = false
        }
    }
}

@Composable
private fun resultText(result: SubmitResult): String = stringResource(
    when (result) {
        SubmitResult.SENT -> Res.string.video_mark_sent
        SubmitResult.ALREADY_THERE -> Res.string.video_mark_there
        SubmitResult.TOO_MANY -> Res.string.video_mark_too_many
        SubmitResult.REFUSED -> Res.string.video_mark_refused
        SubmitResult.FAILED -> Res.string.video_mark_failed
    },
)

private fun time(ms: Long): String = VideoText.duration(ms / 1000)

/** A private random id for SponsorBlock (64 hex characters; the service asks for at least 30). */
internal fun newUserId(): String = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "")
