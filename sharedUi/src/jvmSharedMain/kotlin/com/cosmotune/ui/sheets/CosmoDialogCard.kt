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
 * overline, the question, a line under it and one peach action.
 */
@Composable
fun CosmoDialogCard(
    onDismiss: () -> Unit,
    maxWidth: Dp,
    hazeState: HazeState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close = stringResource(Res.string.cosmo_close)
    val look = orbitLook()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
 * [message] under it in the dim ink, and the buttons at the end: [dismissLabel] quiet, [confirmLabel] the one peach action.
 */
@Composable
fun CosmoDialogBody(
    overline: String?,
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
        if (overline != null) OrbitOverline(overline, fonts, look, Modifier.padding(bottom = 10.dp))
        Text(title, style = fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 26.sp), color = look.ink, modifier = Modifier.semantics { heading() })
        if (!message.isNullOrBlank()) {
            Text(message, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 10.dp))
        }
        extra?.invoke(this)
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            if (dismissLabel != null) OrbitPillButton(dismissLabel, onDismiss, fonts, look)
            OrbitPillButton(confirmLabel, onConfirm, fonts, look, primary = true, enabled = confirmEnabled)
        }
    }
}
