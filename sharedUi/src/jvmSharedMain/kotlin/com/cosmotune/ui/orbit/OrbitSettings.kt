package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons

/*
 * The pieces of Batch A's quiet pages (Canvas repo, design/cosmotune/batch-a/board.html): the stat card a list page leads with
 * (section 1: the space Downloads takes, with its peach bar), the account card that leads Settings (section 2: Home's colour card
 * with the picture as a disc and the sync status, no bite because nothing plays) and the category tile of the settings home (an
 * icon tile, the name and a one-line summary).
 */

/**
 * One quiet stat card (board 1, the right column): ink at 8 %, radius 30, the mono [label], [title] in the wide face, a peach bar
 * filled to [fraction] when there is one (the share of the disk the downloads take), and [line] under it in the dim ink.
 */
@Composable
internal fun OrbitStatCard(
    label: String,
    title: String,
    line: String?,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(look.chip).padding(20.dp)) {
        OrbitLabel(label, fonts, look.dim)
        Text(
            title, style = fonts.heroTitle.copy(fontSize = 24.sp, lineHeight = 27.sp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (fraction != null) {
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(look.ink.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).clip(CircleShape).background(look.accent))
            }
        }
        if (!line.isNullOrBlank()) {
            Text(line, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * The account card that leads Settings: a flat colour card from the picture ([coverFill]; a deep stand-in without one), the picture
 * as a disc (the user glyph on a faint disc when there is none), the mono [label] ("Account · Google"), the [name] in the wide face
 * and the [line] under it (the sync status). No bite: nothing plays here. [compact] is the phone's (a 72 dp disc).
 */
@Composable
fun OrbitAccountCard(
    label: String,
    name: String,
    line: String?,
    pictureUrl: String?,
    fonts: OrbitFonts,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val (fill, ink) = coverFill(pictureUrl, "account:$name")
    // A narrow card (the desktop's column beside the settings page) gives the words the room a big disc would take.
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val disc = if (compact || maxWidth < 400.dp) 72.dp else 96.dp
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (compact) 120.dp else 150.dp).clip(RoundedCornerShape(32.dp)).background(fill)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(if (compact) 18.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp),
    ) {
        Box(Modifier.size(disc).clip(CircleShape).background(ink.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(CosmoIcons.User, contentDescription = null, tint = ink, modifier = Modifier.size(disc * 0.42f))
            if (pictureUrl != null) Disc(pictureUrl, disc)
        }
        Column(Modifier.weight(1f)) {
            OrbitLabel(label, fonts, ink.copy(alpha = 0.75f))
            Text(
                name, style = fonts.heroTitle.copy(fontSize = if (compact) 22.sp else 26.sp, lineHeight = if (compact) 25.sp else 29.sp), color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
            if (!line.isNullOrBlank()) {
                // The sync status wraps whole ("Cloud sync is off. Your library stays on this device."), never cut mid-word.
                Text(line, style = fonts.body.copy(fontSize = 13.sp, lineHeight = 17.sp), color = ink.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (onClick != null) Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
    }
}

/**
 * A category of the settings home (board 2, `.tile`): radius 26, ink at 5 %, a 44 dp icon tile in the category's [fill] with a white
 * glyph, the [title] and the one-line [summary]. [stacked] puts the icon over the text (a narrow cell: the phone's two columns).
 * [selected] (not null: the tile is a tab, the desktop's list beside the page) true is solid ink with the ground's colour for its text.
 * [summaryLines] lets a search result say where it lives and what it does; a [stacked] tile (a narrow cell) gives its summary two lines
 * at least, so "Wi-Fi: Lossless · Mobile data: Lossless" is read whole.
 */
@Composable
internal fun OrbitSettingsTile(
    icon: ImageVector,
    fill: Color,
    title: String,
    summary: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    stacked: Boolean = false,
    summaryLines: Int = 1,
) {
    val on = selected == true
    val ink = if (on) look.ground else look.ink
    val dim = if (on) look.ground.copy(alpha = 0.72f) else look.dim
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(if (on) look.ink else look.row)
            .then(if (selected != null) Modifier.selectable(selected, role = Role.Tab, onClick = onClick) else Modifier.clickable(role = Role.Button, onClick = onClick)),
        contentAlignment = if (stacked) Alignment.TopStart else Alignment.CenterStart,
    ) {
        val iconTile = @Composable {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(fill), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        val text = @Composable { m: Modifier ->
            Column(m) {
                Text(title, style = fonts.title.copy(fontSize = 14.5.sp), color = ink, maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis)
                Text(summary, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = dim, maxLines = if (stacked) maxOf(summaryLines, 2) else summaryLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (stacked) {
            Column(Modifier.padding(16.dp)) {
                iconTile()
                Spacer(Modifier.height(12.dp))
                text(Modifier)
            }
        } else {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                iconTile()
                Spacer(Modifier.width(14.dp))
                text(Modifier.weight(1f))
            }
        }
    }
}

/** [count] cells in [columns] columns [gap] apart, each row as tall as its tallest cell, as the settings home lays its tiles. */
@Composable
internal fun OrbitTileGrid(count: Int, modifier: Modifier = Modifier, gap: Dp = 12.dp, columns: Int = 2, cell: @Composable (Int, Modifier) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (start in 0 until count step columns) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (i in start until start + columns) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { if (i < count) cell(i, Modifier.fillMaxHeight()) }
                }
            }
        }
    }
}

/**
 * A field of a settings page (the proxy's address, user and password): the chip's pill (radius 22, at least 48 dp), the
 * [placeholder] in the dim ink while it is empty, the words in the body face and the ink. [error] rings it in the theme's error
 * colour; [secret] hides what is typed; [enabled] false fades it and takes no typing. [onDone] (or [onSubmit]) is Enter (a dialog's
 * Save, a room code's look-up; an [imeAction] of Next moves on instead). [singleLine] false with [minLines] / [maxLines] makes it a
 * box of several lines (a pasted curve, a comment); [sentences] capitalises them. [textStyle] is laid over the body face (a room
 * code's wide tracking), and [trailing] sits at the field's end (a clear button).
 */
@Composable
fun OrbitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    secret: Boolean = false,
    onDone: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    sentences: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    onSubmit: (() -> Unit)? = null,
    imeAction: androidx.compose.ui.text.input.ImeAction? = null,
    textStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    val words = fonts.body.merge(textStyle)
    val submit = onDone ?: onSubmit
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else maxOf(minLines, maxLines),
        enabled = enabled,
        textStyle = words.copy(color = if (enabled) look.ink else look.dim),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(look.accent),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
            capitalization = if (sentences && !secret) androidx.compose.ui.text.input.KeyboardCapitalization.Sentences else androidx.compose.ui.text.input.KeyboardCapitalization.None,
            imeAction = imeAction ?: if (submit != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
        ),
        keyboardActions = if (submit != null) androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }) else androidx.compose.foundation.text.KeyboardActions.Default,
        modifier = modifier
            .then(if (enabled) Modifier else Modifier.alpha(0.45f))
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(look.chip)
            .then(if (error) Modifier.border(1.5.dp, androidx.compose.material3.MaterialTheme.colorScheme.error, shape) else Modifier)
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(Modifier.padding(start = 18.dp, end = if (trailing != null) 6.dp else 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = words, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}
