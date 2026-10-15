package com.cosmotune.theme

object PaletteChoice {
    /**
     * What the app should head for next, or null to keep the palette it has.
     *
     * - [fresh] is the palette of the new cover, or null when it could not be loaded (still loading, offline, bad picture):
     *   the palette on screen is kept, unless it belongs to the other theme, then the [brand] one stands in.
     * - [coverMissing] is a song with no cover at all: the brand palette.
     * - [currentMatchesTheme] is whether [current] was made for the theme now in force.
     * - [sameAlbum] lets two tracks of one album differ more before the app re-themes ([CoverPalette.settle]).
     */
    fun next(
        current: CoverColors,
        currentMatchesTheme: Boolean,
        fresh: CoverColors?,
        coverMissing: Boolean,
        brand: CoverColors,
        sameAlbum: Boolean = false,
    ): CoverColors? {
        val wanted = when {
            coverMissing -> brand
            fresh == null -> return if (currentMatchesTheme) null else brand
            else -> fresh
        }
        if (!currentMatchesTheme) return wanted
        val settled = CoverPalette.settle(current, wanted, sameAlbum)
        return if (settled == current) null else settled
    }
}
