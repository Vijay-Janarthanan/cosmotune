package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_subscribe
import com.cosmotune.video.resources.video_subscribed
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource


/**
 * Subscribe (DESIGN_SYSTEM.md 4.12, 6.2): the kit's dense button, the accent while not subscribed (a page's one filled action) and tonal
 * once [subscribed]. In a list of channels, where the page has no one main action, [tonal] keeps it tonal either way.
 */
@Composable
internal fun SubscribePill(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tonal: Boolean = false) {
    KitButton(
        text = stringResource(if (subscribed) Res.string.video_subscribed else Res.string.video_subscribe),
        onClick = onClick,
        modifier = modifier,
        kind = if (subscribed || tonal) KitButtonKind.TONAL else KitButtonKind.PRIMARY,
        dense = true,
    )
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
