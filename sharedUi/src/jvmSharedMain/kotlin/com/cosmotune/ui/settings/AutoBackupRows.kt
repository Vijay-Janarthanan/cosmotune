package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.AutoBackup
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

const val AUTO_BACKUP_TITLE = "Back up automatically"
const val AUTO_BACKUP_LINE = "A backup every day or week in a folder you choose; the newest ${AutoBackup.KEEP} are kept"

/**
 * Your data's automatic backups: Off, Every day or Every week, and the folder they go to ([folderLabel], chosen through
 * [onChooseFolder]). Choosing a schedule before a folder asks for the folder first. [last] is when the last one was made.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoBackupRows(folderLabel: String?, last: String?, onChooseFolder: () -> Unit) {
    val look = rememberCosmoLookSettings()
    val fonts = orbitFonts()
    val ink = orbitLook()
    val days by look.autoBackupDays.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AUTO_BACKUP_TITLE, style = fonts.title, color = ink.ink)
        Text(AUTO_BACKUP_LINE, style = fonts.body, color = ink.dim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoBackup.CHOICES.forEach { choice ->
                OrbitSelectChip(AutoBackup.label(choice), days == choice, fonts, ink, onClick = {
                    look.setAutoBackupDays(choice)
                    if (choice > 0 && folderLabel == null) onChooseFolder()
                }, haptic = false)
            }
        }
        if (days > 0 || folderLabel != null) {
            Text(
                listOfNotNull(folderLabel?.let { "Saved in $it" } ?: "No folder chosen yet", last?.let { "last made $it" }).joinToString(" · "),
                style = fonts.body, color = ink.dim, modifier = Modifier.padding(top = 4.dp),
            )
            OrbitActionPill(if (folderLabel == null) "Choose a folder" else "Change folder", onChooseFolder, fonts, ink, Modifier.padding(top = 4.dp))
        }
    }
}
