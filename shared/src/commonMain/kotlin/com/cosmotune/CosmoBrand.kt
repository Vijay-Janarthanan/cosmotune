package com.cosmotune

/**
 * The two colours the logo is drawn in inside the app, as ARGB values. This module has no Compose
 * dependency, so the screens wrap them: `Color(CosmoBrand.APRICOT)`.
 *
 * The mark stands alone on a transparent background: apricot on a dark theme, ink on a light one.
 * (Every app icon, launcher, installer, window and splash, has a black tile instead; see `docs/MAINTAINING.md`.)
 */
object CosmoBrand {
    /** The mark on a dark theme: 12.59:1 on black, but only 1.67:1 on white, so never on a light theme. */
    const val APRICOT: Long = 0xFFFFB98F

    /** The mark on a light theme: 19.56:1 on white. */
    const val INK: Long = 0xFF0B0C0F
}
