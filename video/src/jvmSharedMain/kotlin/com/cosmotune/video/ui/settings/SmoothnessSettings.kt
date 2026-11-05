package com.cosmotune.video.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.settings.CosmoRowDivider
import com.cosmotune.ui.settings.CosmoSettingsGroup
import com.cosmotune.ui.settings.CosmoSettingsInsets
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.settings.CosmoSettingsSegmented
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_off
import com.cosmotune.video.resources.video_smooth_anime
import com.cosmotune.video.resources.video_smooth_auto
import com.cosmotune.video.resources.video_smooth_blend
import com.cosmotune.video.resources.video_smooth_experimental
import com.cosmotune.video.resources.video_smooth_experimental_note
import com.cosmotune.video.resources.video_smooth_film
import com.cosmotune.video.resources.video_smooth_match_display
import com.cosmotune.video.resources.video_smooth_match_display_note
import com.cosmotune.video.resources.video_smooth_mode
import com.cosmotune.video.resources.video_smooth_only_slow
import com.cosmotune.video.resources.video_smooth_preset
import com.cosmotune.video.resources.video_smooth_smooth
import com.cosmotune.video.resources.video_smooth_soap_note
import com.cosmotune.video.resources.video_smooth_status
import com.cosmotune.video.resources.video_smooth_status_none
import com.cosmotune.video.resources.video_smooth_strength
import com.cosmotune.video.resources.video_smooth_strength_note
import com.cosmotune.video.resources.video_smooth_target
import com.cosmotune.video.resources.video_smooth_target_display
import com.cosmotune.video.resources.video_smooth_target_fps
import com.cosmotune.video.resources.video_smooth_title
import com.cosmotune.video.resources.video_smooth_unavailable
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothPreset
import com.cosmotune.video.settings.SmoothnessReadout
import com.cosmotune.video.settings.SmoothnessSettings
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The Smoothness group of the settings page: the mode (Off, Blend, Smooth), the frame rate drawn, the strength, "Only when the
 * video is below 30 fps", the kind of picture, a plain note about the soap opera effect, and below them what is truly happening
 * ([readout]). Where the engine makes the frames ([canSmooth]: the desktop app, and the phone on its GPU) the whole group is shown;
 * an engine that does not shows Blend and Smooth as unavailable and offers its one real control, matching the screen's refresh rate
 * to the video. [experimental] (the phone) labels the mode as such, explains it, and adds that same switch after the rest.
 */
