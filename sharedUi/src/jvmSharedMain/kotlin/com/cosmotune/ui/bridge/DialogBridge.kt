package com.cosmotune.ui.bridge

import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.design.GlassPolicy
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell

/** `--r-sheet`: a dialog has the sheet's corners. */
private val DIALOG_SHAPE = SuperellipseShape(CosmoRadii.sheet)

/**
 * Material's `AlertDialog` (the overload with slots, material3 1.3.x) with the same names, order and defaults. With the new look
 * off it is Material's own. With it on the dialog is a 36 dp superellipse of solid tinted glass (`surfaceHigh` at
 * [GlassPolicy.SOLID_ALPHA]: a dialog is its own window, so there is nothing to blur), with no tonal elevation, an `accent` icon,
 * a `textPrimary` title and `textSecondary` text. [shape], [containerColor], [iconContentColor], [titleContentColor],
 * [textContentColor] and [tonalElevation] are the look's then; the title's type comes from the theme (`headlineSmall`).
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    if (LocalCosmoShell.current == null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties,
        )
        return
    }
    val palette = LocalCosmoColors.current.palette
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = DIALOG_SHAPE,
        containerColor = Color(palette.surfaceHigh).copy(alpha = GlassPolicy.SOLID_ALPHA),
        iconContentColor = Color(palette.accent),
        titleContentColor = Color(palette.textPrimary),
        textContentColor = Color(palette.textSecondary),
        tonalElevation = 0.dp,
        properties = properties,
    )
}
