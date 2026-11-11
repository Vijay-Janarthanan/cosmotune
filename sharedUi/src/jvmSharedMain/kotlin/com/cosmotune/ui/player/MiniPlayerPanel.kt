package com.cosmotune.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.theme.OnePalette
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors

/**
 * The compact mini player's contents in Orbit, sized for the desktop's small always-on-top window: the artwork as a disc inside the
 * orbit ring, whose arc and moon are the song's [progress] (peach: it is what plays), the title and the artist in Orbit's faces, and
 * the round previous and next buttons beside the play button, with the round button that opens Cosmotune at the end. The window stands
 * outside the app's theme, so the panel brings Orbit's own palette for [dark] (the system's by default). The host supplies the
 * artwork loader.
 */
@Composable
fun MiniPlayerPanel(
    title: String, artist: String, playing: Boolean, progress: Float,
    artwork: @Composable (Modifier) -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onOpen: () -> Unit,
    dark: Boolean = isSystemInDarkTheme(),
    /** The playhead run on between readings, drawn instead of [progress] so the ring glides; the host supplies it when it has the clock. */
    liveProgress: (() -> Float)? = null,
) {
    val colors = remember(dark) { CosmoColors(CosmoPaletteState(OnePalette.of(dark), dark)) }
    CompositionLocalProvider(LocalCosmoColors provides colors) {
        val fonts = orbitFonts()
        val look = orbitLook()
        Box(Modifier.fillMaxSize().background(look.ground).testTag("mini:root")) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    artwork(Modifier.size(80.dp).clip(CircleShape))
                    OrbitRing(96.dp, look.accent, progress = { (liveProgress?.invoke() ?: progress).coerceIn(0f, 1f) }, spinning = playing)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(title.ifBlank { "Nothing playing" }, style = fonts.title, color = look.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("mini:title"))
                    Text(artist, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrbitRoundButton(CosmoIcons.Previous, "Previous", look.ink, onPrevious, Modifier.testTag("mini:previous"), size = 36.dp)
                        OrbitPlayButton(playing, onPlayPause, look, Modifier.testTag("mini:playpause"), size = 42.dp)
                        OrbitRoundButton(CosmoIcons.Next, "Next", look.ink, onNext, Modifier.testTag("mini:next"), size = 36.dp)
                        Spacer(Modifier.weight(1f))
                        OrbitRoundButton(Icons.Rounded.OpenInNew, "Open Cosmotune", look.dim, onOpen, Modifier.testTag("mini:open"), size = 36.dp)
                    }
                }
            }
        }
    }
}
