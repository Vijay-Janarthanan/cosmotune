package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.playback.EqLayout
import com.cosmotune.music.playback.EqPresetText
import com.cosmotune.music.playback.manualCurve
import com.cosmotune.music.playback.withFullHeadroom
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoAlertDialog

/**
 * Bring an AutoEQ preset in, or send the current bands out, as plain text. One dialog for phone and desktop; the host
 * supplies the file chooser and the clipboard. Nothing changes until "Use this preset".
 */
@Composable
fun EqPresetTransferDialog(
    bands: List<Float>,
    fullHeadroom: Boolean,
    openFile: ((String) -> Unit) -> Unit,
    saveFile: (String) -> Unit,
    copyText: (String) -> Unit,
    pasteText: () -> String?,
    onApply: (List<Float>) -> Unit,
    onDismiss: () -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val small = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { EqPresetText.parse(text) }
    val mapping = remember(parsed) { if (parsed.filters.isEmpty()) null else EqPresetText.mapToBands(parsed.filters) }
    val exported = remember(bands, fullHeadroom) {
        val curve = manualCurve(bands).let { if (fullHeadroom) it.withFullHeadroom() else it }
        EqPresetText.export(bands, curve.preampDb)
    }
    val status = when {
        text.isBlank() -> null
        mapping == null -> "No filters found in this text."
        else -> buildString {
            append("${parsed.filters.size} filter${if (parsed.filters.size == 1) "" else "s"} found")
            if (parsed.rejected.isNotEmpty()) append(", ${parsed.rejected.size} line${if (parsed.rejected.size == 1) "" else "s"} skipped")
            append(".")
            if (mapping.clipped) append(" Some bands reach the ${EqLayout.MANUAL_RANGE_DB.toInt()} dB limit.")
        }
    }
    CosmoAlertDialog(
        onDismissRequest = onDismiss,
        overline = "Equaliser",
        title = "Import or export a preset",
        confirmLabel = "Use this preset",
        confirmEnabled = mapping != null,
        onConfirm = { mapping?.let { onApply(it.bandsDb) } },
        dismissLabel = "Close",
        onDismiss = onDismiss,
        maxWidth = 520.dp,
    ) {
        val wide = Modifier.fillMaxWidth()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OrbitLabel("Import", fonts, look.dim)
            Text(
                "AutoEQ presets are fitted to the seven bands here, so they sound close, not exact.",
                style = small,
                color = look.dim,
            )
            OrbitTextField(
                text,
                { text = it.take(EqPresetText.MAX_TEXT_CHARS) },
                "Preamp: -6.2 dB, Filter 1: ON PK Fc 105 Hz Gain 2.3 dB Q 0.70",
                fonts,
                look,
                wide,
                singleLine = false,
                minLines = 3,
                maxLines = 6,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbitSheetButton("Open file", { openFile { text = it.take(EqPresetText.MAX_TEXT_CHARS) } }, Modifier.weight(1f))
                OrbitSheetButton("Paste", { pasteText()?.let { text = it.take(EqPresetText.MAX_TEXT_CHARS) } }, Modifier.weight(1f))
            }
            status?.let { Text(it, style = small, color = if (mapping == null) MaterialTheme.colorScheme.error else look.ink) }
            parsed.rejected.take(3).forEach {
                Text("Line ${it.lineNumber}: ${it.reason}", style = small, color = look.dim)
            }
            OrbitLabel("Export", fonts, look.dim)
            Text("Your seven bands, written as filters other equalisers can read.", style = small, color = look.dim)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbitSheetButton("Save to file", { saveFile(exported) }, Modifier.weight(1f))
                OrbitSheetButton("Copy", { copyText(exported) }, Modifier.weight(1f))
            }
        }
    }
}

/** Type an exact gain for one band, 12 dB either way. */
@Composable
fun EqBandValueDialog(
    frequencyLabel: String,
    current: Float,
    onSet: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val small = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
    var text by remember { mutableStateOf(if (current == current.toInt().toFloat()) current.toInt().toString() else current.toString()) }
    val value = EqPresetText.parseGain(text)
    val limit = EqLayout.MANUAL_RANGE_DB.toInt()
    CosmoAlertDialog(
        onDismissRequest = onDismiss,
        overline = "Equaliser",
        title = "$frequencyLabel Hz band",
        confirmLabel = "Set",
        confirmEnabled = value != null,
        onConfirm = { value?.let(onSet) },
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitTextField(
                text,
                { text = it.take(8) },
                "Gain in dB",
                fonts,
                look,
                Modifier.fillMaxWidth(),
                error = text.isNotBlank() && value == null,
                onDone = { value?.let(onSet) },
            )
            Text("From -$limit to +$limit dB.", style = small, color = look.dim)
        }
    }
}
