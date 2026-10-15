package com.cosmotune.theme

import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The cover palette engine: the colours of the whole interface, taken from the cover of the song that is playing,
 * with the designer's dynamic-colour formulas (`ui/v2/01-dynamic-colour.md`, implemented in `dynamic-colour.js`).
 * Pure Kotlin with no Android or Compose types, so it runs in a plain unit test; nothing here draws anything.
 *
 * ## How a screen uses it
 *
 * 1. When the track changes (better: when the next track is known, so the fade starts with the audio crossfade),
 *    decode its cover **off the main thread** and shrink it to at most 64 px on a side (the engine samples to 64 x 64
 *    anyway, so a larger bitmap only costs time): `Bitmap.getPixels` on Android,
 *    `Image.toComposeImageBitmap().readPixels` on the desktop. The array holds non-premultiplied ARGB, one
 *    `Int` per pixel, row by row.
 * 2. Call [fromPixels] **once per track**, not once per frame, with `dark` set from the app's theme. A photo-like 64 x 64
 *    cover takes about 10 ms on a warm desktop JVM (a cover of a few flat colours well under a millisecond), so call it
 *    **off the main thread**, and more so on a phone. It never returns null, and a failure inside the colour library
 *    is turned into "no colour": a cover with no colour (white, black, grey, empty, fully transparent) gives the brand
 *    palette from the fallback seed with [CoverColors.isFallback] set.
 * 3. Pass the result through [settle] together with the palette the screen is showing or heading for (and whether the
 *    new track is on the same album). It returns that palette unchanged when the new one looks the same, so nothing
 *    flickers; it compares the colours you will see (the accent and the top of the background), not the seeds.
 * 4. Animate from what is on screen to the settled palette with [lerp] over [CoverPaletteSpec.fadeMillis] (700 ms; 150 ms with
 *    reduced motion) along [fadeProgress]: ask for `t` from 0 to 1 each frame and turn the ARGB integers into
 *    `Color`s. Interpolate text and grounds at the same `t`. If the track changes in the middle of an animation, start
 *    the new one from the blend that is on screen now, not from the palette the old animation started from.
 *
 * ```
 * // target: the palette being shown or animated to (null at the start); shown: the blend on screen this frame
 * val next = CoverPalette.settle(target, CoverPalette.fromPixels(pixels, w, h, dark = isDark), sameAlbum = sameAlbum)
 * if (next !== target) {
 *     val from = shown ?: next            // the current blend, also in the middle of an animation
 *     target = next
 *     animate(700) { x -> shown = CoverPalette.lerp(from, next, CoverPalette.fadeProgress(x)) }
 * }
 * ```
 *
 * ## How the colours are chosen
 *
 * - **Sampling.** The cover is sampled down to at most 64 x 64 pixels, taking the pixel at the centre of each
 *   block (nearest, so the same pixels give the same colours on every machine); pixels with an alpha below 128
 *   are dropped ([CoverPaletteSpec.maxSampleSide], [CoverPaletteSpec.minAlpha]).
 * - **Pre-filter (the designer's).** Pixels with an OKLab L below 0.16 (near-black), above 0.95 (near-white) or a chroma
 *   below 0.03 (near-grey) are dropped. If fewer than 1.5 percent of the pixels are left, the cover is grey: the seed is
 *   the fallback (brand apricot `#FFB98F`) and the palette says so. Without this filter the quantiser picks the near-black
 *   slate behind a cover instead of its orange.
 * - **The seed.** Google's Material Color Utilities (`com.materialkolor:material-color-utilities`, MIT, a Kotlin
 *   Multiplatform port of Google's Apache-2.0 library, for Android and the desktop JVM) divide the surviving pixels into 128
 *   colours with the Celebi quantiser and rank them with `Score.score`, which prefers colours that cover a lot of
 *   the picture and are vivid. (A cover of only a few flat colours skips the quantiser, which would blend them, and
 *   is ranked from its exact colour histogram: [CoverPaletteSpec.maxFlatBins].) The best-ranked colour is the seed,
 *   the second-best the secondary (its hue is used for the bottom background stop if it is at least 25 degrees from
 *   the seed's). The choice sits behind the private function `chooseColours`, the only place the library is used
 *   for it, so another picker can replace it.
 * - **The derivation is OKLCH**: every token is an OKLCH colour (L, chroma budget from the seed, hue), gamut-mapped with
 *   the CSS Color 4 algorithm (L and hue are kept, only chroma is lowered), then checked and, if needed, repaired by
 *   the contrast loop. All of it is a port of the designer's `derive()`; the numbers are in [CoverPaletteSpec] and the tests
 *   compare the output with the reference implementation's (golden vectors).
 * - **The rails**, every one checked on the final 8-bit colour that is returned: no dark background stop (or ambient
 *   light) is above relative luminance 0.030 or OKLCH L 0.30; text is at least 4.5:1 on every ground; the accent at least
 *   3:1 on the surfaces; the accent used as text and the text on the accent container at least 4.5:1; the text on the
 *   button gradient at least 4.5:1 on both ends. A colour that fails is moved away from its ground one step at a time.
 * - **Blending and stability** use OKLab: [lerp] is a straight line there, and [settle] measures how different the
 *   two palettes' accent and background look (deltaE_OK 0.10 on the same album, 0.03 on another).
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
     * @param fallbackSeed the seed used when the cover has no colour; the default is the brand apricot. Its alpha is
     *   ignored (it is made opaque).
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
        val apricot = ColourMath(spec.gamutJnd, spec.gamutEpsilon, spec.gamutMargin).fromArgb(fallbackSeed or OPAQUE)
        val ext = extract(sample(argb, width, height, spec), spec, apricot)
        return CoverDerive.derive(ext, dark, spec, apricot)
    }

    /**
     * The palette a fraction [t] of the way from [a] to [b], each colour blended in OKLab (so a dark blue
     * glides to a dark red without passing through a muddy grey; the alpha of the translucent tokens blends linearly).
     * `t` is held between 0 and 1: 0 (and NaN) gives [a] itself, 1 gives [b] itself. [CoverColors.isFallback]
     * follows the nearer end. Pass the eased progress ([fadeProgress]) for the designer's curve.
     */
    fun lerp(a: CoverColors, b: CoverColors, t: Float): CoverColors {
        if (t.isNaN() || t <= 0f) return a
        if (t >= 1f) return b
        val x = t.toDouble()
        fun mix(from: Int, to: Int): Int {
            if (from == to) return from
            val p = Oklab.fromArgb(from)
            val q = Oklab.fromArgb(to)
            val alpha = ((from ushr 24) + (((to ushr 24) - (from ushr 24)) * x)).roundToInt().coerceIn(0, 255)
            return Oklab.toArgb(doubleArrayOf(p[0] + (q[0] - p[0]) * x, p[1] + (q[1] - p[1]) * x, p[2] + (q[2] - p[2]) * x), alpha)
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
            ambient = mix(a.ambient, b.ambient),
            surfaceHigh = mix(a.surfaceHigh, b.surfaceHigh),
            textTertiary = mix(a.textTertiary, b.textTertiary),
            accentContainer = mix(a.accentContainer, b.accentContainer),
            onAccentContainer = mix(a.onAccentContainer, b.onAccentContainer),
            shadow = mix(a.shadow, b.shadow),
            scrim = mix(a.scrim, b.scrim),
            track = mix(a.track, b.track),
            outline = mix(a.outline, b.outline),
            glass = mix(a.glass, b.glass),
            skelBase = mix(a.skelBase, b.skelBase),
            skelShine = mix(a.skelShine, b.skelShine),
            danger = mix(a.danger, b.danger),
            onDanger = mix(a.onDanger, b.onDanger),
            dangerContainer = mix(a.dangerContainer, b.dangerContainer),
            onDangerContainer = mix(a.onDangerContainer, b.onDangerContainer),
            dangerClash = if (t < 0.5f) a.dangerClash else b.dangerClash,
        )
    }

    /**
     * The fade's easing, the designer's cubic-bezier(0.2, 0, 0, 1): the progress (0 to 1) of the fade at the fraction
     * [x] (0 to 1) of its duration ([CoverPaletteSpec.fadeMillis]). Feed the result to [lerp]. Solved by bisection on the
     * curve's x, like the reference.
     */
    fun fadeProgress(x: Float, spec: CoverPaletteSpec = CoverPaletteSpec()): Float {
        val target = x.toDouble().coerceIn(0.0, 1.0)
        var lo = 0.0
        var hi = 1.0
        var t = target
        repeat(24) {
            t = (lo + hi) / 2
            val u = 1 - t
            val bx = 3 * u * u * t * spec.easeX1 + 3 * u * t * t * spec.easeX2 + t * t * t
            if (bx < target) lo = t else hi = t
        }
        val u = 1 - t
        return (3 * u * u * t * spec.easeY1 + 3 * u * t * t * spec.easeY2 + t * t * t).toFloat()
    }

    /**
     * Keeps the palette on screen when the next track's looks the same. Returns [previous] itself (the same object)
     * when the two palettes' **accent** and **backgroundTop**, the colours that show most, are each closer than the
     * threshold in OKLab (deltaE_OK, the plain Euclidean distance; 0.02 is about what an eye can just tell apart): that is
     * [CoverPaletteSpec.settleSameAlbumDeltaE] (0.10) when [sameAlbum], else [CoverPaletteSpec.settleAcrossAlbumDeltaE]
     * (0.03). It is the case for every track of one album, and also for two covers whose seeds differ only in lightness
     * where the palette does not follow it. Returns [next] otherwise, when there is no previous palette, and whenever
     * exactly one of the two is a fallback palette (a real colour never hides behind the fallback, or the other way
     * round, however close they are). Comparing the accent and background also tells a dark palette from the light one
     * for the same cover.
     */
    fun settle(previous: CoverColors?, next: CoverColors, spec: CoverPaletteSpec, sameAlbum: Boolean = false): CoverColors =
        settle(previous, next, (if (sameAlbum) spec.settleSameAlbumDeltaE else spec.settleAcrossAlbumDeltaE).toFloat())

    /** [settle] with the default spec's threshold for the same album or another one. */
    fun settle(previous: CoverColors?, next: CoverColors, sameAlbum: Boolean = false): CoverColors =
        settle(previous, next, CoverPaletteSpec(), sameAlbum)

    /** [settle] with an explicit threshold (deltaE_OK, the Euclidean distance in OKLab). */
    fun settle(previous: CoverColors?, next: CoverColors, minDeltaE: Float): CoverColors {
        if (previous == null) return next
        if (previous.isFallback != next.isFallback) return next
        val accentDistance = Oklab.distance(previous.accent, next.accent)
        val backgroundDistance = Oklab.distance(previous.backgroundTop, next.backgroundTop)
        return if (accentDistance < minDeltaE && backgroundDistance < minDeltaE) previous else next
    }

    /**
     * The palette of a given seed, without reading a cover: for tests and for screens that already know the seed. The
     * [second] colour, the [mono] (grey cover) and [bright] flags are what extraction would have found.
     */
    internal fun fromSeed(
        seed: Int,
        second: Int?,
        mono: Boolean,
        bright: Boolean,
        dark: Boolean,
        spec: CoverPaletteSpec = CoverPaletteSpec(),
        fallbackSeed: Int = 0xFFFFB98F.toInt(),
    ): CoverColors {
        val apricot = ColourMath(spec.gamutJnd, spec.gamutEpsilon, spec.gamutMargin).fromArgb(fallbackSeed or OPAQUE)
        val lch = if (mono) Lch(apricot.l, apricot.c, apricot.h) else Oklab.lchFromArgb(seed or OPAQUE)
        val ext = Extraction(lch, second?.let { Oklab.lchFromArgb(it or OPAQUE) }, mono, bright && !mono)
        return CoverDerive.derive(ext, dark, spec, apricot)
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
     * The designer's extraction on the sampled pixels: the pre-filter, then the seed and the secondary; or the grey
     * cover (seed = the fallback), when fewer than [CoverPaletteSpec.minColourShare] of the pixels have a colour.
     */
    internal fun extract(pixels: IntArray, spec: CoverPaletteSpec, apricot: Tok): Extraction {
        val fallback = Lch(apricot.l, apricot.c, apricot.h)
        if (pixels.isEmpty()) return Extraction(fallback, null, mono = true, bright = false)
        val colour = IntArray(pixels.size)
        var colourCount = 0
        var sumL = 0.0
        for (p in pixels) {
            val lab = Oklab.fromArgb(p)
            sumL += lab[0]
            if (lab[0] < spec.nearBlackL || lab[0] > spec.nearWhiteL) continue
            if (kotlin.math.hypot(lab[1], lab[2]) < spec.nearGreyChroma) continue
            colour[colourCount++] = p
        }
        val meanL = sumL / pixels.size
        val share = colourCount.toDouble() / pixels.size
        if (colourCount == 0 || share < spec.minColourShare) return Extraction(fallback, null, mono = true, bright = false)
        val picked = chooseColours(colour.copyOf(colourCount), spec)
        if (picked.isEmpty()) return Extraction(fallback, null, mono = true, bright = false)
        val seed = Oklab.lchFromArgb(picked[0])
        val second = picked.getOrNull(1)?.let { Oklab.lchFromArgb(it) }
        return Extraction(seed, second, mono = false, bright = seed.l > spec.brightSeedL || meanL > spec.brightMeanL)
    }

    /**
     * Picks the colours of the cover that best stand for it, best first (the seed, then the secondary), without the
     * near-grey ones; empty when there are none. Kept in one private function so that the picker can be swapped
     * without touching the rest. A failure inside the library is treated as "no colour": a decoration must not take
     * the player down.
     */
    private fun chooseColours(pixels: IntArray, spec: CoverPaletteSpec): List<Int> = try {
        val quantized = flatHistogram(pixels, spec.maxFlatBins) ?: QuantizerCelebi.quantize(pixels, max(spec.quantizerColors, 1))
        if (quantized.isEmpty()) {
            emptyList()
        } else {
            // Up to four hue-distinct colours, best first; the near-grey ones (OKLCH chroma under 0.03) are not colours.
            Score.score(quantized, desired = max(spec.scoredColours, 2), fallbackColorArgb = null, filter = true)
                .filter { Oklab.lchFromArgb(it).c >= spec.nearGreyChroma }
        }
    } catch (e: Exception) {
        emptyList()
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
}
