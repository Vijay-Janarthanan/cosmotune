package com.cosmotune.ui.listening

import com.cosmotune.listening.DiagnosticCheck
import com.cosmotune.listening.ListeningLibrary
import com.cosmotune.listening.ListeningNetwork
import com.cosmotune.listening.ListeningPreferences
import com.cosmotune.listening.ListeningSession
import com.cosmotune.music.data.model.Song
import java.io.File
import androidx.compose.runtime.Composable
import com.cosmotune.remote.LanRemoteController

enum class ReplayWindow { MONTH, YEAR }
data class ReplayTracks(val title: String, val songs: List<Song>)
data class ListeningQueue(val songs: List<Song>, val index: Int, val positionMs: Long, val repeat: Int, val shuffle: Boolean)

data class ListeningRemoteHost(val controller: LanRemoteController, val qrCode: @Composable (String) -> Unit)

/** Platform operations; UI and persistence rules are shared across every supported target. */
data class ListeningToolsHost(
    val file: File,
    val library: ListeningLibrary,
    val queue: () -> ListeningQueue,
    val replay: suspend (ReplayWindow) -> ReplayTracks,
    val createPlaylist: suspend (String, List<Song>) -> Unit,
    val restore: suspend (ListeningSession) -> Unit,
    val play: suspend (List<Song>) -> Unit,
    val openRelease: (String, String) -> Unit,
    val findMusic: () -> Unit,
    val importMusic: () -> Unit,
    val applySetup: (ListeningPreferences, Boolean) -> Unit,
    val exportFile: suspend (String, String) -> Boolean,
    val importFile: suspend () -> String?,
    val mediaCheck: suspend (Song) -> DiagnosticCheck = ListeningNetwork::youtubeMedia,
    val connectionCheck: suspend (Song?) -> com.cosmotune.listening.ListeningDiagnostics = { ListeningNetwork.diagnose(it, mediaCheck) },
    val available: (com.cosmotune.listening.ListeningTrack) -> Boolean = ::desktopTrackAvailable,
    val languageChoices: List<Pair<String, String>> = listOf("" to "System", "en" to "English", "hi" to "हिन्दी"),
    val sourceSummary: String = "",
    val existingUser: Boolean = false,
    val libraryAccess: com.cosmotune.listening.LocalLibraryAccess = com.cosmotune.listening.FileLibraryAccess,
    val remote: ListeningRemoteHost? = null,
)

/** Android provider grants cannot be used by a desktop receiving its backup. */
internal fun desktopTrackAvailable(track: com.cosmotune.listening.ListeningTrack): Boolean =
    if (track.path?.let { File(it).let { file -> file.isFile && file.canRead() } } == true) true
    else if (track.uri != null) runCatching { File(java.net.URI(track.uri)).let { it.isFile && it.canRead() } }.getOrDefault(false)
    else track.path == null
