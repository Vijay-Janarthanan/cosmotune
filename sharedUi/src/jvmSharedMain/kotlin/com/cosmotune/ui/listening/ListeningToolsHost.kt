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
    /** Platform decoder for loudness/quality analysis; WAV is always available. */
    val pcmDecoder: com.cosmotune.audio.LocalPcmDecoder = com.cosmotune.audio.WavPcmDecoder,
    val analysisFile: File = com.cosmotune.audio.LibraryAnalysisStore.beside(file),
    /** Moves local copies to the platform trash and returns how many moved; null where the host cannot. */
    val trashFiles: (suspend (List<Song>) -> Int)? = null,
    /** Queues LOSSLESS downloads of these recordings from the enabled sources and says how many were queued (LK-08). */
    val requestLosslessCopies: ((List<Song>) -> Int)? = null,
    /** Called after Cosmotune rewrote a local file's tags, so the platform library can rescan it. */
    val onLocalFileChanged: (String) -> Unit = {},
    /** Release lookup for followed artists; shared YouTube Music catalogue by default. */
    val releases: suspend (com.cosmotune.listening.WatchedArtist) -> List<com.cosmotune.listening.ReleaseNotice> = com.cosmotune.listening.ListeningNetwork::releases,
    /** Reads a linked playlist's current source rows; only `youtube` is linked from this workspace today. */
    val fetchPlaylist: suspend (String, String) -> com.cosmotune.listening.RemotePlaylist = { source, id ->
        require(source == "youtube") { "This playlist source is not available." }
        fetchYouTubePlaylist(id)
    },
    /** Separate document metadata preserves relative local paths without opening imported paths. */
    val importPlaylistFile: suspend () -> com.cosmotune.listening.MusicPlaylistFile? = {
        importFile()?.let { com.cosmotune.listening.MusicPlaylistFile("Imported playlist", it) }
    },
    /** Called only when the listener chooses catalogue matching in the import preview. */
    val matchPlaylistTrack: suspend (com.cosmotune.listening.MusicPlaylistImportRow) -> Song? = ::findPlaylistRecording,
)

private suspend fun findPlaylistRecording(row: com.cosmotune.listening.MusicPlaylistImportRow): Song? =
    kotlinx.coroutines.withTimeoutOrNull(15_000) {
        val target = com.cosmotune.music.data.sources.TrackMatcher.Target(row.title, row.artist, album = row.album)
        for (query in com.cosmotune.music.data.sources.TrackMatcher.queries(target).take(2)) {
            val candidates = com.cosmotune.music.data.YtMusicRepository.search(query, com.cosmotune.music.data.model.SearchFilter.SONGS)
                .getOrDefault(emptyList()).mapNotNull { (it as? com.cosmotune.music.data.model.SearchResult.Track)?.song }
                .filter { com.cosmotune.music.data.sources.TrackMatcher.sharesArtist(row.artist, it.artist) }
            com.cosmotune.music.data.sources.TrackMatcher.best(candidates, target)?.let { return@withTimeoutOrNull it }
        }
        null
    }

/** Android provider grants cannot be used by a desktop receiving its backup. */
internal fun desktopTrackAvailable(track: com.cosmotune.listening.ListeningTrack): Boolean =
    if (track.path?.let { File(it).let { file -> file.isFile && file.canRead() } } == true) true
    else if (track.uri != null) runCatching { File(java.net.URI(track.uri)).let { it.isFile && it.canRead() } }.getOrDefault(false)
    else track.path == null
