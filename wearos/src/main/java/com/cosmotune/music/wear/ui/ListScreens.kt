package com.cosmotune.music.wear.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.cosmotune.music.wear.R
import com.cosmotune.music.wear.data.Link
import com.cosmotune.music.wear.data.NowPlaying
import kotlinx.coroutines.delay
import com.cosmotune.music.wear.data.PhoneOutput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.music.wear.data.PhoneLink
import com.cosmotune.music.wear.data.Queue
import com.cosmotune.music.wear.data.QueueRow

/**
 * Up Next. Text first, thumbnails a beat later — see PhoneLink.applyQueue —
 * and asked for again whenever the playing track changes, so the list is never
 * the one from two songs ago.
 */
@Composable
fun QueueScreen(
    queue: Queue?,
    playingId: String?,
    onRequest: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    LaunchedEffect(playingId) { onRequest() }
    val state = rememberScalingLazyListState(initialCenterItemIndex = 1)
    val live = queue?.takeIf { playingId == null || it.mediaId == playingId } ?: queue

    ScreenScaffold(scrollState = state) { padding ->
        WearList(state = state, padding = padding) {
            item(key = "header") { Header(stringResource(R.string.up_next)) }
            when {
                live == null -> item(key = "loading") { Hint(stringResource(R.string.loading)) }
                live.rows.isEmpty() -> item(key = "empty") { Hint(stringResource(R.string.queue_empty)) }
                else -> items(live.rows, key = { it.index }) { row ->
                    QueueCell(
                        row = row,
                        isCurrent = row.index == live.currentIndex,
                        isPast = row.index < live.currentIndex,
                        onClick = { onPlay(row.index) },
                    )
                }
            }
        }
    }
}

/** A song in Up Next as an Orbit row: its disc at the start (the playing one wears the orbit), on the peach wash while it plays. */
@Composable
private fun QueueCell(row: QueueRow, isCurrent: Boolean, isPast: Boolean, onClick: () -> Unit) {
    Cell(onClick = onClick, dimmed = isPast, now = isCurrent) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(WearOrbit.Round),
            ) {
                row.art?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(32.dp))
                }
            }
            if (isCurrent) WearOrbitRing(40.dp, fraction = null, stroke = 1.5.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.playing_now).uppercase(),
                    style = WearType.label,
                    color = WearOrbit.Peach,
                    maxLines = 1,
                )
            }
            Text(
                text = row.title,
                style = WearType.row,
                color = if (isCurrent) WearOrbit.Peach else WearOrbit.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.artist,
                style = WearType.rowSub,
                color = WearOrbit.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shuffle, repeat and love, with the state each is in on the phone. */
@Composable
fun MoreScreen(np: NowPlaying?, link: PhoneLink, onOpenOutput: () -> Unit, onOpenPodio: () -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 1)
    val onWatch by link.watchSpeaker.collectAsStateWithLifecycle()
    ScreenScaffold(scrollState = state) { padding ->
        WearList(state = state, padding = padding) {
            item(key = "header") { Header(stringResource(R.string.more)) }
            item(key = "podio") { ToggleCell(icon = R.drawable.ic_speaker, label = "Podio", value = null, active = false, onClick = onOpenPodio) }
            item(key = "output") {
                ToggleCell(
                    icon = if (onWatch) R.drawable.ic_watch else R.drawable.ic_phone,
                    label = stringResource(R.string.output),
                    value = stringResource(if (onWatch) R.string.this_watch else R.string.on_phone_output),
                    active = false,
                    onClick = onOpenOutput,
                )
            }
            if (np == null) {
                item(key = "none") { Hint(stringResource(R.string.nothing_playing)) }
                return@WearList
            }
            item(key = "shuffle") {
                ToggleCell(
                    icon = R.drawable.ic_shuffle,
                    label = stringResource(R.string.shuffle),
                    value = stringResource(if (np.shuffle) R.string.on else R.string.off),
                    active = np.shuffle,
                    onClick = link::toggleShuffle,
                )
            }
            item(key = "repeat") {
                ToggleCell(
                    icon = R.drawable.ic_repeat,
                    label = stringResource(R.string.repeat),
                    value = stringResource(
                        when (np.repeatMode) {
                            PhoneLink.REPEAT_ALL -> R.string.repeat_all
                            PhoneLink.REPEAT_ONE -> R.string.repeat_one
                            else -> R.string.repeat_off
                        },
                    ),
                    active = np.repeatMode != PhoneLink.REPEAT_OFF,
                    badge = if (np.repeatMode == PhoneLink.REPEAT_ONE) "1" else null,
                    onClick = link::cycleRepeat,
                )
            }
            if (np.canLike) item(key = "love") {
                ToggleCell(
                    icon = if (np.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                    label = stringResource(if (np.liked) R.string.loved else R.string.love),
                    value = null,
                    active = np.liked,
                    onClick = link::toggleLike,
                )
            }
        }
    }
}

@Composable
private fun ToggleCell(
    @DrawableRes icon: Int,
    label: String,
    value: String?,
    active: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Cell(onClick = onClick) {
        // On: solid ink, as a selected Orbit chip is.
        val disc = if (active) WearOrbit.Ink else WearOrbit.Round
        val glyph = if (active) WearOrbit.OnInk else WearOrbit.Ink
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(disc),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = glyph, modifier = Modifier.size(18.dp))
            if (badge != null) {
                Text(
                    text = badge,
                    style = WearType.caption.copy(fontSize = WearType.caption.fontSize * 0.8f),
                    color = glyph,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = WearType.row,
            color = WearOrbit.Ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(text = value, style = WearType.rowSub, color = WearOrbit.Dim, maxLines = 1)
        }
    }
}

/** An Orbit row for a round screen (rule 8): radius 22, ink fill; [now] is the playing row, on the peach wash. */
@Composable
private fun Cell(
    onClick: () -> Unit,
    dimmed: Boolean = false,
    now: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (pressed) WearOrbit.RowPressed else WearOrbit.Row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (dimmed) 0.5f else 1f }
            .clip(RoundedCornerShape(22.dp))
            .background(if (now) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(WearOrbit.Peach.copy(alpha = 0.26f), fill)) else androidx.compose.ui.graphics.SolidColor(fill))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 9.dp)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = WearType.header,
        color = WearOrbit.Ink,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = WearType.subtitle,
        color = WearOrbit.Dim,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WearTextInset, vertical = 12.dp),
    )
}

