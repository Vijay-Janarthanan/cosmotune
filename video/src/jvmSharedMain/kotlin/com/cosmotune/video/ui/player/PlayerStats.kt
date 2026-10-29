package com.cosmotune.video.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerSnapshot
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.net.GooglevideoRequests
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_stats_bitrate
import com.cosmotune.video.resources.video_stats_buffer
import com.cosmotune.video.resources.video_stats_client
import com.cosmotune.video.resources.video_stats_codec
import com.cosmotune.video.resources.video_stats_decoder
import com.cosmotune.video.resources.video_stats_dropped
import com.cosmotune.video.resources.video_stats_hardware
import com.cosmotune.video.resources.video_stats_resolution
import com.cosmotune.video.resources.video_stats_smoothing
import com.cosmotune.video.resources.video_stats_software
import org.jetbrains.compose.resources.stringResource

/** The card (spec 6.5): padding 12, 12.5 sp tabular; 8 dp from the left, under the top row; at most 80 % of the box wide. */
private const val STATS_PAD_DP = 12f
private const val STATS_SP = 12.5f
private const val STATS_EDGE_DP = 8f
private const val STATS_GAP_DP = 10f
private const val STATS_MAX_WIDTH_DP = 360f

/** What a stats value shows when it is not known. */
private const val UNKNOWN = "–"

/**
 * Stats for nerds (spec 6.5, Music's nerd stats): a C glass card at the top left with the resolution and frame rate, the codecs, the
 * bitrates (video + audio, kb/s), the buffer ahead (s), the dropped frames, the decoder (hardware or software) and the source client,
 * from [PlaybackUiState] and the engine's snapshot. The buffer counts from the playhead's whole second, so the card recomposes at
 * most once a second.
 */
@Composable
internal fun PlayerStats(playback: VideoPlayback, state: PlaybackUiState, playhead: Playhead, metrics: ChromeMetrics, modifier: Modifier = Modifier) {
    val snapshot by playback.engine.snapshot.collectAsState()
    val smoothing by playback.engine.smoothness.collectAsState()
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    val style = remember(type) { TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Medium, fontSize = STATS_SP.sp).tabular() }
    val shape = remember { RoundedCornerShape(CosmoRadii.md) }
    val video = remember(state.details, state.quality) { chosenVideo(state) }
    val audio = remember(state.audioTracks, state.audioTrackId) { chosenAudio(state) }
    val hardware = state.quality?.let { q -> playback.engine.capabilities.video.firstOrNull { it.codec == q.codec }?.hardware }
    val positionMs = playhead.second.longValue * 1000
    val rows = listOf(
        stringResource(Res.string.video_stats_resolution) to resolution(snapshot, state, video),
        stringResource(Res.string.video_stats_codec) to listOfNotNull(video?.codecs?.ifBlank { null } ?: state.quality?.codec?.name, audio?.codecs?.ifBlank { null }).joinToString(" / ").ifEmpty { UNKNOWN },
        stringResource(Res.string.video_stats_bitrate) to bitrates(video, audio, snapshot),
        stringResource(Res.string.video_stats_buffer) to seconds((state.bufferedMs - positionMs).coerceAtLeast(0)),
        stringResource(Res.string.video_stats_dropped) to snapshot.droppedFrames.toString(),
        stringResource(Res.string.video_stats_decoder) to decoder(snapshot, hardware),
        stringResource(Res.string.video_stats_client) to (video?.url?.let { GooglevideoRequests.clientOf(it) } ?: UNKNOWN),
    ) + listOfNotNull(smoothing.label()?.let { stringResource(Res.string.video_stats_smoothing) to it }) // only a conversion that is happening
    Column(
        modifier
            .padding(start = STATS_EDGE_DP.dp, top = metrics.topRowDp.dp)
            .widthIn(max = STATS_MAX_WIDTH_DP.dp)
            .cosmoGlass(hazeState = null, shape = shape, overVideo = true)
            .padding(STATS_PAD_DP.dp),
    ) {
        for ((label, value) in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(STATS_GAP_DP.dp)) {
                Text(label, style = style, color = Color(palette.textSecondary), maxLines = 1)
                Text(value, style = style, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun decoder(snapshot: PlayerSnapshot, hardware: Boolean?): String {
    val kind = when (hardware) {
        true -> stringResource(Res.string.video_stats_hardware)
        false -> stringResource(Res.string.video_stats_software)
        null -> null
    }
    val name = snapshot.decoderName ?: UNKNOWN
    return if (kind != null) "$name ($kind)" else name
}

/** The video stream playing: the one of the chosen rung (height, frame rate and codec), among the video-only and muxed streams. */
private fun chosenVideo(state: PlaybackUiState): VideoStream? {
    val q = state.quality ?: return null
    val streams = state.details?.streams ?: return null
    return (streams.videoOnly + streams.muxed).firstOrNull { it.height == q.height && it.fps == q.fps && it.codec == q.codec }
        ?: (streams.videoOnly + streams.muxed).firstOrNull { it.height == q.height }
}

/** The audio stream playing: the best of the chosen track (the original when none is chosen). */
private fun chosenAudio(state: PlaybackUiState): AudioStream? =
    state.audioTracks.filter { state.audioTrackId == null || it.trackId == state.audioTrackId }.maxByOrNull { it.bitrate }

private fun resolution(snapshot: PlayerSnapshot, state: PlaybackUiState, video: VideoStream?): String {
    val w = if (snapshot.videoWidth > 0) snapshot.videoWidth else video?.width ?: 0
    val h = if (snapshot.videoHeight > 0) snapshot.videoHeight else video?.height ?: state.quality?.height ?: 0
    if (w <= 0 || h <= 0) return UNKNOWN
    val fps = state.quality?.fps ?: video?.fps
    return if (fps != null && fps > 0) "${w}x$h@$fps" else "${w}x$h"
}

private fun bitrates(video: VideoStream?, audio: AudioStream?, snapshot: PlayerSnapshot): String {
    val parts = listOfNotNull(video?.bitrate?.takeIf { it > 0 }?.let { it / 1000 }, audio?.bitrate?.takeIf { it > 0 }?.let { it / 1000 })
    val streams = if (parts.isEmpty()) UNKNOWN else parts.joinToString(" + ") + " kb/s"
    val link = snapshot.bandwidthKbps?.let { " · $it kb/s" }.orEmpty()
    return streams + link
}

private fun seconds(ms: Long): String {
    val tenths = ms / 100
    return "${tenths / 10}.${tenths % 10} s"
}
