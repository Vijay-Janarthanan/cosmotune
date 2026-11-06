package com.cosmotune.music.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import com.cosmotune.music.wear.R
import kotlin.math.cos
import kotlin.math.sin

/*
 * Orbit on the watch (Canvas repo, docs/superpowers/plans/2026-11-06-cosmotune-orbit-revamp.md; board batch-be section E).
 *
 * The watch app is its own small app on Compose for Wear OS (Wear Material 3) and does not depend on :sharedUi — that module is
 * Compose Multiplatform with the phone's Material 3, its compose resources and the whole phone and desktop kit, far too much for a
 * watch, and its Material does not fit a round screen. So only Orbit's visual tokens are reproduced here, once: the colours (ground,
 * ink, dim, peach), the text face (Hanken Grotesk; the watch has no wide headings), the orbit ring with its moon, and the peach
 * play button. The watch is dark only, as Wear OS is.
 */

object WearOrbit {
    /** The page: Orbit's warm near-black. */
    val Ground = Color(0xFF0F0B0D)
    val Ink = Color(0xFFF7ECE4)
    val Dim = Color(0xFFB49C90)
    /** Peach: the play button, the playing row, the moon. */
    val Peach = Color(0xFFFFB98F)
    val OnPeach = Color(0xFF2A100A)
    /** A row's or a chip's fill on the watch (ink at 8 %; 5 % is too faint on a small bright screen). */
    val Row = Ink.copy(alpha = 0.08f)
    val RowPressed = Ink.copy(alpha = 0.14f)
    /** A round tonal button's fill (ink at 12 %). */
    val Round = Ink.copy(alpha = 0.12f)
    /** The ground-coloured text on solid ink (a selected chip, the primary button). */
    val OnInk = Color(0xFF1B1416)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun hanken(weight: Int) = Font(R.font.hanken_grotesk, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Orbit's text face; anything outside it (a Hindi or Japanese title) falls back to the system face glyph by glyph. */
val Hanken = FontFamily(hanken(400), hanken(500), hanken(600), hanken(700), hanken(800))

object WearType {
    val title = TextStyle(fontFamily = Hanken, fontWeight = FontWeight(800), fontSize = 15.sp, letterSpacing = (-0.2).sp)
    val subtitle = TextStyle(fontFamily = Hanken, fontWeight = FontWeight(500), fontSize = 12.sp)
    val header = TextStyle(fontFamily = Hanken, fontWeight = FontWeight(800), fontSize = 17.sp, letterSpacing = (-0.3).sp)
    val row = TextStyle(fontFamily = Hanken, fontWeight = FontWeight(700), fontSize = 14.sp, letterSpacing = (-0.1).sp)
    val rowSub = TextStyle(fontFamily = Hanken, fontWeight = FontWeight(500), fontSize = 12.sp)
    val button = TextStyle(fontFamily = Hanken, fontWeight = FontWeight(700), fontSize = 14.sp)
    val caption = TextStyle(fontFamily = Hanken, fontWeight = FontWeight(600), fontSize = 11.sp, letterSpacing = 0.2.sp)
    /** The mono label: uppercase, tracked ("PLAYING NOW"). */
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.2.sp)
}

@Composable
fun CosmotuneWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = WearOrbit.Peach,
            onPrimary = WearOrbit.OnPeach,
            background = WearOrbit.Ground,
            onBackground = WearOrbit.Ink,
            onSurface = WearOrbit.Ink,
            onSurfaceVariant = WearOrbit.Dim,
            surfaceContainer = WearOrbit.Row,
        ),
        typography = Typography(defaultFontFamily = Hanken),
        content = content,
    )
}

/**
 * The orbit (rule 2): a faint track, the part already played and a moon at the current point, on a circle [inset] in from the
 * edges of the draw area. [fraction] null draws the track alone; [spin] (degrees) turns a short arc instead, for "working".
 */
fun DrawScope.drawOrbit(fraction: Float?, color: Color, stroke: Float, inset: Float, track: Color = WearOrbit.Ink.copy(alpha = 0.2f), spin: Float? = null, moon: Boolean = true) {
    val r = size.minDimension / 2f - inset
    val topLeft = Offset(center.x - r, center.y - r)
    val arc = Size(r * 2, r * 2)
    drawCircle(track, r, center, style = Stroke(stroke))
    if (spin != null) {
        drawArc(color, spin - 90f, 70f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        return
    }
    val p = fraction?.coerceIn(0f, 1f) ?: return
    if (p * 360f > 0.5f) drawArc(color, -90f, 360f * p, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
    if (moon) {
        val a = Math.toRadians(360.0 * p - 90.0)
        drawCircle(color, stroke * 1.5f, center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
    }
}

/** A disc's orbit as a composable, for a row's playing disc. */
@Composable
fun WearOrbitRing(size: Dp, fraction: Float?, modifier: Modifier = Modifier, color: Color = WearOrbit.Peach, stroke: Dp = size * 0.04f) {
    Canvas(modifier.size(size)) { drawOrbit(fraction, color, stroke.toPx(), stroke.toPx() * 1.6f) }
}

/** The play / pause button: peach with the dark glyph, dipping when pressed. */
@Composable
fun WearPlayButton(playing: Boolean, size: Dp, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .graphicsLayer { val s = if (pressed) 0.92f else 1f; scaleX = s; scaleY = s }
            .clip(CircleShape)
            .background(WearOrbit.Peach)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = description,
            tint = WearOrbit.OnPeach,
            modifier = Modifier.size(size * 0.4f),
        )
    }
}
