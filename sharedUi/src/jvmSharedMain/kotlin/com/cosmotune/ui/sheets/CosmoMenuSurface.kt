package com.cosmotune.ui.sheets

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoRadii

/** `.menu3 { border-radius: 22px }`. */
private val MENU_SHAPE = SuperellipseShape(CosmoRadii.lg)

/** `.menu3 { padding: 6px }`. */
private val MENU_PADDING = 6.dp

/**
 * The card of a menu (`.menu3`, layer C): a 22 dp superellipse of the solid glass (`cosmoGlass` with no backdrop: a menu opens
 * over the shell's two blurred layers, so it is the third and does not blur) with 6 dp of padding inside it. The rows
 * ([CosmoActionRow] with `menu = true`) and the groups' rules ([CosmoSheetGap]) go inside.
 *
 * Put it where the old card's own background, clip and border were: it draws the shadow, the fill, the edge and the cover tint
 * itself, and clips what is inside it.
 */
@Composable
fun Modifier.cosmoMenuSurface(): Modifier = this
    .cosmoGlass(hazeState = null, shape = MENU_SHAPE)
    .padding(MENU_PADDING)
