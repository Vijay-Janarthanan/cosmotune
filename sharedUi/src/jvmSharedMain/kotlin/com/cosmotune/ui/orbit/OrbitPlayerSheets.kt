package com.cosmotune.ui.orbit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.sheets.CosmoDialogActions
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.sheets.CosmoDialogHeading

/*
 * The Orbit pieces of the Now Playing sheets and popups (Play on, the lyrics source, Listen Together, Audio details, the cast
 * picker and the audio pipeline, Share lyrics): an option row, a dialog's pill for code outside this module, and the popup card
 * the output drawer's popups open into. Drawn from the kit's own row ([OrbitSongRow]), pill ([OrbitPillButton]) and dialog card
 * ([CosmoDialogCard]); nothing here is a second one of those.
 */

/** A row of a player sheet: [OrbitSongRow]'s row in its 64 dp option size. */
private val OPTION_ROW_HEIGHT = 64.dp
private val OPTION_DISC = 40.dp
private const val DIMMED_ALPHA = 0.5f

/**
 * One choice in a player sheet (an output, a lyrics source, a member, a receiver): [OrbitSongRow] with [icon] on a quiet disc (or
 * [discContent] in its place: a picture, a spinner), the [title] over the [subtitle]. [now] is the one the music is playing on
 * (peach, with the orbit round its disc); [checked] puts a check at the end and reads as selected; [chevron] says the row opens
 * something. [dimmed] fades a row that cannot be picked. A null [onClick] makes the row inert.
 */
@Composable
fun OrbitOptionRow(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    now: Boolean = false,
    checked: Boolean = false,
    chevron: Boolean = false,
    dimmed: Boolean = false,
    discContent: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    OrbitSongRow(
        title = title,
        subtitle = subtitle.orEmpty(),
        cover = null,
        fonts = fonts,
        look = look,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (dimmed) DIMMED_ALPHA else 1f)
            .then(if (checked) Modifier.semantics { selected = true } else Modifier),
        now = now,
        icon = icon ?: CosmoIcons.Devices,
        height = OPTION_ROW_HEIGHT,
        discSize = OPTION_DISC,
        discContent = discContent,
        trailing = {
            trailing?.invoke(this)
            if (checked) {
                Icon(CosmoIcons.Check, contentDescription = null, tint = if (now) look.accent else look.ink, modifier = Modifier.padding(start = 10.dp).size(20.dp))
            }
            if (chevron) {
                Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.padding(start = 10.dp).size(20.dp))
            }
        },
    )
}

/**
 * A dialog's or a sheet's pill ([OrbitPillButton]) for the screens outside this module: [primary] is the one peach action, the
 * others the quiet chip; [icon] goes before the word.
 */
@Composable
fun OrbitSheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OrbitPillButton(label, onClick, orbitFonts(), orbitLook(), modifier, primary = primary, enabled = enabled, icon = icon)
}

/** The ground of a dialog or popup card ([CosmoDialogCard]'s), for what is drawn on it to sit flush with it. */
@Composable
fun orbitPopupGround(): Color = orbitFloatGround(orbitLook())

/**
 * The card the output drawer's popups open into (the audio pipeline, the cast picker), as an Orbit dialog: the 55 % scrim that
 * closes it, the floating card at most [maxWidth] wide, the [title] as its question and the [subtitle] under it, [content]
 * scrolling (handed a [BoxScope] so a body can lay something behind its rows), and [doneLabel] the one peach action. Drawn in the
 * page's own tree rather than in a window of its own, as the player's sheets are.
 */
@Composable
fun OrbitPopupCard(
    title: String,
    subtitle: String,
    doneLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 400.dp,
    contentMaxHeight: Dp = 420.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        CosmoDialogCard(onDismiss = onDismiss, maxWidth = maxWidth) {
            CosmoDialogHeading(null, title, subtitle, Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp))
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
            CosmoDialogActions(
                confirmLabel = null,
                onConfirm = {},
                dismissLabel = doneLabel,
                onDismiss = onDismiss,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
        }
    }
}
