package com.cosmotune.design

import kotlin.math.round

/**
 * The corner sizes of the Material theme in the new look, in dp. Material's `Shapes` takes corner-based (circular) shapes
 * only, and "a superellipse corner reads smaller than a circular arc of the same radius, so radii are about 1.3 times the
 * old circular values" (`02-design-formulas.md` section 3), so each B+C superellipse radius becomes the circular radius that
 * looks the same: [circularFor].
 */
object MaterialShapeRadii {
    /** How much larger a superellipse radius is than the circular one that reads the same. */
    const val SUPERELLIPSE_FACTOR = 1.3f

    /** The circular radius that looks like a superellipse corner of [superellipseDp]: `round(r / 1.3)`. */
    fun circularFor(superellipseDp: Float): Float = round(superellipseDp / SUPERELLIPSE_FACTOR)

    /** Menus, text fields, snackbars: the C menu, radius 22 (`01-components-spec-bc.md`, "Menu: radius 22"). */
    val EXTRA_SMALL: Float = circularFor(22f)

    /** Chips and small containers: `--r-sm`, 12. */
    val SMALL: Float = circularFor(12f)

    /** Cards: `--r-card`, 24. */
    val MEDIUM: Float = circularFor(24f)

    /** Large containers and FABs: `--r-tile`, 28. */
    val LARGE: Float = circularFor(28f)

    /** Dialogs and bottom sheets (top corners): `--r-sheet`, 36. */
    val EXTRA_LARGE: Float = circularFor(36f)
}
