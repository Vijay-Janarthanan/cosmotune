package com.cosmotune.ui.orbit

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cosmotune.design.CapsuleGeometry
import com.cosmotune.design.PlayerBarMetrics.Plan
import com.cosmotune.design.PlayerBarMetrics.Tier
import com.cosmotune.design.ShellMetrics

/*
 * The Orbit player bar's pieces (board v2, section 6): the bar is Home's colour card made slim, with play standing in a round bite
 * cut out of its end, centred in its height (Orbit rule 3: "bottom-end, or right-end for a bar").
 */

/** A rounded bar with a round bite out of its end edge: the bite's centre is [inset] in from that edge, half way down. */
internal class EndBiteShape(private val corner: Dp, private val bite: Dp, private val inset: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val card = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner.toPx()))) }
        val x = if (layoutDirection == LayoutDirection.Ltr) size.width - inset.toPx() else inset.toPx()
        val hole = Path().apply { addOval(Rect(Offset(x, size.height / 2f), bite.toPx())) }
        Outline.Generic(Path.combine(PathOperation.Difference, card, hole))
    }
}

/**
 * The desktop player bar in Orbit, in dp. It keeps the shell's 80 dp height (the page clearance and the side column are worked out
 * from [ShellMetrics.DESKTOP_BAR_HEIGHT_DP]); the rest is the board's: corners of 30, a 52 dp disc in a 64 dp orbit, 36 dp round
 * buttons, and a 34 dp bite 40 dp in from the end with the 54 dp play button in it (7 dp of clear ring round the button, as the
 * board's 35 / 56).
 *
 * A narrower bar (a window with the lyrics or queue column open) drops things in the old bar's order ([Tier]): the volume, then
 * shuffle and repeat, then the middle column (the orbit round the disc is the progress then) with like and output. Lyrics and queue
 * stay in every plan: they put the column away again.
 */
internal object OrbitBarMetrics {
    const val HEIGHT_DP = ShellMetrics.DESKTOP_BAR_HEIGHT_DP
    const val RADIUS_DP = 30f
    const val PAD_START_DP = 8f
    const val RING_DP = 64f
    const val DISC_DP = 52f
    const val GAP_DP = 14f
    const val BUTTON_DP = 36f
    const val BUTTON_GAP_DP = 4f
    const val BITE_DP = 34f
    const val BITE_INSET_DP = 40f
    const val PLAY_DP = 54f

    /** What the row keeps clear at its end: the bite and 8 dp before it. */
    const val END_PAD_DP = BITE_INSET_DP + BITE_DP + 8f
    const val CLOCK_DP = 84f
    const val CLOCK_GAP_DP = 10f
    const val TOOLS_MIN_GAP_DP = 16f
    const val VOLUME_DP = 92f
    const val VOLUME_GAP_DP = 8f
    const val VOLUME_ICON_DP = 16f
    const val MINIMAL_TEXT_DP = 96f

    val shape = EndBiteShape(RADIUS_DP.dp, BITE_DP.dp, BITE_INSET_DP.dp)

    private val full = Plan(Tier.FULL, 220f, like = true, shuffleRepeat = true, scrubber = true, output = true, volume = true)
    private val medium = Plan(Tier.MEDIUM, 160f, like = true, shuffleRepeat = true, scrubber = true, output = true, volume = false)
    private val compact = Plan(Tier.COMPACT, 120f, like = true, shuffleRepeat = false, scrubber = true, output = true, volume = false)
    private val minimal = Plan(Tier.MINIMAL, MINIMAL_TEXT_DP, like = false, shuffleRepeat = false, scrubber = false, output = false, volume = false)

    fun planFor(tier: Tier): Plan = when (tier) {
        Tier.FULL -> full
        Tier.MEDIUM -> medium
        Tier.COMPACT -> compact
        Tier.MINIMAL -> minimal
    }

    /** The richest plan that fits in a bar [widthDp] wide; the narrowest one when none does. */
    fun plan(widthDp: Float): Plan = Tier.entries.map(::planFor).firstOrNull { requiredDp(it) <= widthDp } ?: minimal

    private fun buttons(n: Int) = n * BUTTON_DP + (n - 1).coerceAtLeast(0) * BUTTON_GAP_DP

    /** The least width [plan] fits in: the disc and the words, like, the middle column (or the tools alone), and the bite. */
    fun requiredDp(plan: Plan): Float {
        val left = PAD_START_DP + RING_DP + GAP_DP + plan.textDp + if (plan.like) GAP_DP + BUTTON_DP else 0f
        val tools = buttons(if (plan.output) 3 else 2) + if (plan.volume) VOLUME_GAP_DP + VOLUME_ICON_DP + VOLUME_GAP_DP + VOLUME_DP else 0f
        val middle = if (plan.scrubber) buttons(if (plan.shuffleRepeat) 4 else 2) + CLOCK_GAP_DP + CLOCK_DP + TOOLS_MIN_GAP_DP + tools else tools
        return left + GAP_DP + middle + END_PAD_DP
    }

    /**
     * What the bar looks like to the player that grows out of it: its corners, the disc 14 dp in and centred in the height, round,
     * and the words from the end of the orbit's gap to the column's own width (in [Tier.MINIMAL], up to the lyrics and queue
     * buttons and the bite).
     */
    fun geometry(plan: Plan, widthDp: Float): CapsuleGeometry {
        val textStart = PAD_START_DP + RING_DP + GAP_DP
        val textEnd = if (plan.tier == Tier.MINIMAL) END_PAD_DP + buttons(2) + GAP_DP else (widthDp - textStart - plan.textDp).coerceAtLeast(0f)
        return CapsuleGeometry(
            cornerRadiusDp = RADIUS_DP,
            coverInsetLeftDp = PAD_START_DP + (RING_DP - DISC_DP) / 2f,
            coverInsetTopDp = (HEIGHT_DP - DISC_DP) / 2f,
            coverSizeDp = DISC_DP,
            coverRadiusDp = DISC_DP / 2f,
            textStartDp = textStart,
            textEndDp = textEnd,
        )
    }
}
