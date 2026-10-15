package com.cosmotune.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What the cover gave: the seed (OKLCH), the second colour if there is one, whether the cover is grey ([mono]) and
 * whether it is bright. Port of the object `DC.extract` returns.
 */
internal class Extraction(val seed: Lch, val second: Lch?, val mono: Boolean, val bright: Boolean)

/**
 * The derivation: seed in, palette out. A port of `DC.derive` (and its helpers `ensure` and `inside`) in the
 * designer's `dynamic-colour.js`, line by line, with every number taken from [CoverPaletteSpec]. The order of the
 * steps matters (the contrast loops read what was derived before them), so it is the reference's order.
 */
internal object CoverDerive {

    private fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))

    private fun hueDistance(a: Double, b: Double) = abs(((b - a + 540.0) % 360.0) - 180.0)

    /**
     * @param ext the extraction.
     * @param dark the dark theme or the light one.
     * @param apricot the fallback seed as a token (the brand apricot unless the caller chose another).
     */
    fun derive(ext: Extraction, dark: Boolean, spec: CoverPaletteSpec, apricot: Tok): CoverColors {
        val m = ColourMath(spec.gamutJnd, spec.gamutEpsilon, spec.gamutMargin)
        val ik = spec.intensity
        val s = ext.seed
        val mono = ext.mono
        var h = s.h
        // Chroma budget for grounds and surfaces.
        val c = if (mono) spec.greyGroundChroma else min(s.c * ik, spec.groundChromaMax)
        // The cover's second colour, else the seed's hue.
        var h2 = if (ext.second != null && hueDistance(ext.second.h, h) >= spec.secondHueMinSeparation) ext.second.h else h
        if (mono) {
            h = apricot.h
            h2 = apricot.h
        }
        val yellow = !mono && h >= spec.yellowHueMin && h <= spec.yellowHueMax
        val hg = if (yellow) h + spec.yellowHueShift else h
        val hg2 = if (yellow) h2 + spec.yellowHueShift else h2
        val k = if (ext.bright) spec.brightGroundFactor else 1.0
        val accentChroma = clamp(s.c * ik, spec.accentChromaMin, spec.accentChromaMax) * (if (ext.bright) spec.brightAccentChromaFactor else 1.0)
        val sh = if (!mono && h >= spec.buttonReverseHueMin && h <= spec.buttonReverseHueMax) -spec.buttonHueShift else spec.buttonHueShift

        fun rule(r: ChromaRule, budget: Double, hue: Double, k: Double = 1.0, alpha: Double = 1.0) =
            m.mk(r.l, min(r.factor * budget * k, r.max), hue, alpha)

        /** Move the foreground's L 0.01 at a time away from the grounds; at the end of the range cut its chroma. */
        fun ensure(tok: Tok, grounds: List<Tok>, target: Double): Tok {
            fun worst(t: Tok) = grounds.minOf { m.contrast(t, it) }
            var t = tok
            var steps = 0
            val darkGrounds = grounds.sumOf { it.y } / grounds.size < spec.darkGroundsMeanLuminance
            var l = t.req[0]
            var chroma = t.req[1]
            while (worst(t) < target && steps < spec.contrastMaxSteps) {
                if (if (darkGrounds) l < spec.contrastLMax else l > spec.contrastLMin) {
                    l = clamp(l + (if (darkGrounds) spec.contrastLStep else -spec.contrastLStep), 0.0, 1.0)
                } else {
                    chroma *= spec.contrastChromaShrink
                }
                t = m.mk(l, chroma, t.req[2], t.a)
                steps++
            }
            if (worst(t) < target) {
                val white = m.mk(1.0, 0.0, 0.0, t.a)
                val ink = m.mk(spec.contrastInkL, 0.0, 0.0, t.a)
                t = if (worst(white) > worst(ink)) white else ink
            }
            return t
        }

        val ambient: Tok
        val backgroundTop: Tok
        val backgroundMid: Tok
        val backgroundBottom: Tok
        val surface: Tok
        val surfaceHigh: Tok
        val surfaceVariant: Tok
        val textPrimary: Tok
        val textSecondary: Tok
        val textTertiary: Tok
        val accent: Tok
        val accentStrong: Tok
        var buttonStart: Tok
        var buttonEnd: Tok
        val accentContainer: Tok
        val onAccentContainer: Tok
        val glow: Tok
        val shadow: Tok
        val scrim: Tok
        val track: Tok
        val outline: Tok

        if (dark) {
            var top = rule(spec.darkBackgroundTop, c, hg, k)
            var mid = rule(spec.darkBackgroundMid, c, hg, k)
            var bottom = rule(spec.darkBackgroundBottom, c, hg2, k)
            var amb = rule(spec.darkAmbient, c, hg, k)
            surface = rule(spec.darkSurface, c, hg)
            surfaceHigh = rule(spec.darkSurfaceHigh, c, hg)
            surfaceVariant = rule(spec.darkSurfaceVariant, c, hg)
            // The darkness rail: lower any ground stop that is too bright after gamut mapping.
            fun darker(t0: Tok): Tok {
                var t = t0
                while ((t.l > spec.darkGroundMaxL || t.y > spec.darkGroundMaxLuminance) && t.req[0] > spec.darkGroundMinL) {
                    t = m.mk(t.req[0] - spec.groundStep, t.req[1], t.req[2])
                }
                return t
            }
            top = darker(top)
            mid = darker(mid)
            bottom = darker(bottom)
            amb = darker(amb)
            ambient = amb
            backgroundTop = top
            backgroundMid = mid
            backgroundBottom = bottom
            val grounds = listOf(top, mid, bottom, amb, surface, surfaceHigh, surfaceVariant)
            textPrimary = ensure(rule(spec.darkTextPrimary, c, h), grounds, spec.textContrast)
            textSecondary = ensure(rule(spec.darkTextSecondary, c, h), grounds, spec.textContrast)
            textTertiary = ensure(rule(spec.darkTextTertiary, c, h), grounds, spec.textContrast)
            val la = if (mono) apricot.l else clamp(s.l, spec.darkAccentMinL, spec.darkAccentMaxL) - (if (ext.bright) spec.brightAccentLShift else 0.0)
            val rawAccent = if (mono) apricot else m.mk(la, m.inside(la, accentChroma, h), h)
            accent = ensure(rawAccent, listOf(bottom, surface, surfaceHigh, surfaceVariant), spec.uiContrast)
            accentStrong = ensure(
                m.mk(max(accent.l, spec.darkAccentStrongMinL), min(accent.c, spec.darkAccentStrongMaxChroma), h),
                grounds, spec.textContrast,
            )
            buttonStart = buttonStop(m, spec.darkButtonStart, accentChroma, h - sh)
            buttonEnd = buttonStop(m, spec.darkButtonEnd, accentChroma, h + sh)
            accentContainer = m.mk(
                spec.darkAccentContainer.l,
                clamp(spec.darkAccentContainer.factor * accentChroma, spec.darkAccentContainer.min, spec.darkAccentContainer.max),
                h,
            )
            onAccentContainer = ensure(rule(spec.darkOnAccentContainer, accentChroma, h), listOf(accentContainer), spec.textContrast)
            glow = m.mk(
                clamp(s.l, spec.darkGlowMinL, spec.darkGlowMaxL),
                if (mono) spec.greyGlowChroma else clamp(s.c, spec.darkGlowChromaMin, spec.darkGlowChromaMax),
                h,
            )
            shadow = rule(spec.darkShadow, c, h, 1.0, spec.darkShadowAlpha)
            scrim = rule(spec.darkScrim, c, h)
            track = m.mk(spec.darkTrackL, 0.0, h, spec.darkTrackAlpha)
            outline = m.mk(spec.darkTrackL, 0.0, h, spec.darkOutlineAlpha)
        } else {
            var top = rule(spec.lightBackgroundTop, c, hg, k)
            var mid = rule(spec.lightBackgroundMid, c, hg, k)
            var bottom = rule(spec.lightBackgroundBottom, c, hg2, k)
            var amb = rule(spec.lightAmbient, c, hg, k)
            surface = rule(spec.lightSurface, c, hg)
            surfaceHigh = rule(spec.lightSurfaceHigh, c, hg)
            surfaceVariant = rule(spec.lightSurfaceVariant, c, hg)
            // The lightness floor: raise any ground stop that is too dark after gamut mapping.
            fun lighter(t0: Tok): Tok {
                var t = t0
                while (t.l < spec.lightGroundMinL) t = m.mk(t.req[0] + spec.groundStep, t.req[1], t.req[2])
                return t
            }
            top = lighter(top)
            mid = lighter(mid)
            bottom = lighter(bottom)
            amb = lighter(amb)
            ambient = amb
            backgroundTop = top
            backgroundMid = mid
            backgroundBottom = bottom
            val grounds = listOf(top, mid, bottom, amb, surface, surfaceHigh, surfaceVariant)
            textPrimary = ensure(rule(spec.lightTextPrimary, c, h), grounds, spec.textContrast)
            textSecondary = ensure(rule(spec.lightTextSecondary, c, h), grounds, spec.textContrast)
            textTertiary = ensure(rule(spec.lightTextTertiary, c, h), grounds, spec.textContrast)
            val la = clamp(if (mono) spec.greyLightAccentL else s.l, spec.lightAccentMinL, spec.lightAccentMaxL)
            val al = clamp(if (mono) spec.greyLightAccentChroma else s.c * ik, spec.lightAccentChromaMin, spec.lightAccentChromaMax)
            accent = ensure(m.mk(la, m.inside(la, al, h), h), grounds, spec.uiContrast)
            accentStrong = ensure(
                m.mk(min(accent.l, spec.lightAccentStrongMaxL), min(accent.c, spec.lightAccentStrongMaxChroma), h),
                grounds, spec.textContrast,
            )
            buttonStart = buttonStop(m, spec.lightButtonStart, al, h - sh)
            buttonEnd = buttonStop(m, spec.lightButtonEnd, al, h + sh)
            accentContainer = m.mk(
                spec.lightAccentContainer.l,
                clamp(spec.lightAccentContainer.factor * al, spec.lightAccentContainer.min, spec.lightAccentContainer.max),
                h,
            )
            onAccentContainer = ensure(rule(spec.lightOnAccentContainer, al, h), listOf(accentContainer), spec.textContrast)
            glow = m.mk(
                spec.lightGlowL,
                if (mono) spec.greyGlowChroma else clamp(s.c, spec.lightGlowChromaMin, spec.lightGlowChromaMax),
                h,
            )
            shadow = rule(spec.lightShadow, c, h, 1.0, spec.lightShadowAlpha)
            scrim = rule(spec.lightScrim, c, h)
            track = m.mk(spec.lightTrackL, 0.0, h, spec.lightTrackAlpha)
            outline = m.mk(spec.lightTrackL, 0.0, h, spec.lightOutlineAlpha)
        }

        // On-accent: ink or white, whichever has the higher worst-case contrast on both button stops; if it still fails,
        // both stops move 0.01 L away from it until it passes.
        val ink = rule(spec.onAccentInk, c, h)
        val white = m.mk(1.0, 0.0, 0.0)
        fun worstOnButtons(t: Tok) = min(m.contrast(t, buttonStart), m.contrast(t, buttonEnd))
        val onInk = worstOnButtons(ink) >= worstOnButtons(white)
        val onAccent = if (onInk) ink else white
        var guard = 0
        while (worstOnButtons(onAccent) < spec.textContrast && guard < spec.onAccentMaxShifts) {
            val dir = if (onInk) spec.onAccentShiftStep else -spec.onAccentShiftStep
            buttonStart = m.mk(clamp(buttonStart.req[0] + dir, spec.buttonMinL, spec.buttonMaxL), buttonStart.req[1], buttonStart.req[2])
            buttonEnd = m.mk(clamp(buttonEnd.req[0] + dir, spec.buttonMinL, spec.buttonMaxL), buttonEnd.req[1], buttonEnd.req[2])
            guard++
        }

        return CoverColors(
            seed = m.mk(s.l, s.c, s.h).argb,
            backgroundTop = backgroundTop.argb,
            backgroundMid = backgroundMid.argb,
            backgroundBottom = backgroundBottom.argb,
            surface = surface.argb,
            surfaceVariant = surfaceVariant.argb,
            accent = accent.argb,
            accentStrong = accentStrong.argb,
            onAccent = onAccent.argb,
            buttonGradientStart = buttonStart.argb,
            buttonGradientEnd = buttonEnd.argb,
            textPrimary = textPrimary.argb,
            textSecondary = textSecondary.argb,
            glow = glow.argb,
            isFallback = mono,
            ambient = ambient.argb,
            surfaceHigh = surfaceHigh.argb,
            textTertiary = textTertiary.argb,
            accentContainer = accentContainer.argb,
            onAccentContainer = onAccentContainer.argb,
            shadow = shadow.argb,
            scrim = scrim.argb,
            track = track.argb,
            outline = outline.argb,
        )
    }

    /** One end of the button gradient: `(l, inside(clamp(factor * A, min, max)), hue)`. */
    private fun buttonStop(m: ColourMath, r: AccentChromaRule, accentChroma: Double, hue: Double): Tok =
        m.mk(r.l, m.inside(r.l, clamp(r.factor * accentChroma, r.min, r.max), hue), hue)
}
