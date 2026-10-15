package com.cosmotune.ui.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import com.cosmotune.design.Intensity
import com.cosmotune.theme.CoverColors
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.CoverPaletteLoader
import com.cosmotune.ui.theme.LocalCosmoColors

/**
 * The colours Now Playing draws its text, buttons and scrubber with.
 *
 * With a video on screen Now Playing stays dark in both themes (`01-dynamic-colour.md` section 2): the scrim over the video is
 * dark whatever the theme, so ink from the light theme (L 0.20) would vanish on it. In the dark theme nothing needs doing and
 * the shell's own colours are returned. In the light theme the screen keeps a palette state of its own: it follows the
 * shell's palette while no video shows, and fades (700 ms, like any palette change) to the dark palette of the same cover once
 * [videoShown] (the clip has a frame on screen), and back when the video goes. The dark palette comes from the same engine
 * and the same cache as the shell's ([CoverPaletteLoader]), at the colour intensity of the setting (Settings > Appearance), like
 * the shell's; it is looked up as soon as the song has a video ([hasVideo]), so it is ready by the first frame. The page ground is
 * drawn with these colours too, so the fade is one thing.
 *
 * Without a shell (the new look is off) the inherited colours are returned untouched.
 */
@Composable
internal fun rememberPlayerColors(shell: CosmoShellState?, hasVideo: Boolean, videoShown: Boolean, reduced: Boolean): CosmoColors {
    val inherited = LocalCosmoColors.current
    if (shell == null) return inherited
    val target = inherited.palette           // read first: it changes once per cover, and with a theme flip
    return if (inherited.dark) inherited else rememberLightThemeColors(shell, target, hasVideo, videoShown, reduced)
}

/** What a search for the dark palette of a cover came to: not asked yet or still asking ([done] false), none, or the palette. */
private class DarkLookup(val done: Boolean, val palette: CoverColors?)

@Composable
private fun rememberLightThemeColors(shell: CosmoShellState, target: CoverColors, hasVideo: Boolean, videoShown: Boolean, reduced: Boolean): CosmoColors {
    val context = LocalPlatformContext.current
    val url = shell.coverUrl
    val own = remember(shell) { CosmoPaletteState(target, dark = false) }
    val colors = remember(own) { CosmoColors(own) }
    val intensityIndex by shell.settings.intensity.collectAsStateWithLifecycle()
    val intensity = Intensity.multiplier(intensityIndex)
    val lookup by produceState(DarkLookup(false, null), url, hasVideo, intensity) {
        value = DarkLookup(false, null)
        value = DarkLookup(true, if (hasVideo && url != null) CoverPaletteLoader.load(context, url, dark = true, intensity = intensity) else null)
    }
    LaunchedEffect(own, target, videoShown, lookup, reduced) {
        // Never show the dark brand colours for the second it takes to find the cover's own.
        if (videoShown && !lookup.done) return@LaunchedEffect
        val next = if (videoShown) lookup.palette ?: CosmoBrandPalettes.of(true) else target
        own.show(next, dark = videoShown, reducedMotion = reduced, animate = true)
    }
    return colors
}