@Composable
internal fun SmoothnessSettingsGroup(
    smooth: SmoothnessSettings,
    update: SettingsUpdate,
    canSmooth: Boolean,
    readout: SmoothnessReadout,
    experimental: Boolean = false,
) {
    val unavailable = stringResource(Res.string.video_smooth_unavailable)
    CosmoSettingsGroup(header = stringResource(Res.string.video_smooth_title)) {
        if (!canSmooth) {
            CosmoSettingsRow(icon = CosmoIcons.Equaliser, title = stringResource(Res.string.video_smooth_blend), subtitle = unavailable, enabled = false)
            CosmoSettingsRow(icon = CosmoIcons.Equaliser, title = stringResource(Res.string.video_smooth_smooth), subtitle = unavailable, enabled = false)
            CosmoRowDivider()
            SmoothSwitchRow(
                title = stringResource(Res.string.video_smooth_match_display),
                checked = smooth.matchDisplayRate,
                subtitle = stringResource(Res.string.video_smooth_match_display_note),
            ) { value -> update { it.copy(smoothness = it.smoothness.copy(matchDisplayRate = value)) } }
            return@CosmoSettingsGroup
        }
        val modes = listOf(stringResource(Res.string.video_off), stringResource(Res.string.video_smooth_blend), stringResource(Res.string.video_smooth_smooth))
        val display = stringResource(Res.string.video_smooth_target_display)
        val targets = SmoothnessSettings.TARGET_CHOICES.map { if (it == 0) display else stringResource(Res.string.video_smooth_target_fps, it) }
        val presets = listOf(stringResource(Res.string.video_smooth_auto), stringResource(Res.string.video_smooth_film), stringResource(Res.string.video_smooth_anime))
        val on = smooth.mode != SmoothMode.OFF
        CosmoSettingsRow(
            icon = CosmoIcons.Equaliser,
            title = stringResource(Res.string.video_smooth_mode),
            subtitle = if (experimental) stringResource(Res.string.video_smooth_experimental) else null,
        )
        CosmoSettingsSegmented(
            options = modes,
            selectedIndex = smooth.mode.ordinal,
            onSelect = { index -> update { it.copy(smoothness = it.smoothness.copy(mode = SmoothMode.entries[index])) } },
            modifier = segmentPadding(),
            compact = true,
        )
        SmoothNote(readoutText(smooth, readout))
        if (smooth.mode == SmoothMode.SMOOTH) SmoothNote(stringResource(Res.string.video_smooth_soap_note))
        if (experimental) SmoothNote(stringResource(Res.string.video_smooth_experimental_note))
        CosmoRowDivider()
        CosmoSettingsRow(title = stringResource(Res.string.video_smooth_target), enabled = on)
        CosmoSettingsSegmented(
            options = targets,
            selectedIndex = SmoothnessSettings.TARGET_CHOICES.indexOf(smooth.targetFps).coerceAtLeast(0),
            onSelect = { index -> update { it.copy(smoothness = it.smoothness.copy(targetFps = SmoothnessSettings.TARGET_CHOICES[index])) } },
            modifier = segmentPadding(),
            enabled = on,
            compact = true,
        )
        CosmoSettingsRow(title = stringResource(Res.string.video_smooth_strength), value = "${smooth.strength} %", enabled = on)
        Box(Modifier.fillMaxWidth().padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side)) {
            CosmoSlider(
                value = smooth.strength / 100f,
                onValueChange = { value -> update { it.copy(smoothness = it.smoothness.copy(strength = (value * 100f).roundToInt().coerceIn(0, 100))) } },
                contentDescription = stringResource(Res.string.video_smooth_strength),
            )
        }
        SmoothNote(stringResource(Res.string.video_smooth_strength_note))
        CosmoRowDivider()
        SmoothSwitchRow(title = stringResource(Res.string.video_smooth_only_slow), checked = smooth.onlyBelow30, enabled = on) { value ->
            update { it.copy(smoothness = it.smoothness.copy(onlyBelow30 = value)) }
        }
        CosmoSettingsRow(title = stringResource(Res.string.video_smooth_preset), enabled = on)
        CosmoSettingsSegmented(
            options = presets,
            selectedIndex = smooth.preset.ordinal,
            onSelect = { index -> update { it.copy(smoothness = it.smoothness.copy(preset = SmoothPreset.entries[index])) } },
            modifier = segmentPadding(),
            enabled = on,
            compact = true,
        )
        if (experimental) {
            CosmoRowDivider()
            SmoothSwitchRow(
                title = stringResource(Res.string.video_smooth_match_display),
                checked = smooth.matchDisplayRate,
                subtitle = stringResource(Res.string.video_smooth_match_display_note),
            ) { value -> update { it.copy(smoothness = it.smoothness.copy(matchDisplayRate = value)) } }
        }
    }
}

/**
 * What smoothing is doing, in one line: "24 → 60 fps · Smooth" while frames are really made, else the reason smoothing is not
 * used (a notice), else that nothing is converted. Nothing is written for a conversion that is not happening.
 */
@Composable
internal fun readoutText(smooth: SmoothnessSettings, readout: SmoothnessReadout): String {
    val label = readout.label()
    val head = stringResource(Res.string.video_smooth_status)
    return when {
        smooth.mode == SmoothMode.OFF -> ""
        label != null -> "$head: $label"
        readout.notice != null -> "$head: ${readout.notice}"
        else -> "$head: ${stringResource(Res.string.video_smooth_status_none)}"
    }
}

@Composable
private fun segmentPadding(): Modifier =
    Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 10.dp)

/** A row whose whole face is a switch at its end. */
@Composable
private fun SmoothSwitchRow(title: String, checked: Boolean, enabled: Boolean = true, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    CosmoSettingsRow(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        trailing = { CosmoSwitch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/** A line of explanation under a row, in the secondary colour. Nothing is drawn for an empty text. */
@Composable
internal fun SmoothNote(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        text = text,
        style = type.forText(type.body(TypeScale.BODY).copy(fontSize = NOTE_TEXT_SP.sp, lineHeight = (NOTE_TEXT_SP * NOTE_TEXT_LINE).sp), text),
        color = Color(palette.textSecondary),
        modifier = modifier.fillMaxWidth().padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 8.dp),
    )
}

private const val NOTE_TEXT_SP = 13f
private const val NOTE_TEXT_LINE = 1.35f
