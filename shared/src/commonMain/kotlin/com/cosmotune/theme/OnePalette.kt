package com.cosmotune.theme

/**
 * The fixed neutral palette of the "One" design system (`docs/design/DESIGN_SYSTEM.md` sections 2.1, 2.2 and 2.4): every mode's
 * chrome, pages, lists, sheets and the dock draw with it, so the whole app is one UI. The cover's colour ([CoverPalette]) is kept
 * for the zones of section 2.3 only (Now Playing and the detail headers).
 *
 * The neutral roles map onto [CoverColors]' fields so every screen keeps reading the same names: the three background stops and
 * the ambient light are `bg`; `surface`, `surfaceHigh` and `surfaceVariant` are `surface1`, `surface2` and `surface3`; the accent
 * family is the brand apricot (`#FFB98F` dark, `#A24B17` light); `outline` is the hairline; the danger family is the fixed status red.
 */
object OnePalette {

    private fun argb(hex: Long): Int = hex.toInt()

    private val dark: CoverColors by lazy { build(Theme.DARK) }
    private val black: CoverColors by lazy { build(Theme.AMOLED) }
    private val light: CoverColors by lazy { build(Theme.LIGHT) }

    /** The palette of the dark theme, its AMOLED variant (a true black page, tiers kept visible) or the light theme. */
    fun of(dark: Boolean, amoled: Boolean = false): CoverColors = when {
        !dark -> light
        amoled -> black
        else -> this.dark
    }

    private enum class Theme { DARK, AMOLED, LIGHT }

    private fun build(theme: Theme): CoverColors {
        val isLight = theme == Theme.LIGHT
        val bg = argb(when (theme) { Theme.DARK -> 0xFF0F0F11; Theme.AMOLED -> 0xFF000000; Theme.LIGHT -> 0xFFF7F7F8 })
        val surface1 = argb(when (theme) { Theme.DARK -> 0xFF17171A; Theme.AMOLED -> 0xFF0E0E10; Theme.LIGHT -> 0xFFFFFFFF })
        val surface2 = argb(when (theme) { Theme.DARK -> 0xFF1F1F23; Theme.AMOLED -> 0xFF18181B; Theme.LIGHT -> 0xFFEFEFF1 })
        val surface3 = argb(when (theme) { Theme.DARK -> 0xFF29292E; Theme.AMOLED -> 0xFF222226; Theme.LIGHT -> 0xFFE4E4E7 })
        val accent = argb(if (isLight) 0xFFA24B17 else 0xFFFFB98F)
        val accentContainer = argb(if (isLight) 0xFFFFE3D3 else 0xFF3A2418)
        return CoverColors(
            seed = accent,
            backgroundTop = bg,
            backgroundMid = bg,
            backgroundBottom = bg,
            surface = surface1,
            surfaceVariant = surface3,
            accent = accent,
            accentStrong = accent,
            onAccent = argb(if (isLight) 0xFFFFFFFF else 0xFF2A1607),
            buttonGradientStart = accent,
            buttonGradientEnd = accent,
            textPrimary = argb(if (isLight) 0xFF121214 else 0xFFF2F2F3),
            textSecondary = argb(if (isLight) 0xFF55555D else 0xFFB4B4BB),
            glow = accentContainer,
            isFallback = true,
            ambient = bg,
            surfaceHigh = surface2,
            textTertiary = argb(if (isLight) 0xFF64646C else 0xFF93939B),
            accentContainer = accentContainer,
            onAccentContainer = argb(if (isLight) 0xFF5A2508 else 0xFFFFD9C2),
            // Section 2.5: the light theme's shadow is black at 8 %; the dark themes draw none that shows (black at 12 % on a near-black page).
            shadow = if (isLight) 0x14000000 else 0x1F000000,
            scrim = argb(0xFF000000),
            track = if (isLight) 0x29000000 else 0x38FFFFFF,
            outline = when (theme) { Theme.DARK -> 0x14FFFFFF; Theme.AMOLED -> 0x1AFFFFFF; Theme.LIGHT -> 0x17000000 },
            // The dock's tint under blur: surface1 at 78 % (dark, AMOLED), bg at 82 % (light), section 2.6.
            glass = if (isLight) (0xD1 shl 24) or (bg and 0xFFFFFF) else (0xC7 shl 24) or (surface1 and 0xFFFFFF),
            skelBase = surface2,
            skelShine = surface3,
            danger = argb(if (isLight) 0xFFC62828 else 0xFFFF8A80),
            onDanger = argb(if (isLight) 0xFFFFFFFF else 0xFF2A0A08),
            dangerContainer = argb(if (isLight) 0xFFFDE2E0 else 0xFF3B1C1A),
            onDangerContainer = argb(if (isLight) 0xFF7A1212 else 0xFFFFD6D2),
            dangerClash = false,
        )
    }
}
