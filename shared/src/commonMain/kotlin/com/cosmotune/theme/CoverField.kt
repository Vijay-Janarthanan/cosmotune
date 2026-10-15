package com.cosmotune.theme

/**
 * Index of each colour of [CoverColors], in the order of the handoff table; the animation keeps them in arrays.
 * [CoverColors.track] and [CoverColors.outline] are not here: they are translucent neutrals that the animation
 * does not blend, and the new screens draw them from the text colour.
 */
object CoverField {
    const val SEED = 0; const val AMBIENT = 1
    const val BACKGROUND_TOP = 2; const val BACKGROUND_MID = 3; const val BACKGROUND_BOTTOM = 4
    const val SURFACE = 5; const val SURFACE_HIGH = 6; const val SURFACE_VARIANT = 7
    const val TEXT_PRIMARY = 8; const val TEXT_SECONDARY = 9; const val TEXT_TERTIARY = 10
    const val ACCENT = 11; const val ACCENT_STRONG = 12; const val ON_ACCENT = 13
    const val BUTTON_GRADIENT_START = 14; const val BUTTON_GRADIENT_END = 15
    const val ACCENT_CONTAINER = 16; const val ON_ACCENT_CONTAINER = 17
    const val GLOW = 18; const val SHADOW = 19; const val SCRIM = 20
    const val COUNT = 21
}

fun CoverColors.toFieldArray(): IntArray = intArrayOf(
    seed, ambient, backgroundTop, backgroundMid, backgroundBottom, surface, surfaceHigh, surfaceVariant,
    textPrimary, textSecondary, textTertiary, accent, accentStrong, onAccent, buttonGradientStart,
    buttonGradientEnd, accentContainer, onAccentContainer, glow, shadow, scrim,
)

/** One colour by its [CoverField] index. Allocates: for composition-time use only. */
fun CoverColors.argbOf(field: Int): Int = toFieldArray()[field]
