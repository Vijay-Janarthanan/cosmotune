package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.MonotonicClock
import com.cosmotune.music.data.audio.AudioVisuals
import com.cosmotune.music.data.audio.VisualAudioFrame
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Desktop supplies its full-screen window; Android uses a full-window native dialog. */
val LocalVisualsPresentation = staticCompositionLocalOf<@Composable (() -> Unit, @Composable () -> Unit) -> Unit> {
    { dismiss, content ->
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
    }
}

/** Lives in the player's action row, never over the video or the transport controls: the Orbit round button, or [style]'s. */
@Composable
fun PlayerVisualsAction(song: Song, modifier: Modifier = Modifier, style: CosmoIconButtonStyle? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(Res.string.player_visuals)
    if (style != null) {
        CosmoIconButton(CosmoIcons.Waveform, label, { open = true }, modifier, style = style, size = 40.dp)
    } else {
        OrbitRoundButton(CosmoIcons.Waveform, label, orbitLook().ink, { open = true }, modifier, size = 48.dp)
    }
    if (open) LocalVisualsPresentation.current({ open = false }) {
        PlayerVisualsContent(song, { open = false })
    }
}

/**
 * The full-screen Visuals in Orbit: the page's own ground, the heading in the wide face with the round close button, the three
 * scenes as Orbit chips (one solid, the one showing), and the scene: the artwork as a disc, or the pulse and the waveform in the ink.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PlayerVisualsContent(song: Song, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val modes = listOf(stringResource(Res.string.player_visuals_artwork), stringResource(Res.string.player_visuals_pulse), stringResource(Res.string.player_visuals_waveform))
    val fonts = orbitFonts()
    val look = orbitLook()
    BoxWithConstraints(modifier.fillMaxSize().testTag("player:visuals").background(look.ground).safeDrawingPadding()) {
        val compact = maxHeight < 480.dp
        Column(Modifier.fillMaxSize().padding(if (compact) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.player_visuals), style = fonts.section, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                OrbitRoundButton(CosmoIcons.Close, stringResource(Res.string.player_visuals_close), look.ink, onDismiss, size = 48.dp)
            }
            FlowRow(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().align(Alignment.CenterHorizontally).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                modes.forEachIndexed { index, name ->
                    val on = selected == index
                    OrbitSelectChip(name, on, fonts, look, { selected = index }, Modifier.semantics { this.selected = on; role = Role.Tab })
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("player:visuals:scene"), contentAlignment = Alignment.Center) {
                if (selected == 0) {
                    val side = minOf(maxWidth, maxHeight).coerceAtMost(560.dp)
                    Box(Modifier.size(side).clip(CircleShape).background(look.chip).semantics { contentDescription = song.title }, contentAlignment = Alignment.Center) {
                        Icon(CosmoIcons.Waveform, null, Modifier.size(72.dp), tint = look.dim)
                        Disc(song.thumbnailUrl, side)
                    }
                } else AudioScene(selected, look.ink, look.dim, fonts.body)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.title, style = if (compact) fonts.title.copy(fontSize = 17.sp) else fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 26.sp),
                    color = look.ink, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(song.artist, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BoxScope.AudioScene(selected: Int, ink: Color, dim: Color, style: androidx.compose.ui.text.TextStyle) {
    val frame by AudioVisuals.frame.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(MonotonicClock.nowMs()) }
    LaunchedEffect(Unit) { while (true) { now = MonotonicClock.nowMs(); delay(500) } }
    val fresh = frame.atMs > 0 && now - frame.atMs < 1500
    val reduced = LocalReducedMotion.current
    VisualAudioScene(if (fresh && !reduced) frame else VisualAudioFrame(), selected == 1, ink, Modifier.fillMaxSize())
    if (!fresh) Text(stringResource(Res.string.player_visuals_waiting), style = style, color = dim,
        textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
}

@Composable
private fun VisualAudioScene(frame: VisualAudioFrame, pulse: Boolean, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (pulse) {
            val radius = size.minDimension * (.14f + frame.energy * .3f)
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .4f), Color.Transparent), radius = radius * 2), radius * 2)
            repeat(3) { i -> drawCircle(accent.copy(alpha = .65f / (i + 1)), radius + i * 18.dp.toPx(), style = Stroke(2.dp.toPx())) }
        } else {
            val path = Path()
            frame.wave.forEachIndexed { i, value ->
                val x = size.width * i / (frame.wave.size - 1)
                val y = size.height / 2 + value * size.height * .35f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent, style = Stroke(3.dp.toPx()))
        }
    }
}
