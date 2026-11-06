package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_subscribe
import com.cosmotune.video.resources.video_subscribed
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource


/**
 * Subscribe (Orbit rule 7, the chip): a 40 dp pill, solid ink with the ground's colour for its words while not subscribed (the
 * channel's one main action), and the quiet 8 % chip with a check once [subscribed]. In a list of channels, where the page has no one
 * main action, [tonal] keeps it quiet either way. On a colour card [ink] and [fill] are the card's.
 */
@Composable
internal fun SubscribePill(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tonal: Boolean = false, ink: Color? = null, fill: Color? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val text = stringResource(if (subscribed) Res.string.video_subscribed else Res.string.video_subscribe)
    val solid = !subscribed && !tonal
    val pen = ink ?: look.ink
    val words = if (solid) (fill ?: look.ground) else pen
    Row(
        modifier.height(40.dp).clip(CircleShape).background(if (solid) pen else pen.copy(alpha = if (ink != null) 0.12f else 0.08f))
            .clickable(role = Role.Button, onClick = onClick).padding(start = if (subscribed) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (subscribed) {
            Icon(CosmoIcons.Check, contentDescription = null, tint = words, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = fonts.chip, color = words, maxLines = 1)
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
