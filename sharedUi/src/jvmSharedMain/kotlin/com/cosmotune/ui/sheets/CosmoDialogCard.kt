package com.cosmotune.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_close
import com.cosmotune.ui.orbit.OrbitOverline
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.orbitFloatEdge
import com.cosmotune.ui.orbit.orbitFloatGround
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/** The black behind a dialog: 55 % (the board's sheet scrim). */
private val DIALOG_SCRIM = Color.Black.copy(alpha = 0.55f)

/** A dialog's corners (the board's `.dialog`: radius 32). */
private val DIALOG_SHAPE = RoundedCornerShape(32.dp)

/**
 * The dialog in Orbit (board batch-a, section 4): a 55 % black scrim that closes the dialog when it is clicked, and over it a dark
 * card at most [maxWidth] wide, radius 32, the floating ground ([orbitFloatGround]) with a 1 dp edge and a deep shadow. Flat (rule
 * 4): [hazeState] is kept for the callers and blurs nothing. A click on the card's own background goes nowhere, so it does not reach
 * the scrim and close what was just clicked; the rows' own clicks are untouched. [CosmoDialogBody] is the usual content: a mono
 * overline, the question, a line under it and one peach action. [scrim] false leaves the scrim to the dialog window the card stands
 * in ([CosmoDialog]), which dims and closes on a click outside by itself.
 */
@Composable
fun CosmoDialogCard(
    onDismiss: () -> Unit,
    maxWidth: Dp,
    hazeState: HazeState? = null,
    scrim: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close = stringResource(Res.string.cosmo_close)
    val look = orbitLook()
    Box(if (scrim) Modifier.fillMaxSize() else Modifier, contentAlignment = Alignment.Center) {
        if (scrim) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DIALOG_SCRIM)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = close,
                        role = Role.Button,
                        onClick = onDismiss,
                    ),
            )
        }
        Box(
            Modifier
                .padding(16.dp)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .shadow(30.dp, DIALOG_SHAPE, clip = false)
                .clip(DIALOG_SHAPE)
                .background(orbitFloatGround(look))
                .border(1.dp, orbitFloatEdge(look), DIALOG_SHAPE),
        ) {
            // Behind the content rather than around it, so it swallows clicks on the card's own background without eating the rows' own.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            )
            Column(Modifier.padding(bottom = 8.dp), content = content)
        }
    }
}

/**
 * The usual inside of a [CosmoDialogCard]: the mono [overline] ("Delete download"), the [title] as the question in the wide face,
 * [message] under it in the dim ink, [extra] (a field, a list) and the buttons at the end: [dismissLabel] quiet, [confirmLabel] the
 * one peach action. With no [confirmLabel] the dismiss is the only action and the peach one. [busy] puts a small spinner before the
 * buttons and holds the dismiss. [extra] takes the room that is left on a short window and scrolls when [scroll] (false for content
 * that scrolls itself, a lazy list say).
 */
@Composable
fun CosmoDialogBody(
    overline: String?,
    title: String,
    message: String?,
    confirmLabel: String?,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    busy: Boolean = false,
    scroll: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
        CosmoDialogHeading(overline, title, message)
        if (extra != null) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .padding(top = 14.dp)
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            ) {
                CompositionLocalProvider(LocalContentColor provides look.ink, LocalTextStyle provides fonts.body) { extra() }
            }
        }
        CosmoDialogActions(confirmLabel, onConfirm, dismissLabel, onDismiss, confirmEnabled, dismissEnabled, busy)
    }
}

/** The head of a dialog: the mono [overline], the [title] as the question in the wide face and the [message] under it in the dim ink. */
@Composable
fun CosmoDialogHeading(
    overline: String?,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    messageIsError: Boolean = false,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth()) {
        if (overline != null) OrbitOverline(overline, fonts, look, Modifier.padding(bottom = 10.dp))
        Text(title, style = fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 26.sp), color = look.ink, modifier = Modifier.semantics { heading() })
        if (!message.isNullOrBlank()) {
            Text(
                message,
                style = fonts.body,
                color = if (messageIsError) MaterialTheme.colorScheme.error else look.dim,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * The buttons at the end of a dialog, 22 dp under what is above them: [dismissLabel] quiet, [confirmLabel] the one peach action (with
 * no [confirmLabel], the dismiss is the peach one). [busy] puts a small spinner before them and holds the dismiss.
 */
@Composable
fun CosmoDialogActions(
    confirmLabel: String?,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().padding(top = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(color = look.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        if (confirmLabel == null) {
            if (dismissLabel != null) OrbitPillButton(dismissLabel, onDismiss, fonts, look, primary = true, enabled = dismissEnabled && !busy)
        } else {
            if (dismissLabel != null) OrbitPillButton(dismissLabel, onDismiss, fonts, look, enabled = dismissEnabled && !busy)
            OrbitPillButton(confirmLabel, onConfirm, fonts, look, primary = true, enabled = confirmEnabled)
        }
    }
}

/**
 * A dialog in a window of its own (Android's dialog window, the desktop's dialog layer), for a caller anywhere in the tree: the
 * window dims and closes on a click outside, and the [CosmoDialogCard] (without a scrim of its own) stands in it, at most [maxWidth].
 */
@Composable
fun CosmoDialog(onDismiss: () -> Unit, maxWidth: Dp = 460.dp, properties: DialogProperties? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = properties ?: DialogProperties(usePlatformDefaultWidth = false)) {
        CosmoDialogCard(onDismiss = onDismiss, maxWidth = maxWidth, scrim = false, content = content)
    }
}

/**
 * Material's alert in Orbit: a [CosmoDialog] holding a [CosmoDialogBody], the [title] as the question, the [message] or the [content]
 * under it and the buttons by their labels. [onDismissRequest] is a click outside or Back; [onDismiss] the quiet button (the same
 * unless said otherwise).
 */
@Composable
fun CosmoAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    dismissEnabled: Boolean = true,
    overline: String? = null,
    message: String? = null,
    busy: Boolean = false,
    scroll: Boolean = true,
    maxWidth: Dp = 460.dp,
    properties: DialogProperties? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    CosmoDialog(onDismiss = onDismissRequest, maxWidth = maxWidth, properties = properties?.let {
        DialogProperties(dismissOnBackPress = it.dismissOnBackPress, dismissOnClickOutside = it.dismissOnClickOutside, usePlatformDefaultWidth = false)
    }) {
        CosmoDialogBody(
            overline = overline,
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            dismissLabel = dismissLabel,
            onDismiss = onDismiss,
            confirmEnabled = confirmEnabled,
            dismissEnabled = dismissEnabled,
            busy = busy,
            scroll = scroll,
            extra = content,
        )
    }
}

/**
 * A further, quiet action of a dialog that has more than its two (Test beside Save, Remove beside Cancel, Retry): the chip's pill in
 * the ink, laid in the dialog's content rather than among its buttons, so there stays one peach action.
 */
@Composable
fun CosmoDialogQuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OrbitPillButton(label, onClick, orbitFonts(), orbitLook(), modifier, enabled = enabled)
}
