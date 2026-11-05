package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channels
import com.cosmotune.video.resources.video_no_subscriptions
import com.cosmotune.video.resources.video_no_subscriptions_text
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ChannelRow
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import org.jetbrains.compose.resources.stringResource

/**
 * The channels you follow (spec 5.8), sorted by name: a [ChannelRow] each (the 52 dp circle, the name) with "Subscribed", whose tap
 * unsubscribes. A channel unsubscribed here keeps its row, now with "Subscribe", until the page is left, so a slip of the finger is
 * undone with the same button. A tap on the row opens the channel. Empty: "No subscriptions yet".
 */
@Composable
fun ChannelsPage(contentPadding: PaddingValues, wide: Boolean) {
    val library = LocalVideoGraph.current.library
    val navigator = LocalVideoNavigator.current
    val subscriptions by library.subscriptions.collectAsState()
    // the channels let go on this visit, by id: their rows stay, with "Subscribe"
    val leftHere = remember { mutableStateMapOf<String, Subscription>() }
    val followed = remember(subscriptions) { subscriptions.mapTo(HashSet()) { it.channelId } }
    val rows = remember(subscriptions, leftHere.toMap()) {
        LibraryPlan.channelsByName(subscriptions + leftHere.values.filter { it.channelId !in followed })
    }
    LibraryList(contentPadding, wide) { cell ->
        item(key = "heading") { LibraryHeader(stringResource(Res.string.video_channels), wide, cell) }
        if (rows.isEmpty()) {
            item(key = "empty") {
                CosmoEmptyState(
                    message = stringResource(Res.string.video_no_subscriptions_text),
                    modifier = cell,
                    title = stringResource(Res.string.video_no_subscriptions),
                    icon = VideoIcons.Subscriptions,
                )
            }
        }
        items(rows, key = { "channel:" + it.channelId }) { channel ->
            val subscribed = channel.channelId in followed
            ChannelRow(
                item = ChannelItem(channel.channelId, channel.name, channel.avatarUrl, subscriberCount = null, videoCount = null, description = null),
                subscribed = subscribed,
                onClick = { navigator.push(VideoRoute.Channel(channel.channelId)) },
                onSubscribe = {
                    if (subscribed) {
                        leftHere[channel.channelId] = channel
                        library.unsubscribe(channel.channelId)
                    } else {
                        library.subscribe(ChannelItem(channel.channelId, channel.name, channel.avatarUrl, null, null, null))
                    }
                },
                modifier = cell,
            )
        }
    }
}
