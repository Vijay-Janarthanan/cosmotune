package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_subscribe
import com.cosmotune.video.resources.video_subscribed
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * The Subscribe pill of the video apps: a 36 dp pill. "Subscribe" is inverted, the page's ink as the fill and its ground as the label
 * (white with black on the near-black ground); "Subscribed" is a wash of the ink with the ink as its label. One block for the watch
 * page's channel row, the channel page's head and the channel rows of Search.
 */

private val PILL_HEIGHT = 36.dp
private val PILL_PADDING = 16.dp
private const val PILL_SP = 14f
private const val PILL_WEIGHT = 600
private const val PILL_PRESSED = 0.85f
private const val WASH_REST = 0.10f
private const val WASH_PRESSED = 0.18f

/** "Subscribe" or "Subscribed" ([subscribed]); a tap calls [onClick]. It keeps a 48 dp touch target and the focus ring. */
@Composable
internal fun SubscribePill(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = stringResource(if (subscribed) Res.string.video_subscribed else Res.string.video_subscribe)
    val style = remember(type, label) { type.forText(type.label.copy(fontSize = PILL_SP.sp, fontWeight = FontWeight(PILL_WEIGHT), letterSpacing = 0.sp), label) }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .height(PILL_HEIGHT)
            .cosmoFocusRing(interaction, PILL_HEIGHT / 2)
            .clip(CircleShape)
            .drawBehind {
                val ink = colors.live(CoverField.TEXT_PRIMARY)
                if (subscribed) {
                    drawRect(ink.copy(alpha = if (pressed) WASH_PRESSED else WASH_REST))
                } else {
                    drawRect(ink.copy(alpha = if (pressed) PILL_PRESSED else 1f))
                }
            }
            .clickable(interaction, indication = null, role = Role.Button) {
                haptics.play(Haptic.Tap)
                onClick()
            }
            .padding(horizontal = PILL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = style,
            color = if (subscribed) Color(palette.textPrimary) else videoGroundColor(colors, CoverField.BACKGROUND_TOP),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What a Subscribe pill does: `toggle(channel, subscribed)` subscribes to the channel or leaves it. The local library is always
 * changed (the pills of the app follow it); while a Google account is signed in the account's own subscription is changed too, so
 * the two agree. The account's answer is not waited for.
 */
@Composable
internal fun rememberSubscribeToggle(): (ChannelItem, Boolean) -> Unit {
    val graph = LocalVideoGraph.current
    val scope = rememberCoroutineScope()
    val signedIn = graph.account.state.collectAsState().value is AccountState.SignedIn
    return remember(graph, scope, signedIn) {
        { channel, subscribed ->
            if (subscribed) graph.library.unsubscribe(channel.id) else graph.library.subscribe(channel)
            if (signedIn) scope.launch { graph.account.setSubscribed(channel.id, !subscribed) }
        }
    }
}
