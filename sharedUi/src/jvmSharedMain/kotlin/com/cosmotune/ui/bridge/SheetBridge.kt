package com.cosmotune.ui.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.GlassPolicy
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_sheet_handle
import org.jetbrains.compose.resources.stringResource

/** `--r-sheet`. */
private val SHEET_SHAPE = SuperellipseShape(CosmoRadii.sheet)

/** `.scrim-dim`: the black behind a detached sheet. */
private val SHEET_SCRIM = Color.Black.copy(alpha = 0.40f)

/** `.sheet2 .grab`: 36 x 4 dp, in `textPrimary` at 45 %, 10 dp from the sheet's top edge and 14 dp above the content. */
private val GRAB_WIDTH = 36.dp
private val GRAB_HEIGHT = 4.dp
private const val GRAB_ALPHA = 0.45f
private val GRAB_TOP = 10.dp
private val GRAB_BOTTOM = 14.dp

/**
 * Material's `ModalBottomSheet` with the same names, order and defaults (material3 1.3.x). With the new look off it is Material's
 * own. With it on, a sheet that paints itself (the caller passed `containerColor = Color.Transparent`: the song and browse
 * sheets, the player) is Material's untouched. Any other sheet is detached glass: 8 dp in from the sides and the bottom
 * ([CosmoRadii.sheetInset], `.sheet2 { left/right/bottom: var(--sheet-inset) }`), a 36 dp superellipse, `surfaceHigh` at [GlassPolicy.SOLID_ALPHA], a 40 % black scrim, no tonal elevation and the
 * [CosmoSheetHandle] grabber in place of the Material handle (a handle the caller turned off with `null` stays off).
 */
@ExperimentalMaterial3Api
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalCosmoShell.current == null || containerColor == Color.Transparent) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            sheetMaxWidth = sheetMaxWidth,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            content = content,
        )
        return
    }
    val glass = Color(LocalCosmoColors.current.palette.surfaceHigh).copy(alpha = GlassPolicy.SOLID_ALPHA)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.padding(start = CosmoRadii.sheetInset, end = CosmoRadii.sheetInset, bottom = CosmoRadii.sheetInset),
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = SHEET_SHAPE,
        containerColor = glass,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        scrimColor = SHEET_SCRIM,
        dragHandle = if (dragHandle == null) null else { { CosmoSheetHandle() } },
        contentWindowInsets = contentWindowInsets,
        properties = properties,
        content = content,
    )
}

/**
 * The grabber of a detached sheet (`.sheet2 .grab`): a 36 x 4 dp pill in `textPrimary` at 45 %, 10 dp below the sheet's top edge
 * and 14 dp above its content. The sheet centres it and gives it the "drag handle" role and actions; the label is `cosmo_sheet_handle`.
 */
@Composable
fun CosmoSheetHandle(modifier: Modifier = Modifier) {
    val ink = Color(LocalCosmoColors.current.palette.textPrimary).copy(alpha = GRAB_ALPHA)
    val label = stringResource(Res.string.cosmo_sheet_handle)
    Box(modifier.padding(top = GRAB_TOP, bottom = GRAB_BOTTOM).size(GRAB_WIDTH, GRAB_HEIGHT).background(ink, CircleShape).semantics { contentDescription = label })
}
