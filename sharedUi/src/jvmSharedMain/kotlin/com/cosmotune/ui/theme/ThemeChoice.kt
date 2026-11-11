package com.cosmotune.ui.theme

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app's theme as the person chose it: follow the system, or always light, or always dark. The phone keeps it in its own
 * settings (`AppSettings.themeMode`), the desktop in `DesktopAppearanceSettings.themeMode`; both hand it to the shared chrome
 * (the profile drawer's quick toggle, the desktop's account popover) through [LocalThemeChoice].
 */
enum class ThemeChoice {
    SYSTEM, LIGHT, DARK;

    /** The round button's order: System, then Light, then Dark, then System again. */
    fun next(): ThemeChoice = entries[(ordinal + 1) % entries.size]

    /**
     * Whether this choice draws the dark theme, given whether the system is dark now. [videoFirst] is Video being the mode: Video is
     * dark first, so while it is the mode "follow the system" is dark too; only an explicit Light makes it light.
     */
    fun isDark(systemDark: Boolean, videoFirst: Boolean = false): Boolean = when (this) {
        SYSTEM -> systemDark || videoFirst
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** A stored name read back; anything that is not a choice (nothing stored yet, an old value) is [SYSTEM]. */
        fun fromStored(stored: String?): ThemeChoice = entries.firstOrNull { it.name == stored } ?: SYSTEM
    }
}

/**
 * Where the theme choice lives on this platform: [collect] reads it in composition (and recomposes when it changes), [choose]
 * stores a new one. The app installs one at its root ([CosmoLookTheme]'s `themeChoice`); what it provides through
 * [LocalThemeChoice] stores through a short cross-fade, so the quick toggle and the Settings row change the same setting the same way.
 */
@Stable
interface ThemeChoiceHost {
    @Composable fun collect(): ThemeChoice
    fun choose(choice: ThemeChoice)
}

/** The app's theme choice, for the chrome that offers to change it; null where the app installed none (a preview, the TV). */
val LocalThemeChoice = staticCompositionLocalOf<ThemeChoiceHost?> { null }

/** The cross-fade of a theme change, for a window of its own (the profile drawer) to join with [themeFadeSurface]. */
internal val LocalThemeFade = staticCompositionLocalOf<ThemeFade?> { null }

/** How long the old theme takes to fade out over the new one. */
private const val THEME_FADE_MS = OneMotion.MEDIUM_MS

/**
 * The cross-fade of a theme change, with no second composition of the app: each surface that joined ([themeFadeSurface]: the
 * window's root, the profile drawer's window) records its last frame into a layer, the frame is kept as a picture, the choice is
 * stored, and the picture of the old theme is drawn over the new one fading out over [THEME_FADE_MS]. Under reduced motion, or
 * with nothing to fade (no surface, a picture that could not be taken), the new theme is simply there. Main thread only.
 */
@Stable
internal class ThemeFade {
    private val surfaces = mutableListOf<FadeSurface>()
    var alpha by mutableFloatStateOf(0f)
        private set
    private var running = false

    fun join(surface: FadeSurface) { surfaces += surface }
    fun leave(surface: FadeSurface) { surfaces -= surface }

    fun run(scope: CoroutineScope, reduced: Boolean, change: () -> Unit) {
        // A second change while the first still fades is applied at once: the fade on screen carries on to the newest theme.
        if (reduced || running || surfaces.isEmpty()) { change(); return }
        running = true
        val taking = surfaces.toList()
        scope.launch {
            try {
                taking.forEach { it.recording = true }
                // One frame records the surfaces into their layers; the pictures are taken from that frame.
                withFrameNanos { }
                withFrameNanos { }
                taking.forEach { surface -> surface.picture = runCatching { surface.layer.toImageBitmap() }.getOrNull() }
                alpha = 1f
                change()
                animate(1f, 0f, animationSpec = tween(THEME_FADE_MS, easing = OneMotion.Standard)) { value, _ -> alpha = value }
            } finally {
                // Whatever happened (the scope ended mid-fade, a picture failed), the surfaces go back to drawing straight through.
                taking.forEach { it.picture = null; it.recording = false }
                alpha = 0f
                running = false
            }
        }
    }
}

/** A surface of [ThemeFade]: its layer, whether it is being recorded, and the picture of the old theme while that fades. */
@Stable
internal class FadeSurface(val layer: GraphicsLayer) {
    var recording by mutableStateOf(false)
    var picture by mutableStateOf<ImageBitmap?>(null)
}

/**
 * Makes this the surface a theme change cross-fades ([ThemeFade]). It draws straight through except during a change, when its
 * content goes through a layer for a frame and the picture of the old theme is drawn over it while it fades. Nothing where no
 * theme choice is installed.
 */
@Composable
internal fun Modifier.themeFadeSurface(): Modifier {
    val fade = LocalThemeFade.current ?: return this
    val layer = rememberGraphicsLayer()
    val surface = remember(layer) { FadeSurface(layer) }
    DisposableEffect(fade, surface) {
        fade.join(surface)
        onDispose { fade.leave(surface) }
    }
    return drawWithContent {
        if (surface.recording) {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        } else {
            drawContent()
        }
        surface.picture?.let { drawImage(it, alpha = fade.alpha.coerceIn(0f, 1f)) }
    }
}

/**
 * The root of a theme choice: provides [LocalThemeChoice] (the [host], whose [ThemeChoiceHost.choose] goes through the cross-fade)
 * and makes its own box the window's fade surface. [reduced] is read when a choice is made, so a change of the reduced-motion
 * setting needs no new host. A box that changes no constraint, around the whole app.
 */
@Composable
internal fun ThemeChoiceRoot(host: ThemeChoiceHost?, reduced: Boolean, content: @Composable () -> Unit) {
    val fade = remember { ThemeFade() }
    val scope = rememberCoroutineScope()
    val reducedNow by rememberUpdatedState(reduced)
    val fading = remember(host, fade, scope) {
        host?.let { stored ->
            object : ThemeChoiceHost {
                @Composable override fun collect(): ThemeChoice = stored.collect()
                override fun choose(choice: ThemeChoice) = fade.run(scope, reducedNow) { stored.choose(choice) }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalThemeChoice provides fading, LocalThemeFade provides fade.takeIf { host != null }) {
        Box(Modifier.themeFadeSurface(), propagateMinConstraints = true) { content() }
    }
}
