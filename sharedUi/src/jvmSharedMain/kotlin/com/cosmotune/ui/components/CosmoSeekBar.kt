package com.cosmotune.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.cosmotune.design.SliderMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_seek_bar
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The seek bar of Now Playing and the player bar (DESIGN_SYSTEM.md 4.18): a straight 4 dp track in the accent up to the playhead, the
 * rest a quiet line, with a 12 dp thumb only while it is being dragged.
 *
 * - [fraction] is read only while drawing, so the position moving does not recompose anything.
 * - [onScrub] gets the fraction while the pointer moves (a tap, a drag, a key); [onRelease] when it lets go: the caller seeks then.
 * - The touch area is 48 dp tall. The bar has a name, a range and a progress action for screen readers, and takes the arrow keys, Page
 *   Up and Down, Home and End on the desktop, with a visible focus ring.
 */
@Composable
fun CosmoSeekBar(
    fraction: () -> Float,
    onScrub: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCosmoColors.current
    val label = stringResource(Res.string.cosmo_seek_bar)

    // The position for screen readers, in steps of five percent: the semantics change twenty times over a song, not every frame.
    var announced by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { snapshotFlow { (fraction().coerceIn(0f, 1f) * 20).roundToInt() }.collect { announced = it } }
    val announcedNow = announced

    var dragging by remember { mutableStateOf(false) }
    val scrub: (Float) -> Unit = { dragging = true; onScrub(it) }
    val release: () -> Unit = { dragging = false; onRelease() }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .height(SliderMetrics.touchHeightDp(TRACK_DP).dp)
            .scrubKeys({ fraction() }, onScrub, onRelease)
            .focusable(interactionSource = interaction)
            .cosmoFocusRing(interaction, radius = 12.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(announcedNow / 20f, 0f..1f)
                setProgress { target -> onScrub(target.coerceIn(0f, 1f)); onRelease(); true }
            }
            .scrubGestures(scrub, release),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.height / 2f
            val stroke = TRACK_DP.dp.toPx()
            val cap = stroke / 2f
            val at = cap + (size.width - stroke) * fraction().coerceIn(0f, 1f)
            val ink = colors.live(CoverField.TEXT_PRIMARY)
            drawLine(ink.copy(alpha = SliderMetrics.trackAlpha(colors.dark)), Offset(cap, mid), Offset(size.width - cap, mid), stroke, StrokeCap.Round)
            val accent = colors.live(CoverField.ACCENT)
            drawLine(accent, Offset(cap, mid), Offset(at, mid), stroke, StrokeCap.Round)
            if (dragging) drawCircle(accent, THUMB_DP.dp.toPx() / 2f, Offset(at, mid))
        }
    }
}

private const val TRACK_DP = 4f
private const val THUMB_DP = 12f
