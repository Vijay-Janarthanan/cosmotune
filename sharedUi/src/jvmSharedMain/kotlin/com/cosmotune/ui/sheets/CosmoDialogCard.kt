package com.cosmotune.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_close
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/** `.scrim-dim`: the black behind a sheet or a dialog, 40 %. */
private val DIALOG_SCRIM = Color.Black.copy(alpha = 0.40f)

/** `--r-sheet`: a dialog has the sheet's corners. */
private val DIALOG_SHAPE = SuperellipseShape(CosmoRadii.sheet)

/**
 * The dialog of the desktop (layer C): a 40 % black scrim that closes the dialog when it is clicked, and over it a card at most
 * [maxWidth] wide (the width the old card had), a 36 dp superellipse of the glass. With a [hazeState] (the desktop's window
 * backdrop; an in-window layer may blur, E7) the glass blurs the page behind it, as far as the glass policy allows; without one
 * it is the solid tint. A click on the card's own background goes nowhere, so it does not reach the scrim and close what was
 * just clicked; the rows' own clicks are untouched.
 */
@Composable
fun CosmoDialogCard(
    onDismiss: () -> Unit,
    maxWidth: Dp,
    hazeState: HazeState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close = stringResource(Res.string.cosmo_close)
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
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .cosmoGlass(hazeState = hazeState, shape = DIALOG_SHAPE),
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
            Column(Modifier.padding(bottom = CosmoSpacing.s1), content = content)
        }
    }
}
