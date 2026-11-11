package com.cosmotune.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The cover's wash: the one colour the page grounds wear while a cover leads (the ambient ground at the top of every page, the
 * band behind the top bar, the Now Playing backdrop on the phone and the desktop, the desktop's frame). It is made from the song
 * card's own fill, so the card and the ground it stands on read as one family.
 *
 * Why not the palette's accent or accent container: those are derived for buttons and chips, with their chroma budget cut for a
 * bright cover (a mostly white cover with a little vivid red got an accent container of OKLCH chroma 0.07 at L 0.38), and moving
 * them to the text's contrast by mixing toward black took the rest of their chroma: the card was a deep red and the page behind it
 * a muddy brown.
 *
 * Everything here is OKLCH. The wash keeps the card's hue and chroma and only its lightness is chosen: just under the card's in
 * the dark theme, a pale tint ([LIGHT_L]) in the light one. When the text needs more contrast, the lightness moves away from the
 * text and the chroma stays (gamut mapping lowers it only where sRGB has no such colour), so a deep red stays a red. A grey cover's
 * card is a charcoal, and its wash is the same near-neutral, never a brown made up for it.
 *
 * Pure functions on ARGB integers; no Compose types, so it runs in a plain unit test.
 */
object CoverWash {

    private val spec = CoverPaletteSpec()
    private val m = ColourMath(spec.gamutJnd, spec.gamutEpsilon, spec.gamutMargin)

    /** The dark theme's wash sits this much OKLCH L under the card's fill, so the card still stands off its ground. */
    const val DARK_BELOW_CARD: Double = 0.03

    /** The dark wash's L is held between these: never near black (it would read as no colour), never a light tint. */
    const val DARK_MIN_L: Double = 0.22
    const val DARK_MAX_L: Double = 0.42

    /** The light theme's wash: the card's hue lightened to this OKLCH L, its chroma kept as far as sRGB has it. */
    const val LIGHT_L: Double = 0.90

    /** The text contrast every wash keeps. */
    const val TEXT_CONTRAST: Double = 4.5

    /**
     * The wash for a card of [cardFill] (opaque ARGB) in the [dark] theme or the light one, under the page's text [inks] (the
     * primary, secondary and tertiary text: all light in the dark theme, all dark in the light one): the card's OKLCH hue and
     * chroma at the wash's lightness, then moved in lightness only until every ink reads on it at [TEXT_CONTRAST]:1.
     */
    fun ground(cardFill: Int, dark: Boolean, vararg inks: Int): Int {
        val f = Oklab.lchFromArgb(cardFill or OPAQUE)
        val l = if (dark) (f.l - DARK_BELOW_CARD).coerceIn(DARK_MIN_L, DARK_MAX_L) else LIGHT_L
        return readable(l, f.c, f.h, inks, TEXT_CONTRAST)
    }

    /**
     * [ground] (ARGB; its alpha is kept) moved in OKLCH lightness, away from [ink], just far enough that [ink] keeps [ratio]:1 on
     * it; its hue is kept and so is its chroma, except where sRGB has no colour that vivid at the new lightness. Unchanged (the
     * same integer) when it already reads.
     */
    fun readable(ground: Int, ink: Int, ratio: Double = TEXT_CONTRAST): Int {
        if (contrast(ground, ink) >= ratio) return ground
        val g = Oklab.lchFromArgb(ground)
        return (readable(g.l, g.c, g.h, intArrayOf(ink), ratio) and 0xFFFFFF) or (ground and OPAQUE)
    }

    /** The OKLCH lightness, chroma and hue (degrees) of an opaque ARGB colour. */
    fun oklch(argb: Int): DoubleArray = Oklab.lchFromArgb(argb or OPAQUE).let { doubleArrayOf(it.l, it.c, it.h) }

    /** The WCAG 2 contrast of two opaque ARGB colours. */
    fun contrast(a: Int, b: Int): Double = contrast(luminance(a), luminance(b))

    /** The WCAG 2 relative luminance of an ARGB colour (its alpha is ignored). */
    fun luminance(argb: Int): Double {
        fun lin(channel8: Int): Double {
            val v = channel8 / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    /**
     * The colour at OKLCH ([l], [c], [h]) (gamut-mapped: L and hue kept, chroma lowered only as far as sRGB needs), with its L moved
     * toward black (light inks) or white (dark inks) by bisection until every one of [inks] reads on it at [ratio]:1. Opaque.
     */
    private fun readable(l: Double, c: Double, h: Double, inks: IntArray, ratio: Double): Int {
        if (inks.isEmpty()) return m.mk(l, c, h).argb or OPAQUE
        val inkY = DoubleArray(inks.size) { luminance(inks[it]) }
        fun reads(argb: Int): Boolean { val y = luminance(argb); return inkY.all { contrast(y, it) >= ratio } }
        val start = m.mk(l, c, h).argb or OPAQUE
        if (reads(start)) return start
        // Light ink wants a darker ground, dark ink a lighter one: the end of the range always reads (black or white).
        var fails = l
        var passes = if (inkY.average() > LIGHT_INK_Y) 0.0 else 1.0
        repeat(BISECTIONS) {
            val mid = (fails + passes) / 2
            if (reads(m.mk(mid, c, h).argb)) passes = mid else fails = mid
        }
        return m.mk(passes, c, h).argb or OPAQUE
    }

    private fun contrast(y1: Double, y2: Double) = (max(y1, y2) + 0.05) / (min(y1, y2) + 0.05)

    /** Ink lighter than this (relative luminance) is light ink: the ground darkens for it. */
    private const val LIGHT_INK_Y = 0.18
    private const val BISECTIONS = 22
    private const val OPAQUE = 0xFF shl 24
}
