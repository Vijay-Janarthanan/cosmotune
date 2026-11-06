package com.cosmotune.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.orbitFloatEdge
import com.cosmotune.ui.orbit.orbitFloatGround
import com.cosmotune.ui.orbit.orbitLook

/** The menu's corners: [com.cosmotune.ui.orbit.OrbitMenu]'s 24. */
private val MENU_SHAPE = RoundedCornerShape(24.dp)

/** Inside the edge: 8 dp, as OrbitMenu. */
private val MENU_PADDING = 8.dp

/**
 * The card of a menu in Orbit (board batch-a, section 4; the same card as [com.cosmotune.ui.orbit.OrbitMenu]): radius 24, the
 * floating ground of its own ([orbitFloatGround]), a 1 dp edge of the ink at 8 % and a soft shadow, 8 dp inside. The rows
 * ([CosmoActionRow] with `menu = true`) and the groups' rules ([CosmoSheetGap]) go inside.
 *
 * Put it where the old card's own background, clip and border were: it draws the shadow, the fill and the edge itself, and clips
 * what is inside it.
 */
@Composable
fun Modifier.cosmoMenuSurface(): Modifier {
    val look = orbitLook()
    return this
        .shadow(24.dp, MENU_SHAPE, clip = false)
        .clip(MENU_SHAPE)
        .background(orbitFloatGround(look))
        .border(1.dp, orbitFloatEdge(look), MENU_SHAPE)
        .padding(MENU_PADDING)
}
