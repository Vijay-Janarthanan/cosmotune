package com.cosmotune.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState
import java.util.Locale
import kotlin.math.roundToInt

private const val StepMs = 100

/**
 * A player drawer for shifting all synced lyrics against the playback clock: the [PlayerDrawer] the other player sheets go up in
 * (an Orbit sheet), the offset in the wide face, a minus and a plus round button either side of the Orbit seek line, and Reset.
 */
@Composable
internal fun LyricsOffsetSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val look = orbitLook()
    val fonts = orbitFonts()

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.lyrics_offset),
        onDismiss = onDismiss,
        modifier = modifier,
        titleGap = 4.dp,
    ) {
        Text(
            text = stringResource(Res.string.lyrics_offset_description),
            style = fonts.body,
            color = look.dim,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 16.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(look.row)
                .padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatOffset(offsetMs),
                style = fonts.heroTitle.copy(fontSize = 30.sp, lineHeight = 32.sp),
                color = look.ink,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OrbitRoundButton(
                    icon = Icons.Rounded.Remove,
                    label = stringResource(Res.string.decrease_lyrics_offset),
                    ink = look.ink,
                    enabled = offsetMs > MIN_LYRICS_OFFSET_MS,
                    onClick = {
                        haptics.play(Haptic.Select)
                        PlayerSettings.setLyricsOffsetMs(offsetMs - StepMs)
                    },
                )
                OrbitProgressLine(
                    progress = { offsetToFraction(offsetMs) },
                    ink = look.ink,
                    modifier = Modifier.weight(1f),
                    onSeek = { PlayerSettings.setLyricsOffsetMs(fractionToOffset(it)) },
                    live = true,
                )
                OrbitRoundButton(
                    icon = Icons.Rounded.Add,
                    label = stringResource(Res.string.increase_lyrics_offset),
                    ink = look.ink,
                    enabled = offsetMs < MAX_LYRICS_OFFSET_MS,
                    onClick = {
                        haptics.play(Haptic.Select)
                        PlayerSettings.setLyricsOffsetMs(offsetMs + StepMs)
                    },
                )
            }
        }

        OrbitSheetButton(
            label = stringResource(Res.string.lyrics_offset_reset),
            enabled = offsetMs != 0,
            modifier = Modifier.padding(top = 14.dp),
            onClick = {
                haptics.play(Haptic.Tap)
                PlayerSettings.setLyricsOffsetMs(0)
            },
        )
    }
}

private fun offsetToFraction(offsetMs: Int): Float =
    (offsetMs - MIN_LYRICS_OFFSET_MS).toFloat() /
        (MAX_LYRICS_OFFSET_MS - MIN_LYRICS_OFFSET_MS)

private fun fractionToOffset(fraction: Float): Int {
    val raw = MIN_LYRICS_OFFSET_MS +
        fraction.coerceIn(0f, 1f) *
        (MAX_LYRICS_OFFSET_MS - MIN_LYRICS_OFFSET_MS)
    return (raw / StepMs).roundToInt() * StepMs
}

private fun formatOffset(offsetMs: Int): String = String.format(
    Locale.getDefault(),
    "%+.1f s",
    offsetMs / 1_000f,
)
