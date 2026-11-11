package com.cosmotune.ui.settings

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.cosmotune.diagnostics.CrashNotes
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.OrbitSpace

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashNoteDialog(onDismiss: () -> Unit) {
    val note by CrashNotes.note.collectAsState()
    val clipboard = LocalClipboardManager.current
    com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = onDismiss, title = "Last crash note",
        message = "Kept on this device until you choose to copy it. Exception messages and media URLs are excluded.", dismissLabel = "Done") {
        Text(note ?: "No crash has been recorded.", modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
        if (note != null) {
            // The note's two actions side by side, a text-to-action gap under the note rather than touching it and each other.
            FlowRow(
                Modifier.padding(top = OrbitSpace.textToAction),
                horizontalArrangement = Arrangement.spacedBy(OrbitSpace.chipGap),
                verticalArrangement = Arrangement.spacedBy(OrbitSpace.chipGap),
            ) {
                OrbitSheetButton("Copy diagnostic note", { clipboard.setText(AnnotatedString(note.orEmpty())) })
                OrbitSheetButton("Delete note", CrashNotes::clear, primary = false)
            }
        }
    }
}
