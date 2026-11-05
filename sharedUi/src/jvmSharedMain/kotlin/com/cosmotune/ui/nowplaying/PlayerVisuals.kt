package com.cosmotune.ui.nowplaying

import com.cosmotune.ui.theme.CosmoRadii
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cosmotune.music.data.MonotonicClock
import com.cosmotune.music.data.audio.AudioVisuals
import com.cosmotune.music.data.audio.VisualAudioFrame
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Desktop supplies its full-screen window; Android uses a full-window native dialog. */
val LocalVisualsPresentation = staticCompositionLocalOf<@Composable (() -> Unit, @Composable () -> Unit) -> Unit> {
    { dismiss, content ->
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
    }
}

/** Lives in the player's action row, never over the video or the transport controls. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlayerVisualsAction(song: Song, modifier: Modifier = Modifier, style: CosmoIconButtonStyle? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(Res.string.player_visuals)
    if (style != null) {
        CosmoIconButton(CosmoIcons.Waveform, label, { open = true }, modifier, style = style, size = 40.dp)
    } else {
        TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
            FilledTonalIconButton(onClick = { open = true }, modifier = modifier.size(48.dp)) {
                Icon(CosmoIcons.Waveform, contentDescription = label)
            }
        }
    }
    if (open) LocalVisualsPresentation.current({ open = false }) {
        PlayerVisualsContent(song, { open = false })
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun PlayerVisualsContent(song: Song, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val modes = listOf(stringResource(Res.string.player_visuals_artwork), stringResource(Res.string.player_visuals_pulse), stringResource(Res.string.player_visuals_waveform))
    BoxWithConstraints(modifier.fillMaxSize().testTag("player:visuals").background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
        val compact = maxHeight < 480.dp
        Column(Modifier.fillMaxSize().padding(if (compact) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.player_visuals), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(CosmoIcons.Close, stringResource(Res.string.player_visuals_close))
                }
            }
            FlowRow(Modifier.widthIn(max = 520.dp).fillMaxWidth().align(Alignment.CenterHorizontally).selectableGroup(),
                horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                modes.forEachIndexed { index, name ->
                    FilterChip(selected = selected == index, onClick = { selected = index },
                        label = { Text(name, maxLines = 2) }, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("player:visuals:scene"), contentAlignment = Alignment.Center) {
                if (selected == 0) {
                    val side = minOf(maxWidth, maxHeight).coerceAtMost(560.dp)
                    Box(Modifier.size(side).clip(RoundedCornerShape(CosmoRadii.rMd)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(CosmoIcons.Waveform, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        AsyncImage(song.thumbnailUrl, contentDescription = song.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                } else AudioScene(selected)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BoxScope.AudioScene(selected: Int) {
    val frame by AudioVisuals.frame.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(MonotonicClock.nowMs()) }
    LaunchedEffect(Unit) { while (true) { now = MonotonicClock.nowMs(); delay(500) } }
    val fresh = frame.atMs > 0 && now - frame.atMs < 1500
    val reduced = LocalReducedMotion.current
    val accent = MaterialTheme.colorScheme.primary
    VisualAudioScene(if (fresh && !reduced) frame else VisualAudioFrame(), selected == 1, accent, Modifier.fillMaxSize())
    if (!fresh) Text(stringResource(Res.string.player_visuals_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant,
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
