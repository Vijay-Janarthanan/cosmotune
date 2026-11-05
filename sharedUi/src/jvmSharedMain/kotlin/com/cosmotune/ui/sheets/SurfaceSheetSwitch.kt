package com.cosmotune.ui.sheets

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.LocalCosmoShell

/** `.scrim-dim`: the black behind a sheet, 40 % (the spec's "the glass surface over a black 40 % scrim"; Material's own is 32 %). */
val CosmoSheetScrim: Color = Color.Black.copy(alpha = 0.40f)

/**
 * The scrim for a sheet that paints itself (the song sheet's host passes a transparent container, which the widget bridge leaves to
 * Material): the spec's 40 % black ([CosmoSheetScrim]) while the new look is on, Material's default while it is off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun cosmoSheetScrim(): Color = if (LocalCosmoShell.current != null) CosmoSheetScrim else BottomSheetDefaults.ScrimColor

/**
 * The host of the album, playlist and add-to-playlist sheets: Material's `ModalBottomSheet`, with the same names, order and defaults
 * as the widget bridge's ([com.cosmotune.ui.bridge.ModalBottomSheet]). Those sheets were painted by their host (a container of the
 * theme's background, which the bridge turned into a plain detached slab); this makes them the song sheet's sheet.
 *
 * With the new look off it is the old call, every argument by name (the bridge, which is then Material's own). With it on, the host
 * paints nothing of its own (a transparent container with no corners to clip, no tonal elevation, no handle), dims the page with the
 * spec's 40 % black, and lays [content] on [CosmoSheetSurface]: detached 8 dp from the sides and the bottom, a 36 dp superellipse of
 * cover-tinted glass with its edge and shadow, the ink grabber, the rows scrolling inside. [containerColor], [shape], [tonalElevation],
 * [scrimColor] and [dragHandle] are the old look's.
 */
@ExperimentalMaterial3Api
@Composable
fun SurfaceSheetSwitch(
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
    if (LocalCosmoShell.current == null) {
        com.cosmotune.ui.bridge.ModalBottomSheet(
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
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = RectangleShape,
        containerColor = Color.Transparent,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        scrimColor = CosmoSheetScrim,
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
    ) {
        CosmoSheetSurface(content = content)
    }
}
