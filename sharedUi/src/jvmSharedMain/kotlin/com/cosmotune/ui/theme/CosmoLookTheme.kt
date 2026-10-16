package com.cosmotune.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import com.cosmotune.theme.PaletteChoice
import com.cosmotune.ui.components.FocusModalityRoot
import com.cosmotune.ui.settings.rememberCosmoLookSettings
import kotlinx.coroutines.delay

/** The new look's state while the switch is on; null while it is off, so old call sites keep the old chrome. */
val LocalCosmoShell = staticCompositionLocalOf<CosmoShellState?> { null }
val LocalCosmoColors = staticCompositionLocalOf<CosmoColors> { error("CosmoLookTheme is not installed") }
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * The app theme with the new look switch in it. [legacy] is the old theme call (BitChordTheme on the
 * phone, the desktop MaterialTheme). The structure below is the same whether the switch is on or off,
 * so turning it on in Settings keeps every remembered state of the app.
 *
 * With the switch on, the old screens also take the new look through Material: the cover palette as the colour scheme
 * ([toColorScheme]), the type, the corners and the ripple ([toMaterialTypography], [CosmoMaterialShapes], [CosmoRipple]), and
 * the ambient text colour as the content colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CosmoLookTheme(
    dark: Boolean,
    legacy: @Composable (content: @Composable () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val settings = rememberCosmoLookSettings()
    val newLook by settings.newLook.collectAsStateWithLifecycle()
    val shell = remember(settings) { CosmoShellState(settings, dark) }
    val reduced = rememberReducedMotion()
    PaletteFollower(shell, newLook, dark, reduced)
    legacy {
        val legacyScheme = MaterialTheme.colorScheme
        val legacyTypography = MaterialTheme.typography
        val legacyShapes = MaterialTheme.shapes
        val target = shell.palette.target
        val type = rememberCosmoType()
        val scheme = if (newLook) remember(target, legacyScheme) { target.toColorScheme(legacyScheme) } else legacyScheme
        MaterialTheme(
            colorScheme = scheme,
            typography = if (newLook) remember(type, legacyTypography) { type.toMaterialTypography(legacyTypography) } else legacyTypography,
            shapes = if (newLook) CosmoMaterialShapes else legacyShapes,
        ) {
            // The locals below are provided whether the switch is on or off (their values differ), never `content` itself
            // inside an `if`: another call tree would throw away the app's state when the switch flips. The box around them is there
            // in both cases too; with the switch on it also tells the focus rings whether the keyboard is in use.
            FocusModalityRoot(enabled = newLook) {
                CompositionLocalProvider(
                    LocalCosmoType provides type,
                    LocalCosmoShell provides (if (newLook) shell else null),
                    LocalCosmoColors provides shell.colors,
                    LocalReducedMotion provides reduced,
                    // The ambient ground's text colour for an untinted icon or text; Material surfaces still set their own.
                    LocalContentColor provides (if (newLook) Color(target.textPrimary) else LocalContentColor.current),
                    LocalRippleConfiguration provides (if (newLook) CosmoRipple else LocalRippleConfiguration.current),
                    content = content,
                )
            }
        }
    }
}

/**
 * How long a missing cover address is waited for before the app goes to the brand colours. A song's cover can lack its address
 * for a moment (a cover inside a WebDAV or SMB file is still being pulled out of it; the player is still connecting at launch;
 * the queue is changing), and sending the whole app to the brand colours and back, 700 ms each way, is a flicker.
 */
private const val COVER_GRACE_MS = 1_500L

/**
 * Keeps the palette on the cover that is playing. Its own scope: the cover reads recompose only this function, which draws
 * nothing, so a new song costs the old screens no recomposition.
 *
 * What it must not do is flicker (`PaletteChoice`): a cover still loading or failing keeps the colours on screen, a cover
 * address that is still being resolved is waited for ([COVER_GRACE_MS]) instead of sending the app to the brand colours and
 * back, and two tracks of one album that look alike do not re-theme. A fade that an early restart of the effect cut short is
 * always brought to its end. A change of the colour intensity (Settings > Appearance) loads the palette again at the new multiplier
 * and shows it as it is: the two palettes of one cover differ by less than the same-album settle threshold, which would keep the
 * old one.
 */
@Composable
private fun PaletteFollower(shell: CosmoShellState, newLook: Boolean, dark: Boolean, reduced: Boolean) {
    val context = LocalPlatformContext.current
    val coverColours by shell.settings.coverColours.collectAsStateWithLifecycle()
    val intensityIndex by shell.settings.intensity.collectAsStateWithLifecycle()
    val intensity = com.cosmotune.design.Intensity.multiplier(intensityIndex)
    val url = shell.coverUrl
    val album = shell.coverAlbum
    val reducedNow by rememberUpdatedState(reduced)
    var showedCover by remember { mutableStateOf(false) }
    var lastAlbum by remember { mutableStateOf<String?>(null) }
    var shownIntensity by remember { mutableStateOf(intensity) }
    LaunchedEffect(newLook, coverColours, dark, url, intensity) {
        if (!newLook) return@LaunchedEffect
        // No address yet: give it time, unless the palette on screen is the other theme's, which cannot wait.
        if (coverColours && url == null && shell.palette.targetDark == dark) delay(COVER_GRACE_MS)
        val missing = !coverColours || url == null
        val fresh = if (missing) null else CoverPaletteLoader.load(context, url!!, dark, intensity)
        val sameAlbum = album != null && album == lastAlbum
        // The intensity changed since the palette on screen was made: its twin for this cover is shown even though it is close.
        val next = if (fresh != null && shownIntensity != intensity) fresh
        else PaletteChoice.next(shell.palette.target, shell.palette.targetDark == dark, fresh, missing, CosmoBrandPalettes.of(dark), sameAlbum)
        if (fresh != null) shownIntensity = intensity
        // The first cover after launch snaps; after that every change fades. Both flags are set before the fade, which a
        // restart of this effect may cut short.
        val animate = showedCover
        if (fresh != null) showedCover = true
        lastAlbum = album
        if (next != null) shell.palette.show(next, dark, reducedNow, animate) else shell.palette.finishFade(reducedNow, animate)
    }
    val nextUrl = shell.nextCoverUrl
    LaunchedEffect(newLook, coverColours, dark, nextUrl, intensity) {
        // Warms the cache, so the next fade starts with the audio, not after the cover loads.
        if (newLook && coverColours && nextUrl != null) CoverPaletteLoader.load(context, nextUrl, dark, intensity)
    }
}
