package com.cosmotune.ui.nowplaying

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cancel
import com.cosmotune.music.sharedui.resources.share
import com.cosmotune.music.ui.haptics.Haptics
import com.cosmotune.music.ui.player.LyricsPicker
import com.cosmotune.music.ui.player.LyricsShareRequest
import com.cosmotune.music.ui.player.PlayerBackHandler
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.lyricsShareAvailable
import com.cosmotune.music.ui.player.rememberLyricsPicker
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/*
 * Lyric card sharing in the new Now Playing (the old view's feature, carried over): long-press a line to start choosing lines, tap to add or drop one, then
 * Share turns the chosen lines into a card. The choice, its budget (one card holds about five short lines), the order of the
 * lines on the card and the marks for lines that were skipped are the old panel's own `rememberLyricsPicker`, reused as it is;
 * the card itself is drawn by the platform ([com.cosmotune.music.ui.player.PlayerHost.LyricsShareSheet], the phone's) and
 * is not in the new look.
 */

/** What a line of lyrics needs to know about choosing lines for a card: the lines are picked by their index in the list. */
@Immutable
internal class LyricSelection(
    /** Whether this platform can turn picked lines into a card at all (the phone; not the desktop). */
    val canPick: Boolean,
    val picking: Boolean,
    val picked: Set<Int>,
    /** A long press on a line: start choosing, or, once choosing, add that line. */
    val onPick: (Int) -> Unit,
    /** A tap while choosing: add or drop that line. */
    val onToggle: (Int) -> Unit,
) {
    companion object {
        /** Nothing can be picked: a plain tap, and nothing on a long press. */
        val None = LyricSelection(canPick = false, picking = false, picked = emptySet(), onPick = {}, onToggle = {})
    }
}

/**
 * The pick and the card it makes, for the player to hold: [selection] for the lines, the footer for Cancel and Share, and the
 * card (a request the platform's sheet draws) once Share is pressed. Made by [rememberCosmoLyricShare].
 */
@Stable
internal class CosmoLyricShare(
    private val picker: LyricsPicker,
    val canPick: Boolean,
    /** What the lines are given: the same value for as long as the pick is the same, so that the rows do not start again with every recomposition of the player. */
    val selection: LyricSelection,
    private val card: MutableState<LyricsShareRequest?>,
) {
    val picking: Boolean get() = picker.picking

    /** Share is on once something is picked, and off again when a tap would have pushed the pick past what one card holds. */
    val shareEnabled: Boolean get() = picker.picks.isNotEmpty() && !picker.overBudget

    /** The card to draw, set the moment Share is confirmed. */
    val request: LyricsShareRequest? get() = card.value

    fun cancel() = picker.cancel()
    fun share() = picker.share()
    fun dismissCard() { card.value = null }
}

/**
 * The pick for [lyrics] of [song]. The picks index into [lyrics] and the pick starts over whenever the track or the lines change
 * (another song, a re-fetched verse), so a stale set never points at lines that are no longer there.
 */
@Composable
internal fun rememberCosmoLyricShare(
    song: Song,
    lyrics: List<LyricLine>?,
    subLines: List<LyricLine>?,
    artworkUrl: String?,
    haptics: Haptics,
): CosmoLyricShare {
    val card = remember { mutableStateOf<LyricsShareRequest?>(null) }
    val picker = rememberLyricsPicker(
        song = song, lines = lyrics, subLines = subLines, artworkUrl = artworkUrl, haptics = haptics,
        onCard = { card.value = it },
    )
    val latest by rememberUpdatedState(picker)
    val canPick = lyricsShareAvailable
    // The picker's own functions are made anew on every composition; the rows are handed ones that call whichever is the latest.
    val selection = remember(canPick, picker.picking, picker.picks) {
        LyricSelection(canPick, picker.picking, picker.picks, onPick = { latest.pick(it) }, onToggle = { latest.toggle(it) })
    }
    return CosmoLyricShare(picker, canPick, selection, card)
}

/**
 * Back and the card, over the whole player. Back takes away whichever is up and leaves the lyrics underneath exactly where the
 * reader left them: the card first, then the pick. It must be composed after the player's other sheets (a later registration is
 * the one back reaches first), and the card is drawn over [hazeState], the page's ground, as the other sheets are.
 */
@Composable
internal fun CosmoLyricShareHost(share: CosmoLyricShare, hazeState: HazeState) {
    PlayerBackHandler(enabled = share.request != null) { share.dismissCard() }
    PlayerBackHandler(enabled = share.picking) { share.cancel() }
    share.request?.let { request ->
        PlayerPlatform.host.LyricsShareSheet(hazeState = hazeState, request = request, onDismiss = share::dismissCard)
    }
}

/**
 * The foot of the lyrics while lines are being chosen: Cancel and Share, where the status line and the two toggles are the rest
 * of the time (the toggles would redraw the very lines the pick is indexing). Page buttons, so B: a quiet one and a primary one.
 * No count and no budget: what is chosen is said by the lines themselves, which light up, and a line that will not fit does not.
 */
@Composable
internal fun LyricsPickFooter(shareEnabled: Boolean, onCancel: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CosmoButton(stringResource(Res.string.cancel), onCancel, Modifier.weight(1f), kind = CosmoButtonKind.QUIET, size = CosmoButtonSize.MEDIUM)
        CosmoButton(
            stringResource(Res.string.share), onShare, Modifier.weight(1f), kind = CosmoButtonKind.PRIMARY, size = CosmoButtonSize.MEDIUM,
            icon = Icons.Rounded.IosShare, enabled = shareEnabled,
        )
    }
}
