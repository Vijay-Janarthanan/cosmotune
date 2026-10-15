package com.cosmotune.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.ui.settings.rememberCosmoLookSettings

/** The new look's state while the switch is on; null while it is off, so old call sites keep the old chrome. */
val LocalCosmoShell = staticCompositionLocalOf<CosmoShellState?> { null }
val LocalCosmoColors = staticCompositionLocalOf<CosmoColors> { error("CosmoLookTheme is not installed") }
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * The app theme with the new look switch in it. [legacy] is the old theme call (BitChordTheme on the
 * phone, the desktop MaterialTheme). The structure below is the same whether the switch is on or off,
 * so turning it on in Settings keeps every remembered state of the app.
 */
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
    // Task 3 replaces this effect with PaletteFollower (cover colours).
    LaunchedEffect(dark) { shell.palette.show(CosmoBrandPalettes.of(dark), dark, reduced, animate = false) }
    legacy {
        val legacyScheme = MaterialTheme.colorScheme
        val target = shell.palette.target
        val scheme = if (newLook) remember(target, legacyScheme) { target.toColorScheme(legacyScheme) } else legacyScheme
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(
                LocalCosmoType provides rememberCosmoType(),
                LocalCosmoShell provides (if (newLook) shell else null),
                LocalCosmoColors provides shell.colors,
                LocalReducedMotion provides reduced,
                content = content,
            )
        }
    }
}
