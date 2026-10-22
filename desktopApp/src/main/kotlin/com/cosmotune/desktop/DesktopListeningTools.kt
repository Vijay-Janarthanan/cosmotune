package com.cosmotune.desktop

import androidx.compose.runtime.Composable
import com.cosmotune.listening.*
import com.cosmotune.ui.listening.*
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.desktop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

@Composable
fun DesktopListeningTools(
    open: Boolean, onDismiss: () -> Unit, library: ListeningLibrary, queue: () -> ListeningQueue,
    createPlaylist: suspend (String, List<Song>) -> Unit,
    restore: suspend (ListeningSession) -> Unit, play: suspend (List<Song>) -> Unit,
    openRelease: (String, String) -> Unit, findMusic: () -> Unit, importMusic: () -> Unit,
    applySetup: (ListeningPreferences, Boolean) -> Unit, sourceSummary: String,
) {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: "${System.getProperty("user.home")}/.local/share"
    ListeningTools(open, ListeningToolsHost(
        file = File(base, "cosmotune/listening.json"), library = library, queue = queue,
        replay = { window ->
            val period = if (window == ReplayWindow.MONTH) DesktopReplayPeriod.THIS_MONTH else DesktopReplayPeriod.THIS_YEAR
            val summary = DesktopListeningStats.summary(period)
            ReplayTracks("Replay · ${summary.label}", summary.songs.map { it.song })
        }, createPlaylist = createPlaylist, restore = restore, play = play, openRelease = openRelease,
        findMusic = findMusic, importMusic = importMusic, applySetup = applySetup,
        exportFile = { name, text ->
            val target = chooseCosmotuneFile(name, saving = true)
            if (target == null) false else withContext(Dispatchers.IO) { target.writeText(text, Charsets.UTF_8); true }
        },
        importFile = {
            chooseCosmotuneFile("", saving = false)?.let { file -> withContext(Dispatchers.IO) {
                require(file.length() <= 16 * 1024 * 1024) { "Choose a listening backup smaller than 16 MB." }
                file.readText(Charsets.UTF_8)
            } }
        },
        mediaCheck = { song -> ListeningNetwork.timed("Media stream") {
            withTimeout(30_000) {
                val stream = DesktopSourceRegistry.resolve(song, null).getOrThrow()
                ListeningNetwork.readMedia(stream.url, stream.headers)
            }
        } },
        sourceSummary = sourceSummary, existingUser = library.songs.isNotEmpty(),
        languageChoices = DesktopStrings.available.map { it.tag to it.label },
    ), onDismiss)
}

internal suspend fun chooseCosmotuneFile(name: String, saving: Boolean): File? = withContext(Dispatchers.IO) {
    var selected: File? = null
    SwingUtilities.invokeAndWait {
        val dialog = FileDialog(null as Frame?, if (saving) "Save Cosmotune file" else "Import listening backup", if (saving) FileDialog.SAVE else FileDialog.LOAD)
        try {
            if (name.isNotBlank()) dialog.file = name
            dialog.isVisible = true
            dialog.file?.let { selected = File(dialog.directory, it) }
        } finally { dialog.dispose() }
    }
    selected
}
