package com.cosmotune.video.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_off
import com.cosmotune.video.resources.video_on
import com.cosmotune.video.resources.video_smooth_anime
import com.cosmotune.video.resources.video_smooth_auto
import com.cosmotune.video.resources.video_smooth_blend
import com.cosmotune.video.resources.video_smooth_film
import com.cosmotune.video.resources.video_smooth_match_display
import com.cosmotune.video.resources.video_smooth_match_display_note
import com.cosmotune.video.resources.video_smooth_only_slow_short
import com.cosmotune.video.resources.video_smooth_preset
import com.cosmotune.video.resources.video_smooth_smooth
import com.cosmotune.video.resources.video_smooth_soap_note
import com.cosmotune.video.resources.video_smooth_strength
import com.cosmotune.video.resources.video_smooth_target
import com.cosmotune.video.resources.video_smooth_target_display
import com.cosmotune.video.resources.video_smooth_target_fps
import com.cosmotune.video.resources.video_smooth_unavailable
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothPreset
import com.cosmotune.video.settings.SmoothnessSettings
import com.cosmotune.video.ui.settings.SmoothNote
import com.cosmotune.video.ui.settings.readoutText
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The Smoothness page of the player's settings: the mode as three choices (Off, Blend, Smooth), then the frame rate drawn, the
 * strength, "Only below 30 fps" and the kind of picture, each changing the stored settings at once, and the line that says what
 * is truly happening. On the phone, which cannot make frames, Blend and Smooth are listed as unavailable and the one real
 * control is shown: matching the screen's refresh rate to the video. [menu] picks the desktop menu's row size.
 */
@Composable
internal fun SmoothnessPanel(playback: VideoPlayback, menu: Boolean) {
    val graph = LocalVideoGraph.current
    val smooth = graph.settings.settings.collectAsState().value.smoothness
    val readout by playback.engine.smoothness.collectAsState()
    val canSmooth = remember(playback) { playback.engine.capabilities.frameSmoothing }
    val accent = Color(LocalCosmoColors.current.palette.accent)
    val on = stringResource(Res.string.video_on)
    val off = stringResource(Res.string.video_off)
    val change: ((SmoothnessSettings) -> SmoothnessSettings) -> Unit = { transform -> graph.settings.update { it.copy(smoothness = transform(it.smoothness)) } }

    @Composable
    fun pick(label: String, selected: Boolean, enabled: Boolean = true, value: String? = null, onClick: () -> Unit) =
        CosmoActionRow(
            icon = if (selected) CosmoIcons.Check else BlankSmoothIcon,
            label = label,
            value = value,
            tint = if (selected) accent else null,
            enabled = enabled,
            menu = menu,
            onClick = onClick,
        )

    if (!canSmooth) {
        val unavailable = stringResource(Res.string.video_smooth_unavailable)
        pick(stringResource(Res.string.video_smooth_blend), selected = false, enabled = false, value = unavailable) {}
        pick(stringResource(Res.string.video_smooth_smooth), selected = false, enabled = false, value = unavailable) {}
        CosmoActionRow(
            icon = CosmoIcons.Equaliser,
            label = stringResource(Res.string.video_smooth_match_display),
            value = if (smooth.matchDisplayRate) on else off,
            menu = menu,
        ) { change { it.copy(matchDisplayRate = !it.matchDisplayRate) } }
        SmoothNote(stringResource(Res.string.video_smooth_match_display_note))
        return
    }

    pick(off, selected = smooth.mode == SmoothMode.OFF) { change { it.copy(mode = SmoothMode.OFF) } }
    pick(stringResource(Res.string.video_smooth_blend), selected = smooth.mode == SmoothMode.BLEND) { change { it.copy(mode = SmoothMode.BLEND) } }
    pick(stringResource(Res.string.video_smooth_smooth), selected = smooth.mode == SmoothMode.SMOOTH) { change { it.copy(mode = SmoothMode.SMOOTH) } }
    SmoothNote(readoutText(smooth, readout))
    if (smooth.mode == SmoothMode.SMOOTH) SmoothNote(stringResource(Res.string.video_smooth_soap_note))
    val active = smooth.mode != SmoothMode.OFF
    val display = stringResource(Res.string.video_smooth_target_display)
    CosmoActionRow(
        icon = CosmoIcons.Equaliser,
        label = stringResource(Res.string.video_smooth_target),
        value = if (smooth.targetFps == 0) display else stringResource(Res.string.video_smooth_target_fps, smooth.targetFps),
        enabled = active,
        menu = menu,
    ) {
        val choices = SmoothnessSettings.TARGET_CHOICES
        change { it.copy(targetFps = choices[(choices.indexOf(it.targetFps) + 1) % choices.size]) }
    }
    CosmoActionRow(
        icon = BlankSmoothIcon,
        label = stringResource(Res.string.video_smooth_strength),
        value = "${smooth.strength} %",
        enabled = active,
        menu = menu,
    ) {}
    Box(Modifier.padding(horizontal = SLIDER_SIDE_DP.dp)) {
        CosmoSlider(
            value = smooth.strength / 100f,
            onValueChange = { value -> change { it.copy(strength = (value * 100f).roundToInt().coerceIn(0, 100)) } },
            contentDescription = stringResource(Res.string.video_smooth_strength),
        )
    }
    CosmoActionRow(
        icon = BlankSmoothIcon,
        label = stringResource(Res.string.video_smooth_only_slow_short),
        value = if (smooth.onlyBelow30) on else off,
        enabled = active,
        menu = menu,
    ) { change { it.copy(onlyBelow30 = !it.onlyBelow30) } }
    val kinds = listOf(stringResource(Res.string.video_smooth_auto), stringResource(Res.string.video_smooth_film), stringResource(Res.string.video_smooth_anime))
    CosmoActionRow(
        icon = BlankSmoothIcon,
        label = stringResource(Res.string.video_smooth_preset),
        value = kinds[smooth.preset.ordinal],
        enabled = active,
        menu = menu,
    ) { change { it.copy(preset = SmoothPreset.entries[(it.preset.ordinal + 1) % SmoothPreset.entries.size]) } }
}

/** The mode in words for the main list's row: Off, Blend or Smooth; on the phone, whether the screen follows the video's rate. */
@Composable
internal fun smoothnessValue(smooth: SmoothnessSettings, canSmooth: Boolean): String = when {
    !canSmooth -> if (smooth.matchDisplayRate) stringResource(Res.string.video_on) else stringResource(Res.string.video_off)
    smooth.mode == SmoothMode.BLEND -> stringResource(Res.string.video_smooth_blend)
    smooth.mode == SmoothMode.SMOOTH -> stringResource(Res.string.video_smooth_smooth)
    else -> stringResource(Res.string.video_off)
}

private const val SLIDER_SIDE_DP = 22f

/** The empty icon of a row that is not ticked, so the labels line up. */
private val BlankSmoothIcon: ImageVector = ImageVector.Builder(
    name = "blank", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).build()