/** How long Output waits for the phone's list before saying the phone is not there. */
private const val OutputWaitMs = 6_000L

/**
 * Where the music plays: this watch, or any output the phone has. Picking the
 * watch hands the queue over from the phone; picking a phone output while the
 * watch plays hands it back, through that output.
 */
@Composable
fun OutputScreen(link: PhoneLink, onDone: () -> Unit) {
    val outputs by link.outputs.collectAsStateWithLifecycle()
    val onWatch by link.watchSpeaker.collectAsStateWithLifecycle()
    val connection by link.link.collectAsStateWithLifecycle()
    // Asked again on Try again; the phone gets [OutputWaitMs] to answer before the screen says it is not there.
    var attempt by remember { mutableIntStateOf(0) }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        waited = false
        if (attempt > 0) link.retry()
        link.requestOutputs()
        delay(OutputWaitMs)
        waited = true
    }
    val unreachable = outputs == null && (connection == Link.NoPhone || waited)
    val context = LocalContext.current
    val state = rememberScalingLazyListState(initialCenterItemIndex = 1)
    ScreenScaffold(scrollState = state) { padding ->
        WearList(state = state, padding = padding) {
            item(key = "header") { Header(stringResource(R.string.output)) }
            item(key = "watch") {
                OutputCell(
                    icon = R.drawable.ic_watch,
                    label = stringResource(R.string.this_watch),
                    detail = stringResource(R.string.watch_speaker),
                    selected = onWatch,
                    onClick = {
                        link.playOnWatch(context)
                        onDone()
                    },
                )
            }
            val list = outputs
            if (list == null && unreachable) {
                item(key = "nophone") { Hint("Connect your phone to choose one of its outputs. This watch can still play what it has.") }
                item(key = "retry") {
                    Cell(onClick = { attempt++ }) {
                        Text("Try again", style = WearType.row, color = WearOrbit.Ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
            } else if (list == null) {
                item(key = "loading") { Hint(if (connection == Link.Connecting) "Looking for your phone…" else stringResource(R.string.loading)) }
            } else {
                items(list, key = { "out-${it.id}" }) { output ->
                    OutputCell(
                        icon = when (output.kind) {
                            "PHONE" -> R.drawable.ic_speaker
                            "WIRED", "BLUETOOTH", "USB" -> R.drawable.ic_headphones
                            else -> R.drawable.ic_phone
                        },
                        label = outputLabel(output),
                        detail = stringResource(R.string.on_phone_output),
                        selected = output.active && !onWatch,
                        onClick = {
                            link.playOnPhone(output)
                            onDone()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun outputLabel(output: PhoneOutput): String = output.name.takeIf(String::isNotBlank) ?: stringResource(
    when (output.kind) {
        "PHONE" -> R.string.phone_speaker
        "WIRED" -> R.string.wired_headphones
        "USB" -> R.string.usb_audio
        "HDMI" -> R.string.hdmi
        else -> R.string.other_output
    },
)

@Composable
private fun OutputCell(
    @DrawableRes icon: Int,
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Cell(onClick = onClick) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (selected) WearOrbit.Ink else WearOrbit.Round),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (selected) WearOrbit.OnInk else WearOrbit.Ink,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = WearType.row, color = WearOrbit.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = WearType.rowSub, color = WearOrbit.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = WearOrbit.Ink,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
