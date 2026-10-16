package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.cosmotune.ui.components.cosmoFocusRing
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.components.longPressMenuClickable

/**
 * What every card and row of Video mode does with a finger, a mouse and a keyboard, written once: a tap calls [onClick]; a hold
 * and a right click (on the desktop) call [onMore] (the video sheet), through the same two modifiers part two's rows use
 * (`longPressMenuClickable` and `contextClick`); the whole block is one button for assistive technology (`Role.Button`, its
 * texts merged); and keyboard focus draws the B focus ring ([cosmoFocusRing], [radius] is the block's corner) while a touch or a
 * click draws none. [onMore] null makes a block with no menu (a hold does nothing).
 *
 * `longPressMenuClickable` takes no interaction source, so the ring is fed from the focus the clickable node takes: a focus
 * listener placed outside it reports `hasFocus` for it, and the bridge turns that into the focus interaction the ring reads.
 */
@Composable
internal fun Modifier.videoClickable(radius: Dp, onClick: () -> Unit, onMore: (() -> Unit)?): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val bridge = remember(interaction) { FocusBridge(interaction) }
    return this
        .cosmoFocusRing(interaction, radius)
        .onFocusChanged(bridge.listener)
        .semantics(mergeDescendants = true) { role = Role.Button }
        .longPressMenuClickable(onClick = onClick, onLongClick = onMore)
        .contextClick(onMore)
}

/** Turns the focus state of a clickable's node into the `Focus` and `Unfocus` interactions that [cosmoFocusRing] collects. */
private class FocusBridge(private val source: MutableInteractionSource) {
    private var focus: FocusInteraction.Focus? = null

    val listener: (FocusState) -> Unit = { state ->
        if (state.hasFocus) {
            if (focus == null) {
                val next = FocusInteraction.Focus()
                focus = next
                source.tryEmit(next)
            }
        } else {
            focus?.let { source.tryEmit(FocusInteraction.Unfocus(it)) }
            focus = null
        }
    }
}
