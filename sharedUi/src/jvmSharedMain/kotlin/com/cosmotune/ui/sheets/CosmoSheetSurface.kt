package com.cosmotune.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.bridge.CosmoSheetHandle
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.components.trackFocusModality
import com.cosmotune.ui.theme.CosmoRadii

/** `--r-sheet`: 36 dp on every corner. */
private val SHEET_SHAPE = SuperellipseShape(CosmoRadii.sheet)

/** The old `TintedSheet` rule, kept: the whole sheet is at most 85 % of the window high, and the rows scroll inside it. */
internal const val SHEET_MAX_HEIGHT_SHARE = 0.85f

/**
 * The surface of the song and album sheets (`.sheet2`, layer C): a detached card, [CosmoRadii.sheetInset] (8 dp) in from the sides
 * and above the bottom edge, a 36 dp superellipse of the solid glass (`cosmoGlass` with no [dev.chrisbanes.haze.HazeState]: a sheet
 * opens in its own window, so there is nothing behind it to blur, and a third blurred layer would break the glass budget; see E7),
 * the ink grabber on top ([CosmoSheetHandle]), and the rows scrolling below it. The sheet and the grabber are 85 % of the window
 * high at most.
 *
 * The host (Material's `ModalBottomSheet` with a transparent container, which the widget bridge leaves alone) pads its content with
 * the system bars, so the card floats 8 dp above the navigation bar's own inset, not on it. The sheet draws itself: the scrim, the
 * drag and the dismissal stay the host's.
 */
@Composable
fun CosmoSheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(
        // The sheet's window does not see the main window's keys: the rows' rings follow its own.
        modifier
            .trackFocusModality()
            .fillMaxWidth()
            .heightIn(max = windowHeight * SHEET_MAX_HEIGHT_SHARE)
            .padding(start = CosmoRadii.sheetInset, end = CosmoRadii.sheetInset, bottom = CosmoRadii.sheetInset)
            .cosmoGlass(hazeState = null, shape = SHEET_SHAPE),
    ) {
        // Outside the scrolling rows, so it stays put as the grab target.
        CosmoSheetHandle(Modifier.align(Alignment.CenterHorizontally))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}
