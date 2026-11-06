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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.bridge.CosmoSheetHandle
import com.cosmotune.ui.components.trackFocusModality
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitSheetGround

/** The sheet's top corners (board batch-a, section 4: `border-radius: 32px 32px 0 0`). */
internal val SHEET_CORNER = 32.dp

/** The sheet's padding at the sides and under its last row. */
private val SHEET_PADDING_H = 10.dp
private val SHEET_PADDING_BOTTOM = 22.dp

/** How far the ground runs on under the sheet, so it reaches the window's foot under the navigation bar the host pads for. */
private val SHEET_GROUND_RUN = 96.dp

/** The old `TintedSheet` rule, kept: the whole sheet is at most 85 % of the window high, and the rows scroll inside it. */
internal const val SHEET_MAX_HEIGHT_SHARE = 0.85f

/**
 * The surface of the song and album sheets in Orbit (board batch-a, section 4): a flat sheet of its own ground ([orbitSheetGround]:
 * no glass, rule 4), standing on the bottom of the window with 32 dp top corners, the grabber on top ([CosmoSheetHandle]) and the
 * rows scrolling below it. The sheet is 85 % of the window high at most.
 *
 * The host (Material's `ModalBottomSheet` with a transparent container) pads its content with the system bars; the ground is drawn
 * on past the sheet's foot ([SHEET_GROUND_RUN]) so it runs under the navigation bar to the window's edge instead of leaving a strip
 * of scrim there. The scrim, the drag and the dismissal stay the host's.
 */
@Composable
fun CosmoSheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val ground = orbitSheetGround(orbitLook())
    Column(
        // The sheet's window does not see the main window's keys: the rows' rings follow its own.
        modifier
            .trackFocusModality()
            .fillMaxWidth()
            .heightIn(max = windowHeight * SHEET_MAX_HEIGHT_SHARE)
            .drawBehind {
                val corner = SHEET_CORNER.toPx()
                drawRoundRect(ground, Offset.Zero, Size(size.width, size.height + SHEET_GROUND_RUN.toPx()), CornerRadius(corner))
            }
            .padding(start = SHEET_PADDING_H, end = SHEET_PADDING_H, bottom = SHEET_PADDING_BOTTOM),
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
