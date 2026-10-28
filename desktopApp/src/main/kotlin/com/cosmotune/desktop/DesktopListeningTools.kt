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
    remote: ListeningRemoteHost,
) {
    ListeningTools(open, ListeningToolsHost(
        file = desktopListeningFile(), library = library, queue = queue,
        replay = { window ->
            val period = if (window == ReplayWindow.MONTH) DesktopReplayPeriod.THIS_MONTH else DesktopReplayPeriod.THIS_YEAR
            val summary = DesktopListeningStats.summary(period)
            ReplayTracks("Replay · ${summary.label}", summary.songs.map { it.song })
        }, createPlaylist = createPlaylist, restore = restore, play = play, openRelease = openRelease,
        findMusic = findMusic, importMusic = importMusic, applySetup = applySetup,
        exportFile = { name, text ->
            val target = chooseCosmotuneFile(name, saving = true)
            if (target == null) false else withContext(Dispatchers.IO) {
                writeTextExportFile(target, text, name.substringAfterLast('.').lowercase(java.util.Locale.ROOT)); true
            }
        },
        importFile = {
            chooseCosmotuneFile("", saving = false)?.let { file -> withContext(Dispatchers.IO) {
                require(file.length() <= 16 * 1024 * 1024) { "Choose a listening backup smaller than 16 MB." }
                file.readText(Charsets.UTF_8)
            } }
        },
        importPlaylistFile = {
            chooseCosmotuneFile("", saving = false, title = "Import music playlist")?.let { selected -> withContext(Dispatchers.IO) {
                val file = selected.canonicalFile
                require(file.length() <= MusicPlaylistFile.MAX_BYTES) { "Choose a music playlist smaller than 4 MB." }
                MusicPlaylistFile.decode(file.name, file.readBytes(), file.parent)
            } }
        },
        mediaCheck = { song -> ListeningNetwork.timed("Media stream") {
            withTimeout(30_000) {
                val stream = DesktopSourceRegistry.resolve(song, null).getOrThrow()
                ListeningNetwork.readMedia(stream.url, stream.headers)
            }
        } },
        sourceSummary = sourceSummary, existingUser = library.songs.isNotEmpty(), remote = remote,
        pcmDecoder = DesktopLibraryPcmDecoder,
        trashFiles = desktopTrash(),
        requestLosslessCopies = { songs -> songs.count { com.cosmotune.music.desktop.DesktopDownloadQueue.enqueue(it, quality = "LOSSLESS") } },
        languageChoices = DesktopStrings.available.map { it.tag to it.label },
    ), onDismiss)
}

/** The listening store; measured library loudness lives beside it. */
fun desktopListeningFile(): File {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: "${System.getProperty("user.home")}/.local/share"
    return File(base, "cosmotune/listening.json")
}

internal suspend fun chooseCosmotuneFile(name: String, saving: Boolean, title: String? = null): File? = withContext(Dispatchers.IO) {
    var selected: File? = null
    SwingUtilities.invokeAndWait {
        val dialog = FileDialog(null as Frame?, title ?: if (saving) "Save Cosmotune file" else "Import listening backup", if (saving) FileDialog.SAVE else FileDialog.LOAD)
        try {
            if (name.isNotBlank()) dialog.file = name
            dialog.isVisible = true
            dialog.file?.let { selected = File(dialog.directory, it) }
        } finally { dialog.dispose() }
    }
    selected
}

/** The operating system's own trash (recoverable), where the desktop supports it. */
internal fun desktopTrash(): (suspend (List<Song>) -> Int)? {
    val desktop = runCatching { java.awt.Desktop.getDesktop().takeIf { java.awt.Desktop.isDesktopSupported() && it.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) } }.getOrNull()
        ?: return null
    return { songs -> withContext(Dispatchers.IO) {
        songs.mapNotNull { it.localPath?.let(::File)?.takeIf { f -> f.isAbsolute && f.isFile } }.distinct().count { runCatching { desktop.moveToTrash(it) }.getOrDefault(false) }
    } }
}
