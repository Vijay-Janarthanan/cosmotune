package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_all
import com.cosmotune.video.resources.video_meta_join
import com.cosmotune.video.resources.video_subscribe
import com.cosmotune.video.resources.video_subscribed
import com.cosmotune.video.resources.video_subscribers
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.stringResource

/*
 * The channels as circles and as rows (spec 5.7, 5.3): the strip over the Subscriptions feed, and the row of a channel in search
 * results and in the list of channels.
 */

/** The strip's padding: 12 above, 16 at the sides, 4 below (spec 5.7: "padding 12 16 4"). */
private val STRIP_PADDING = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)

/** The gap between the strip's items (spec 5.7: "gap 12"). */
private val STRIP_GAP = 12.dp

/** The name under a circle: 12 sp. */
private const val STRIP_NAME_SP = 12f

/** The selected circle's ring: 2 dp in `accent`, with a 2 dp gap of the page between it and the picture. */
private val RING_WIDTH = 2.dp
private val RING_GAP = 2.dp

/** The key of the "All" item (no channel id is this long). */
private const val ALL_KEY = "all"

/**
 * The channels you follow as a row of circles (spec 5.7): "All" first, then one 56 dp circle over its 12 sp name for each of
 * [channels], 72 dp wide, 12 dp apart, in padding 12 16 4. The one whose id is [selectedId] (null: "All") is ringed with a 2 dp
 * `accent` ring and its name is in full ink. A tap calls [onSelect] with the channel's id, or with null for "All". Keyed by channel id.
 */
@Composable
fun ChannelStrip(
    channels: List<Subscription>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = STRIP_PADDING,
        horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
    ) {
        item(key = ALL_KEY) {
            StripItem(name = stringResource(Res.string.video_all), avatarUrl = null, all = true, selected = selectedId == null, onClick = { onSelect(null) })
        }
        items(channels, key = { it.channelId }) { channel ->
            StripItem(
                name = channel.name,
                avatarUrl = channel.avatarUrl,
                all = false,
                selected = channel.channelId == selectedId,
                onClick = { onSelect(channel.channelId) },
            )
        }
    }
}

/** One circle and its name: a button (the choice of a filter), selected or not. */
@Composable
private fun StripItem(name: String, avatarUrl: String?, all: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val interaction = remember { MutableInteractionSource() }
    val style = remember(type, selected, name) {
        type.forText(
            TextStyle(
                fontFamily = type.textFamily,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = STRIP_NAME_SP.sp,
                lineHeight = STRIP_NAME_SP.sp * 1.3f,
            ),
            name,
        )
    }
    // a press dims the item (read in the layer block: no recomposition), keyboard focus rings it; neither clips the selected ring
    val pressed = interaction.collectIsPressedAsState()
    Column(
        Modifier
            .width(VideoMetrics.STRIP_ITEM_W_DP.dp)
            .graphicsLayer { alpha = if (pressed.value) PRESSED_ALPHA else 1f }
            .cosmoFocusRing(interaction, CORNER)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(VideoMetrics.STRIP_AVATAR_DP.dp)
                .drawBehind {
                    if (selected) {
                        // outside the picture: a gap of the page, then the ring (the 72 dp item has 8 dp each side of the circle)
                        val stroke = RING_WIDTH.toPx()
                        val radius = size.minDimension / 2f + RING_GAP.toPx() + stroke / 2f
                        drawCircle(colors.live(CoverField.ACCENT), radius = radius, style = Stroke(stroke))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (all) {
                Box(
                    Modifier
                        .size(VideoMetrics.STRIP_AVATAR_DP.dp)
                        .clip(CircleShape)
                        .drawBehind { drawRect(colors.live(CoverField.ACCENT_CONTAINER)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(VideoIcons.Subscriptions, contentDescription = null, tint = Color(palette.onAccentContainer), modifier = Modifier.size(ALL_ICON))
                }
            } else {
                ChannelAvatar(avatarUrl, VideoMetrics.STRIP_AVATAR_DP.dp)
            }
        }
        Text(
            text = name,
            style = style,
            color = Color(if (selected) palette.textPrimary else palette.textSecondary),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = NAME_GAP),
        )
    }
}

/** The glyph in the "All" circle. */
private val ALL_ICON = 26.dp

/** Between a circle and its name. */
private val NAME_GAP = 6.dp

/** The corner of the keyboard focus ring around an item. */
private val CORNER = 16.dp

/** An item under a finger. */
private const val PRESSED_ALPHA = 0.7f

/**
 * A channel in a list (spec 5.0, 5.3): a [CosmoListRow] 64 dp tall with a 52 dp circle, the name over "handle · 1.2M subscribers",
 * and at the end a small tonal button, "Subscribe" or "Subscribed" ([subscribed]); [onSubscribe] is its tap, [onClick] the row's.
 */
@Composable
fun ChannelRow(
    item: ChannelItem,
    subscribed: Boolean,
    onClick: () -> Unit,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handle = item.handle?.takeIf { it.isNotBlank() }
    val subscribers = item.subscriberCount?.let { stringResource(Res.string.video_subscribers, VideoText.compact(it)) }
    val subtitle = when {
        handle != null && subscribers != null -> stringResource(Res.string.video_meta_join, handle, subscribers)
        handle != null -> handle
        else -> subscribers
    }
    val label = stringResource(if (subscribed) Res.string.video_subscribed else Res.string.video_subscribe)
    CosmoListRow(
        title = item.name,
        onClick = onClick,
        modifier = modifier,
        subtitle = subtitle,
        leading = CosmoRowLeading.Cover(item.avatarUrl, circle = true),
        trailing = {
            CosmoButton(text = label, onClick = onSubscribe, kind = CosmoButtonKind.TONAL, size = CosmoButtonSize.SMALL)
        },
    )
}
