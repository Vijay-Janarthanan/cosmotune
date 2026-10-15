package com.cosmotune.theme

import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The cover palette engine: the colours of the whole interface, taken from the cover of the song that is playing.
 * Pure Kotlin with no Android or Compose types, so it runs in a plain unit test; nothing here draws anything.
 *
 * ## How a screen uses it
 *
 * 1. When the track changes, decode its cover **off the main thread** and shrink it to at most 64 px on a side
 *    (the engine samples to 64 x 64 anyway, so a larger bitmap only costs time): `Bitmap.getPixels` on Android,
 *    `Image.toComposeImageBitmap().readPixels` on the desktop. The array holds non-premultiplied ARGB, one
 *    `Int` per pixel, row by row.
 * 2. Call [fromPixels] **once per track**, not once per frame (a 64 x 64 cover takes about a millisecond on a warm
 *    desktop JVM; keep it off the main thread on a phone), with `dark` set from the app's theme. It never returns
 *    null, and a failure inside the colour library is turned into "no colour": a cover with no colour (white, black,
 *    grey, empty, fully transparent) gives a calm palette from the fallback seed with [CoverColors.isFallback] set.
 * 3. Pass the result through [settle] together with the palette now on screen. Two tracks of one album carry the
 *    same cover and the same seed, and `settle` then returns the palette on screen unchanged, so nothing flickers.
 * 4. Animate from the palette on screen to the settled one with [lerp] over 600 ms: ask for `t` from 0 to 1
 *    each frame, turn the ARGB integers into `Color`s, and keep the final palette as the next call's "previous".
 *
 * ```
 * val next = CoverPalette.settle(onScreen, CoverPalette.fromPixels(pixels, w, h, dark = isDark))
 * if (next !== onScreen) animate(600) { t -> shown = CoverPalette.lerp(onScreen, next, t) }
 * ```
 *
 * ## How the colours are chosen
 *
 * - **Sampling.** The cover is sampled down to at most 64 x 64 pixels, taking the pixel at the centre of each
 *   block (nearest, so the same pixels give the same colours on every machine); pixels with an alpha below 128
 *   are dropped ([CoverPaletteSpec.maxSampleSide], [CoverPaletteSpec.minAlpha]).
 * - **The seed.** Google's Material Color Utilities (`com.materialkolor:material-color-utilities`, MIT, a Kotlin
 *   Multiplatform port of Google's Apache-2.0 library, for Android and the desktop JVM) divide the sample into 128
 *   colours with the Celebi quantiser and rank them with `Score.score`, which prefers colours that cover a lot of
 *   the picture and are vivid. (A cover of only a few flat colours skips the quantiser, which would blend them, and
 *   is ranked from its exact colour histogram: [CoverPaletteSpec.maxFlatBins].) The best-ranked colour with a chroma
 *   of at least 10 becomes the seed; with none (the cover is grey, black or white), the seed is `fallbackSeed` and
 *   the palette says so. The choice sits behind the private function `chooseSeed`, the only place the library is
 *   used for it, so another picker can replace it.
 * - **The colour space is HCT** (hue, chroma, tone), also from that library. Tone is CIE L* (0 black, 100 white),
 *   so every tone in [CoverPaletteSpec] is a perceptual lightness that maps straight to a contrast ratio. A colour
 *   the screen cannot show at its hue and tone has its chroma lowered until it fits; channels are never clipped.
 *   Hue and chroma of everything come from the seed; the tones and chroma limits are the design, in the spec.
 * - **The rails**, checked on the final 8-bit colours (not on the targets): in the dark theme no background or
 *   surface has a relative luminance above 0.12 and no accent colour is above tone 84; text is at least 4.5:1 on
 *   every background stop and surface, the on-accent colour is at least 4.5:1 on the accent and both ends of the
 *   button gradient, and the accent is at least 3:1 on the surface. A colour that fails is moved away from what it
 *   sits on in steps of 2 tones; an on-accent colour that still fails becomes the better of near-black and near-white.
 *   With the default spec the targets already hold every rail; the repair only matters when the spec is retuned.
 * - **Blending and stability** use OKLab ([Oklab]): [lerp] is a straight line there, and [settle] measures how
 *   different two seeds look.
 *
 * Everything is deterministic: the same pixels, flag and spec always give the same palette. No time, no randomness
 * (the library's quantiser uses a fixed seed).
 */
object CoverPalette {

    private const val OPAQUE: Int = 0xFF shl 24

    /**
     * The palette for a cover.
     *
     * @param argb the cover's pixels, non-premultiplied ARGB, row by row: `argb[y * width + x]`. It is only read.
     * @param width the cover's width in pixels.
     * @param height the cover's height in pixels.
     * @param dark the dark theme (the default) or the light one.
     * @param spec every number of the design.
     * @param fallbackSeed the seed used when the cover has no colour; the default is the apricot of the logo.
     *   Its alpha is ignored (it is made opaque).
     * @return the palette. A zero or negative size, an array shorter than `width * height`, or no pixel with an
     *   alpha of 128 or more gives the fallback palette, like any other cover without colour.
     */
    fun fromPixels(
        argb: IntArray,
        width: Int,
        height: Int,
        dark: Boolean = true,
        spec: CoverPaletteSpec = CoverPaletteSpec(),
        fallbackSeed: Int = 0xFFFFB98F.toInt(),
    ): CoverColors {
        val pixels = sample(argb, width, height, spec)
        val chosen = if (pixels.isEmpty()) null else chooseSeed(pixels, spec)
        return derive(chosen ?: (fallbackSeed or OPAQUE), isFallback = chosen == null, dark = dark, spec = spec)
    }

    /**
     * The palette a fraction [t] of the way from [a] to [b], each colour blended in OKLab (so a dark blue
     * glides to a dark red without passing through a muddy grey) and brought into the sRGB range by lowering its
     * chroma. `t` is held between 0 and 1: 0 (and NaN) gives [a] itself, 1 gives [b] itself. [CoverColors.isFallback]
     * follows the nearer end.
     */
    fun lerp(a: CoverColors, b: CoverColors, t: Float): CoverColors {
        if (t.isNaN() || t <= 0f) return a
        if (t >= 1f) return b
        val x = t.toDouble()
        fun mix(from: Int, to: Int): Int {
            if (from == to) return from
            val p = Oklab.fromArgb(from)
            val q = Oklab.fromArgb(to)
            return Oklab.toArgb(Oklab.Lab(p.l + (q.l - p.l) * x, p.a + (q.a - p.a) * x, p.b + (q.b - p.b) * x))
        }
        return CoverColors(
            seed = mix(a.seed, b.seed),
            backgroundTop = mix(a.backgroundTop, b.backgroundTop),
            backgroundMid = mix(a.backgroundMid, b.backgroundMid),
            backgroundBottom = mix(a.backgroundBottom, b.backgroundBottom),
            surface = mix(a.surface, b.surface),
            surfaceVariant = mix(a.surfaceVariant, b.surfaceVariant),
            accent = mix(a.accent, b.accent),
            accentStrong = mix(a.accentStrong, b.accentStrong),
            onAccent = mix(a.onAccent, b.onAccent),
            buttonGradientStart = mix(a.buttonGradientStart, b.buttonGradientStart),
            buttonGradientEnd = mix(a.buttonGradientEnd, b.buttonGradientEnd),
            textPrimary = mix(a.textPrimary, b.textPrimary),
            textSecondary = mix(a.textSecondary, b.textSecondary),
            glow = mix(a.glow, b.glow),
            isFallback = if (t < 0.5f) a.isFallback else b.isFallback,
        )
    }

    /**
     * Keeps the palette on screen when the next track's is not noticeably different. Returns [previous] itself
     * (the same object) when the two seeds are closer than [minDeltaE] in OKLab (distance times 100; about 2 is
     * what an eye can just tell apart), which is the case for every track of one album, and [next] otherwise or
     * when there is no previous palette.
     *
     * The two palettes' surfaces are compared too, so that the same seed in the other theme (a dark palette
     * replaced by the light one, same cover) is never taken for "the same palette".
     */
    fun settle(previous: CoverColors?, next: CoverColors, minDeltaE: Float = CoverPaletteSpec().settleMinDeltaE): CoverColors {
        if (previous == null) return next
        val seedDistance = Oklab.deltaE(previous.seed, next.seed)
        val surfaceDistance = Oklab.deltaE(previous.surface, next.surface)
        return if (seedDistance < minDeltaE && surfaceDistance < minDeltaE) previous else next
    }

    // --- reading the cover ---------------------------------------------------------------------

    /**
     * The pixels the palette is made from: the cover sampled to at most `maxSampleSide` on a side (the pixel at the
     * centre of each block), without the pixels with an alpha below `minAlpha`, and with the rest made opaque.
     * Empty for an unusable cover (non-positive size, an array shorter than the size says).
     */
    internal fun sample(argb: IntArray, width: Int, height: Int, spec: CoverPaletteSpec = CoverPaletteSpec()): IntArray {
        if (width <= 0 || height <= 0) return IntArray(0)
        if (argb.size.toLong() < width.toLong() * height.toLong()) return IntArray(0)
        val side = max(spec.maxSampleSide, 1)
        val outW = min(width, side)
        val outH = min(height, side)
        val out = IntArray(outW * outH)
        var count = 0
        for (oy in 0 until outH) {
            val sy = (((2L * oy + 1) * height) / (2L * outH)).toInt()
            for (ox in 0 until outW) {
                val sx = (((2L * ox + 1) * width) / (2L * outW)).toInt()
                val pixel = argb[sy * width + sx]
                if ((pixel ushr 24) >= spec.minAlpha) out[count++] = pixel or OPAQUE
            }
        }
        return out.copyOf(count)
    }

    /**
     * Picks the seed: the colour of the cover that best stands for it, or null when the cover has none.
     * Kept in one private function so that the picker can be swapped without touching the rest.
     * A failure inside the library is treated as "no colour": a decoration must not take the player down.
     */
    private fun chooseSeed(pixels: IntArray, spec: CoverPaletteSpec): Int? = try {
        val quantized = flatHistogram(pixels, spec.maxFlatBins) ?: QuantizerCelebi.quantize(pixels, max(spec.quantizerColors, 1))
        if (quantized.isEmpty()) {
            null
        } else {
            // Up to four hue-distinct colours, best first; the first one with enough chroma is the seed.
            Score.score(quantized, desired = 4, fallbackColorArgb = null, filter = true)
                .firstOrNull { Hct.fromInt(it).chroma >= spec.minSeedChroma }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The colours of a flat cover with their pixel counts, or null when the cover has more than [limit] distinct
     * colours (counted at 5 bits per channel, the resolution of the quantiser's own histogram) and is better left to
     * the quantiser. Each colour is the mean of the pixels in its bin, so a solid colour comes out exactly and light
     * noise around it (as JPEG leaves on flat art) is averaged away.
     *
     * Why not the quantiser alone: with only a few distinct colours it can return one blend of them (a cover of pink
     * and teal came out as one dull mauve), and the blend is what would become the seed. Its result for rich covers
     * (photographs, gradients) is what it is built for.
     */
    private fun flatHistogram(pixels: IntArray, limit: Int): Map<Int, Int>? {
        val bins = LinkedHashMap<Int, LongArray>() // 15-bit bin -> pixel count and the sums of red, green, blue
        for (p in pixels) {
            val key = (((p shr 19) and 0x1F) shl 10) or (((p shr 11) and 0x1F) shl 5) or ((p shr 3) and 0x1F)
            val sum = bins.getOrPut(key) { LongArray(4) }
            sum[0]++
            sum[1] += ((p shr 16) and 0xFF).toLong()
            sum[2] += ((p shr 8) and 0xFF).toLong()
            sum[3] += (p and 0xFF).toLong()
            if (bins.size > limit) return null
        }
        val colours = LinkedHashMap<Int, Int>()
        for (sum in bins.values) {
            val n = sum[0]
            val colour = OPAQUE or
                (((sum[1] + n / 2) / n).toInt() shl 16) or
                (((sum[2] + n / 2) / n).toInt() shl 8) or
                ((sum[3] + n / 2) / n).toInt()
            colours[colour] = (colours[colour] ?: 0) + n.toInt()
        }
        return colours
    }

    // --- deriving the palette --------------------------------------------------------------------

    /** A tone and a chroma. */
    private class Tc(val tone: Double, val chroma: Double)

    /** The targets of one theme, before the rails: what the design asks for. */
    private class Targets(
        val top: Tc,
        val mid: Tc,
        val bottom: Tc,
        val surface: Tc,
        val surfaceVariant: Tc,
        val textPrimary: Tc,
        val textSecondary: Tc,
        val onAccent: Tc,
        val accentTone: Double,
        val accentStrongTone: Double,
        val buttonStartTone: Double,
        val buttonEndTone: Double,
        val glowTone: Double,
    )

    private fun targets(spec: CoverPaletteSpec, dark: Boolean, seedChroma: Double): Targets =
        if (dark) {
            Targets(
                top = Tc(spec.darkBackgroundTopTone, min(seedChroma * spec.darkBackgroundTopChromaFactor, spec.darkBackgroundTopChromaMax)),
                mid = Tc(spec.darkBackgroundMidTone, min(seedChroma * spec.darkBackgroundMidChromaFactor, spec.darkBackgroundMidChromaMax)),
                bottom = Tc(spec.darkBackgroundBottomTone, min(seedChroma * spec.darkBackgroundBottomChromaFactor, spec.darkBackgroundBottomChromaMax)),
                surface = Tc(spec.darkSurfaceTone, spec.darkSurfaceChroma),
                surfaceVariant = Tc(spec.darkSurfaceVariantTone, spec.darkSurfaceVariantChroma),
                textPrimary = Tc(spec.darkTextPrimaryTone, spec.darkTextPrimaryChroma),
                textSecondary = Tc(spec.darkTextSecondaryTone, spec.darkTextSecondaryChroma),
                onAccent = Tc(spec.darkOnAccentTone, spec.darkOnAccentChroma),
                accentTone = spec.darkAccentTone,
                accentStrongTone = spec.darkAccentStrongTone,
                buttonStartTone = spec.darkButtonStartTone,
                buttonEndTone = spec.darkButtonEndTone,
                glowTone = spec.darkGlowTone,
            )
        } else {
            Targets(
                top = Tc(spec.lightBackgroundTopTone, min(seedChroma, spec.lightBackgroundTopChroma)),
                mid = Tc(spec.lightBackgroundMidTone, min(seedChroma, spec.lightBackgroundMidChroma)),
                bottom = Tc(spec.lightBackgroundBottomTone, min(seedChroma, spec.lightBackgroundBottomChroma)),
                surface = Tc(spec.lightSurfaceTone, spec.lightSurfaceChroma),
                surfaceVariant = Tc(spec.lightSurfaceVariantTone, spec.lightSurfaceVariantChroma),
                textPrimary = Tc(spec.lightTextPrimaryTone, spec.lightTextPrimaryChroma),
                textSecondary = Tc(spec.lightTextSecondaryTone, spec.lightTextSecondaryChroma),
                onAccent = Tc(spec.lightOnAccentTone, spec.lightOnAccentChroma),
                accentTone = spec.lightAccentTone,
                accentStrongTone = spec.lightAccentStrongTone,
                buttonStartTone = spec.lightButtonStartTone,
                buttonEndTone = spec.lightButtonEndTone,
                glowTone = spec.lightGlowTone,
            )
        }

    private fun derive(seed: Int, isFallback: Boolean, dark: Boolean, spec: CoverPaletteSpec): CoverColors {
        val seedHct = Hct.fromInt(seed)
        val hue = seedHct.hue
        val goal = targets(spec, dark, seedHct.chroma)

        // Backgrounds and surfaces. In the dark theme none may be brighter than the luminance cap.
        fun surfaceColour(tc: Tc): Int =
            if (dark) underLuminance(hue, tc.chroma, tc.tone, spec.maxDarkBackgroundLuminance) else colour(hue, tc.chroma, tc.tone)
        val backgroundTop = surfaceColour(goal.top)
        val backgroundMid = surfaceColour(goal.mid)
        val backgroundBottom = surfaceColour(goal.bottom)
        val surface = surfaceColour(goal.surface)
        val surfaceVariant = surfaceColour(goal.surfaceVariant)

        // The accent family: held at or below the tone cap. The accent itself is a component on the surface.
        val accentChroma = seedHct.chroma.coerceIn(spec.accentChromaMin, spec.accentChromaMax)
        val accent = repaired(
            hue, accentChroma, goal.accentTone, listOf(surface),
            spec.minComponentContrast, 0.0, spec.maxAccentTone, spec,
        )
        val accentStrong = colour(hue, accentChroma + spec.accentStrongChromaBoost, min(goal.accentStrongTone, spec.maxAccentTone))
        val buttonStart = colour(hue + spec.buttonStartHueShift, spec.buttonStartChroma, min(goal.buttonStartTone, spec.maxAccentTone))
        val buttonEnd = colour(hue + spec.buttonEndHueShift, spec.buttonEndChroma, min(goal.buttonEndTone, spec.maxAccentTone))
        val glow = colour(hue, spec.glowChroma, min(goal.glowTone, spec.maxAccentTone))

        // The colour of what is drawn on the accent: moved to a readable tone, or flipped to near-black / near-white.
        val onAccent = onAccentColour(hue, goal.onAccent, listOf(accent, buttonStart, buttonEnd), spec)

        // Text on every background stop and on both surfaces.
        val behindText = listOf(backgroundTop, backgroundMid, backgroundBottom, surface, surfaceVariant)
        val textPrimary = repaired(
            hue, goal.textPrimary.chroma, goal.textPrimary.tone, behindText, spec.minTextContrast, 0.0, 100.0, spec,
        )
        val textSecondary = repaired(
            hue, goal.textSecondary.chroma, goal.textSecondary.tone, behindText, spec.minTextContrast, 0.0, 100.0, spec,
        )

        return CoverColors(
            seed = seed,
            backgroundTop = backgroundTop,
            backgroundMid = backgroundMid,
            backgroundBottom = backgroundBottom,
            surface = surface,
            surfaceVariant = surfaceVariant,
            accent = accent,
            accentStrong = accentStrong,
            onAccent = onAccent,
            buttonGradientStart = buttonStart,
            buttonGradientEnd = buttonEnd,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            glow = glow,
            isFallback = isFallback,
        )
    }

    /**
     * The opaque ARGB colour of an HCT triple. The library's solver keeps hue and tone and lowers the chroma
     * until the colour fits the sRGB range, so a colour is never made by clipping channels.
     */
    private fun colour(hue: Double, chroma: Double, tone: Double): Int =
        Hct.from(((hue % 360.0) + 360.0) % 360.0, max(chroma, 0.0), tone.coerceIn(0.0, 100.0)).toInt() or OPAQUE

    /** The colour at [tone], or the first tone below it (steps of 1) whose relative luminance is at most [maxLuminance]. */
    private fun underLuminance(hue: Double, chroma: Double, tone: Double, maxLuminance: Double): Int {
        var t = tone.coerceIn(0.0, 100.0)
        var c = colour(hue, chroma, t)
        while (luminance(c) > maxLuminance && t > 0.0) {
            t = max(t - 1.0, 0.0)
            c = colour(hue, chroma, t)
        }
        return c
    }

    private fun readable(colour: Int, against: List<Int>, needed: Double): Boolean = against.all { contrast(colour, it) >= needed }

    /**
     * A foreground colour of this hue and chroma that has at least [minRatio] (plus the rounding margin) of contrast
     * against every colour of [against]: the [tone] itself if it is readable, otherwise the nearest tone, in steps of
     * `toneStep` away from the background, between [lowerTone] and [upperTone] that is. If no tone in the range is
     * readable, the one at the limit (the best the range allows).
     */
    private fun repaired(
        hue: Double,
        chroma: Double,
        tone: Double,
        against: List<Int>,
        minRatio: Double,
        lowerTone: Double,
        upperTone: Double,
        spec: CoverPaletteSpec,
    ): Int {
        val needed = minRatio + spec.contrastMargin
        var t = tone.coerceIn(lowerTone, upperTone)
        var c = colour(hue, chroma, t)
        if (readable(c, against, needed)) return c
        val step = max(spec.toneStep, 0.1)
        val lighterThanBackground = luminance(c) >= against.sumOf { luminance(it) } / against.size
        val direction = if (lighterThanBackground) step else -step
        while (true) {
            val next = (t + direction).coerceIn(lowerTone, upperTone)
            if (next == t) return c
            t = next
            c = colour(hue, chroma, t)
            if (readable(c, against, needed)) return c
        }
    }

    private fun onAccentColour(hue: Double, goal: Tc, accents: List<Int>, spec: CoverPaletteSpec): Int {
        val moved = repaired(hue, goal.chroma, goal.tone, accents, spec.minTextContrast, 0.0, 100.0, spec)
        if (readable(moved, accents, spec.minTextContrast + spec.contrastMargin)) return moved
        val nearBlack = colour(hue, spec.onAccentFlipChroma, spec.onAccentNearBlackTone)
        val nearWhite = colour(hue, spec.onAccentFlipChroma, spec.onAccentNearWhiteTone)
        fun worst(c: Int) = accents.minOf { contrast(c, it) }
        return if (worst(nearBlack) >= worst(nearWhite)) nearBlack else nearWhite
    }

    // --- WCAG ---------------------------------------------------------------------------------

    /** Relative luminance of an sRGB colour (WCAG 2.x / sRGB: 0.04045 knee, Rec. 709 weights). */
    private fun luminance(argb: Int): Double =
        0.2126 * linear((argb shr 16) and 0xFF) + 0.7152 * linear((argb shr 8) and 0xFF) + 0.0722 * linear(argb and 0xFF)

    private fun linear(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
