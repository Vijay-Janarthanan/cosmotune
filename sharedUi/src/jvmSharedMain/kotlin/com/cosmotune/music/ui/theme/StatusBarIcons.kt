package com.cosmotune.music.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Draws just the status bar glyphs dark or light, leaving the navigation bar
 * as the page underneath set it. Nothing on a platform without one.
 */
@Composable
expect fun StatusBarIcons(dark: Boolean)

/**
 * Draws the status and the navigation bar glyphs dark ([dark]) or light, both: the bars of a page that paints its own ground under
 * both. Nothing on a platform without them.
 */
@Composable
expect fun SystemBarGlyphs(dark: Boolean)

/**
 * [SystemBarGlyphs] for a page whose ground (what the bars stand on) is [ground]: dark glyphs on a light ground, light ones on a dark
 * ground. Every mode reads its own ground, so a dark mode in a light theme (or the other way round) never leaves its icons unseen.
 */
@Composable
fun SystemBarGlyphsOn(ground: Color) = SystemBarGlyphs(dark = groundWantsDarkGlyphs(ground))

/** Whether glyphs on [ground] should be dark: the ground is lighter than the middle grey. */
fun groundWantsDarkGlyphs(ground: Color): Boolean = ground.luminance() > 0.35f

/**
 * Takes the status bar off the window this is composed in while [hidden], and
 * puts it back when this leaves composition. A swipe down from the top edge
 * still shows it for a moment. Nothing on a platform without one.
 */
@Composable
expect fun HideStatusBar(hidden: Boolean)

/**
 * The status bar's insets whether it is showing or not. Anything laid out
 * under it reads this rather than [WindowInsets.Companion.statusBars], which
 * drops to zero the moment [HideStatusBar] hides it — the content would jump
 * up into the camera cutout instead of the icons simply going away.
 */
@Composable
expect fun stableStatusBarInsets(): WindowInsets
