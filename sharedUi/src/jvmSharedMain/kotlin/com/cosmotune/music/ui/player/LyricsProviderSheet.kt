package com.cosmotune.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.music.data.lyrics.LyricsSource
import com.cosmotune.music.ui.LyricsProviderState
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.orbit.OrbitOptionRow
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState

/**
 * Manual provider chooser for the lyrics half-player.
 *
 * It deliberately uses [PlayerDrawer] and the kit's option row ([OrbitOptionRow])
 * as [AudioOutputSheet] does, so it behaves like another player destination rather
 * than a settings dialog. A row that the automatic lookup already completed is
 * entirely local: hits switch immediately and misses cannot be requested twice.
 */
@Composable
internal fun LyricsProviderSheet(
    hazeState: HazeState,
    currentSource: LyricsSource?,
    states: Map<LyricsSource, LyricsProviderState>,
    onSelect: (LyricsSource) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val savedOrder by PlayerSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val sources = remember(savedOrder) { LyricsSource.ordered(savedOrder) }
    var requestedSource by remember { mutableStateOf<LyricsSource?>(null) }
    LaunchedEffect(currentSource, requestedSource) {
        if (requestedSource != null && currentSource == requestedSource) onDismiss()
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.choose_lyrics_provider),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach { source ->
                LyricsProviderRow(
                    source = source,
                    state = states[source] ?: LyricsProviderState.NOT_FETCHED,
                    current = source == currentSource,
                    onClick = {
                        val wasFound = states[source] == LyricsProviderState.FOUND
                        requestedSource = source
                        onSelect(source)
                        if (wasFound) onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * One source: its state on the disc (a spinner while it is being asked, a check once it has the lines, a cross when it has none,
 * a magnifier before it is asked), its name and what it said. The one in use is checked; one that found nothing is faded.
 */
@Composable
private fun LyricsProviderRow(
    source: LyricsSource,
    state: LyricsProviderState,
    current: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    val look = orbitLook()
    // A running automatic request can still be selected: it is marked as the
    // requested source and applied when that same in-flight call completes.
    val enabled = !current && state != LyricsProviderState.NOT_FOUND
    val status = when {
        current -> stringResource(Res.string.lyrics_provider_current)
        state == LyricsProviderState.FOUND -> stringResource(Res.string.lyrics_provider_found)
        state == LyricsProviderState.NOT_FOUND -> stringResource(Res.string.lyrics_provider_not_found)
        state == LyricsProviderState.FETCHING -> stringResource(Res.string.lyrics_provider_fetching)
        else -> stringResource(Res.string.lyrics_provider_not_fetched)
    }
    OrbitOptionRow(
        title = source.label,
        subtitle = status,
        icon = when {
            current || state == LyricsProviderState.FOUND -> Icons.Rounded.Check
            state == LyricsProviderState.NOT_FOUND -> Icons.Rounded.Close
            else -> Icons.Rounded.Search
        },
        onClick = if (enabled) ({
            haptics.play(Haptic.Select)
            onClick()
        }) else null,
        checked = current,
        dimmed = state == LyricsProviderState.NOT_FOUND,
        discContent = if (state == LyricsProviderState.FETCHING) ({
            Box(Modifier.size(40.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = look.ink, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }) else null,
    )
}
