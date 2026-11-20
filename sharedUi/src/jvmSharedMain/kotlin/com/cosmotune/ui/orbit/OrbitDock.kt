package com.cosmotune.ui.orbit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.CapsuleGeometry
import com.cosmotune.design.ShellMetrics
import com.cosmotune.ui.components.cosmoFocusRing

/*
 * The phone's dock in Orbit (board batch-a, section 4): the small player is the desktop bar's colour card made phone-sized, play
 * standing in a round bite out of its right end ([EndBiteShape], as [OrbitBarMetrics]), and under it the tab bar, Home's chips on
 * a quiet rounded bar: the chosen tab a solid ink chip with its icon and its name, the others their icon alone.
 */

/**
 * The small player in dp: the shell's 64 dp capsule height (the pages' clearance is worked out from it), corners of 30, a 48 dp disc
 * in a 56 dp orbit 4 dp in, a 36 dp round next button, and a 28 dp bite 34 dp in from the end with the 46 dp play button in it (5 dp
 * of clear ring round the button).
 */
internal object OrbitMiniMetrics {
    const val HEIGHT_DP = ShellMetrics.CAPSULE_HEIGHT_DP
    const val RADIUS_DP = 30f
    const val PAD_START_DP = 4f
    const val RING_DP = 56f
    const val DISC_DP = 48f
    const val GAP_DP = 10f
    const val BUTTON_DP = 36f
    const val BITE_DP = 28f
    const val BITE_INSET_DP = 34f
    const val PLAY_DP = 46f
    /** The touch target of the small player's buttons and play: 48 dp round the drawn circles. */
    const val TOUCH_DP = 48f

    /** What the row keeps clear at its end: the bite and 6 dp before it. */
    const val END_PAD_DP = BITE_INSET_DP + BITE_DP + 6f

    val shape = EndBiteShape(RADIUS_DP.dp, BITE_DP.dp, BITE_INSET_DP.dp)

    /**
     * What the small player looks like to the player that grows out of it: its corners, the disc 8 dp in and centred in the height,
     * round, and the words from the end of the orbit's gap to the next button, its gap and the bite.
     */
    val geometry = CapsuleGeometry(
        cornerRadiusDp = RADIUS_DP,
        coverInsetLeftDp = PAD_START_DP + (RING_DP - DISC_DP) / 2f,
        coverInsetTopDp = (HEIGHT_DP - DISC_DP) / 2f,
        coverSizeDp = DISC_DP,
        coverRadiusDp = DISC_DP / 2f,
        textStartDp = PAD_START_DP + RING_DP + GAP_DP,
        textEndDp = END_PAD_DP + BUTTON_DP + 8f,
    )
}

/** The tab bar in dp: 60 high, round ends, 6 dp inside; a tab is a 48 dp chip. */
internal object OrbitDockMetrics {
    const val BAR_DP = 60f
    const val PAD_DP = 6f
    const val TAB_DP = 48f
    const val GAP_DP = 8f
}

/** The tab bar's ground: the chip's ink at 8 % laid over the page's ground, so it is solid over whatever scrolls under it. */
internal fun orbitDockFill(look: OrbitLook): Color = look.ink.copy(alpha = if (look.dark) 0.10f else 0.07f).compositeOver(look.ground)

/**
 * One tab of the dock: the chosen one ([selected]) a solid ink chip with its [selectedIcon] and [label] in the ground's colour, the
 * others the [icon] alone in the dim ink, named [label] for a screen reader. The chip grows into its name on a short tween. A [count]
 * (Releases' new ones) is a small peach badge on the icon, and sits after the name while the tab is chosen.
 */
@Composable
internal fun OrbitDockTab(
    label: String,
    icon: ImageVector,
    selectedIcon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    interaction: MutableInteractionSource,
    count: Int? = null,
) {
    val fill by animateColorAsState(if (selected) look.ink else Color.Transparent, tween(180), label = "tab-fill")
    val ink by animateColorAsState(if (selected) look.ground else look.dim, tween(180), label = "tab-ink")
    Row(
        modifier
            .height(OrbitDockMetrics.TAB_DP.dp)
            .widthIn(min = OrbitDockMetrics.TAB_DP.dp)
            .cosmoFocusRing(interaction, (OrbitDockMetrics.TAB_DP / 2).dp, floating = true)
            .clip(CircleShape)
            .background(fill)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .then(if (selected) Modifier else Modifier.semantics { contentDescription = orbitTabName(label, count) })
            .animateContentSize(tween(180))
            .padding(horizontal = if (selected) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(if (selected) selectedIcon else icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            if (!selected && count != null && count > 0) OrbitBadge(count, look, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp), ring = orbitDockFill(look))
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = fonts.chip, color = ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (count != null && count > 0) {
                Spacer(Modifier.width(6.dp))
                OrbitBadge(count, look, ring = null)
            }
        }
    }
}

/** What a screen reader hears for a tab with a count: "Releases, 3 new". */
internal fun orbitTabName(label: String, count: Int?): String = if (count != null && count > 0) "$label, $count new" else label

/**
 * The count badge of a tab or a rail item: 18 dp high, peach with dark ink, at least round; [ring] (the bar's fill) is a 2.5 dp ring
 * of the ground under it so it stands clear of the icon it sits on.
 */
@Composable
fun OrbitBadge(count: Int, look: OrbitLook, modifier: Modifier = Modifier, ring: Color? = null) {
    val fonts = orbitFonts()
    Box(
        modifier
            .then(if (ring != null) Modifier.background(ring, CircleShape).padding(2.5.dp) else Modifier)
            .height(18.dp).widthIn(min = 18.dp).clip(CircleShape).background(if (look.dark) PEACH else Color(0xFFE0662C))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            orbitCountText(count), style = fonts.chip.copy(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(800)),
            color = Color(0xFF2A1607), maxLines = 1,
        )
    }
}

/** A 36 dp round button of the small player, in its card's [ink], with a 48 dp touch target round it. */
@Composable
internal fun OrbitMiniButton(icon: ImageVector, label: String, ink: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OrbitRoundButton(icon, label, ink, onClick, modifier, size = OrbitMiniMetrics.BUTTON_DP.dp, touch = OrbitMiniMetrics.TOUCH_DP.dp)
}
