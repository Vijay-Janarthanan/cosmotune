package com.cosmotune.music.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import java.io.File

/**
 * Rescue on the desktop (RiMusic and Kreate's Rescue Center): a start that does not reach a working window counts, and after two in
 * a row the next start opens Rescue first (a backup, the last crash note) instead of the app; `--rescue` opens it on purpose.
 */
internal object DesktopRescue {
    const val FAILED_STARTS = 2
    private val file: File get() = File(com.cosmotune.desktop.DesktopWatch.dataDir.parentFile, "rescue-starts")

    /** Whether Rescue opens now: asked for, or [FAILED_STARTS] starts in a row never got going. */
    fun due(args: Array<String>, unfinishedStarts: Int): Boolean = "--rescue" in args || unfinishedStarts >= FAILED_STARTS

    /** Counts this start as unfinished until [started] clears it; returns how many were unfinished before it. */
    fun beginStart(): Int {
        val before = runCatching { file.readText().trim().toInt() }.getOrDefault(0).coerceIn(0, 99)
        runCatching { file.parentFile?.mkdirs(); file.writeText((before + 1).toString()) }
        return before
    }

    /** The app is up and playing its part: the count starts again. */
    fun started() { runCatching { file.delete() } }

    /** Shows Rescue and returns when the listener opens Cosmotune or closes the window. */
    fun show() {
        application(exitProcessOnExit = false) {
            var status by remember { mutableStateOf<String?>(null) }
            Window(onCloseRequest = ::exitApplication, title = "Cosmotune rescue", state = rememberWindowState(size = DpSize(520.dp, 520.dp))) {
                val scope = rememberCoroutineScope()
                val dark = rememberDesktopDark()
                androidx.compose.material3.MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                    com.cosmotune.ui.orbit.OrbitWindowColours(dark) {
                        com.cosmotune.ui.settings.RescueCenter(
                            onSaveBackup = {
                                scope.launch {
                                    val folder = DesktopAutoBackup.chooseFolder("") ?: return@launch
                                    status = if (DesktopAutoBackup.backUp(folder)) "Backup saved in ${folder.path}." else "The backup could not be saved there. Try another folder."
                                }
                            },
                            status = status,
                            onOpenApp = { started(); exitApplication() },
                        )
                    }
                }
            }
        }
    }
}
