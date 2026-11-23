package com.cosmotune.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.cosmotune.music.data.stats.Backup
import com.cosmotune.music.ui.theme.CosmotuneTheme
import kotlinx.coroutines.launch

/**
 * Rescue, from the launcher icon's long-press shortcut: a backup and the last crash note without opening the main screens, for
 * when Cosmotune will not open or keeps stopping (RiMusic and Kreate's Rescue Center).
 */
class RescueActivity : ComponentActivity() {
    private var status by mutableStateOf<String?>(null)
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            status = Backup.exportTo(this@RescueActivity, uri).fold({ "Backup saved." }, { "The backup could not be saved there. Try another place." })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            com.cosmotune.ui.theme.CosmoLookTheme(dark = dark, legacy = { CosmotuneTheme(darkTheme = dark, content = it) }) {
                com.cosmotune.ui.settings.RescueCenter(
                    onSaveBackup = { save.launch("cosmotune-backup.json") },
                    status = status,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                )
            }
        }
    }
}
