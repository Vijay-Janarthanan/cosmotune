package com.cosmotune.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.SpringValues
import com.cosmotune.design.Springs

/** The 4 dp spacing scale (`--s1` to `--s12` of `tokens-v3bc.css`), the page margin and the grid gutter. */
object CosmoSpacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s7 = 32.dp
    val s8 = 40.dp
    val s9 = 48.dp
    val s10 = 64.dp
    val s11 = 80.dp
    val s12 = 96.dp

    /** Side margin of every page, and the gap between grid columns. */
    val margin = 16.dp
    val gutter = 12.dp
}

/**
 * Corner radii. `xs` to `xxl` are `--r-xs` to `--r-2xl`; tile, card and sheet are the B+C tokens (`--r-tile`, `--r-card`,
 * `--r-sheet`). Controls take their radius from their own height (`ControlMorph`), not from here.
 *
 * The cover radii are the ones the approved screens draw. The designer's rule `clamp(8, 0.12 x side, 28)` (`--r-cover-k`,
 * [coverByFormula]) disagrees with the screens below about 100 dp (the mini player's 52 dp cover is 14 in the screen, 8 by
 * the formula), and the screens win.
 */
object CosmoRadii {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 28.dp
    val xxl = 32.dp

    val tile = 28.dp
    val card = 24.dp
    val sheet = 36.dp

    /** A bottom sheet or dialog is detached: this far in from the screen edge. */
    val sheetInset = 8.dp

    val coverNowPlaying = 28.dp
    val coverMini = 14.dp
    val coverChip = 9.dp
    val coverRow = 12.dp
    val coverDesktopBar = 12.dp
    val coverDesktopHead = 14.dp

    // ---- The One radii (DESIGN_SYSTEM.md 3.4); new code takes these, not literals. ----

    /** Full-bleed video, reader pages, dividers. */
    val r0 = 0.dp
    /** Progress tracks, tiny thumbs (40 dp and under), badges inside pills. */
    val rXs = 4.dp
    /** Covers of 40 to 64 dp in rows, every 2:3 portrait cover, 16:9 row thumbs. */
    val rSm = 8.dp
    /** Square covers over 64 dp, 16:9 cards, tiles, inline banners, menus, settings cards. */
    val rMd = 12.dp
    /** The dock, the desktop player bar, the search field. */
    val rLg = 20.dp
    /** Bottom sheets' top corners, dialogs. */
    val rXl = 28.dp

    /** `clamp(8, 0.12 x side, 28)`: for covers the screens do not draw. */
    fun coverByFormula(side: Dp): Dp = (side * 0.12f).coerceIn(xs, xl)
}

/**
 * The One motion tokens (DESIGN_SYSTEM.md 3.6): durations and easings, each a 120 ms cross-fade (or nothing) under reduced motion.
 */
object OneMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val FAST_MS = 100
    const val SHORT_MS = 150
    const val MEDIUM_MS = 220
    const val LONG_MS = 300
    const val XL_MS = 400
    const val REDUCED_MS = 120

    /** Press states, ripples. */
    fun <T> fast(reduced: Boolean): AnimationSpec<T> = tween(if (reduced) REDUCED_MS else FAST_MS, easing = Standard)
    /** Fades, a chip's selection, an icon swap. */
    fun <T> short(reduced: Boolean): AnimationSpec<T> = tween(if (reduced) REDUCED_MS else SHORT_MS, easing = Standard)
    /** A tab change, the mode switch, a snackbar (entering). */
    fun <T> medium(reduced: Boolean): AnimationSpec<T> = tween(if (reduced) REDUCED_MS else MEDIUM_MS, easing = EmphasizedDecelerate)
    /** A sheet opening, the top bar settling, a page push. */
    fun <T> long(reduced: Boolean): AnimationSpec<T> = tween(if (reduced) REDUCED_MS else LONG_MS, easing = EmphasizedDecelerate)
    /** The mini player growing into Now Playing; the palette cross-fade (which snaps under reduced motion). */
    fun <T> xl(reduced: Boolean): AnimationSpec<T> = tween(if (reduced) 0 else XL_MS, easing = EmphasizedDecelerate)
}

/**
 * The app's springs and the palette fade as Compose specs. Every one turns into a short fade when `reduced` is true
 * ("Reduce animation" or the system animator scale at 0).
 */
object CosmoMotion {
    private fun <T> spring(s: SpringValues, reduced: Boolean): AnimationSpec<T> =
        if (reduced) tween(Springs.REDUCED_MS) else androidx.compose.animation.core.spring(dampingRatio = s.dampingRatio, stiffness = s.stiffness)

    /** Press, toggle, shape morph: stiffness 800, damping 0.6. */
    fun <T> press(reduced: Boolean): AnimationSpec<T> = spring(Springs.PRESS, reduced)

    /** Sheets and the mini player's growth: stiffness 380, damping 0.8. */
    fun <T> move(reduced: Boolean): AnimationSpec<T> = spring(Springs.MOVE, reduced)

    /** Track change (title, artwork): stiffness 200, damping 0.8. */
    fun <T> slow(reduced: Boolean): AnimationSpec<T> = spring(Springs.SLOW, reduced)

    /** Colour and opacity of a control: stiffness 1600, critically damped. */
    fun <T> effects(reduced: Boolean): AnimationSpec<T> = spring(Springs.EFFECTS, reduced)

    /** The whole-palette cross-fade when the cover changes: 700 ms on `cubic-bezier(0.2, 0, 0, 1)`. */
    fun palette(reduced: Boolean): AnimationSpec<Float> =
        tween(if (reduced) Springs.REDUCED_MS else Springs.PALETTE_MS, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
}
