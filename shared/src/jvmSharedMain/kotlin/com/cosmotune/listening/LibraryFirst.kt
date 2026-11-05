package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.TrackMatcher

/** Why a requested recording plays from where it does; shown to the listener, never guessed silently. */
enum class LibraryChoice {
    /** A readable local copy saved under the same source-qualified recording ID. */
    SAME_RECORDING,
    /** One local file is an unambiguous match for the recording (title, version, artist, length, edition). */
    MATCHED_LOCAL_FILE,
    /** Local copies exist but more than one edition or release fits; streamed rather than guessing. */
    AMBIGUOUS,
    /** The only local copy is missing or unreadable on this device. */
    LOCAL_UNAVAILABLE,
    /** Nothing local matches; streamed from its own source. */
    ONLINE,
}

data class LibraryResolution(val requested: Song, val play: Song, val choice: LibraryChoice)

/**
 * Library-first resolution. A same-ID local copy always wins. Otherwise a local file is used only
 * when the shared matcher accepts exactly one release for it: covers, remixes, live takes, other
 * explicit/clean editions and conflicting albums fall back to the requested source. The returned
 * song keeps the requested recording ID so likes, history and queue identity do not split.
 */
fun resolveLibraryFirst(requested: List<Song>, library: List<Song>, available: (Song) -> Boolean): List<LibraryResolution> {
    val local = library.filter { it.localPath != null || it.localUri != null }
    val byId = local.groupBy { it.videoId }
    return requested.map { song ->
        if (song.localPath != null || song.localUri != null) {
            return@map LibraryResolution(song, song, if (available(song)) LibraryChoice.SAME_RECORDING else LibraryChoice.LOCAL_UNAVAILABLE)
        }
        val same = byId[song.videoId].orEmpty()
        same.firstOrNull(available)?.let { return@map LibraryResolution(song, withLocation(song, it), LibraryChoice.SAME_RECORDING) }
        val target = TrackMatcher.targetOf(song)
        // Stricter than stream matching: a local file must also share a credited artist (no runtime-only override).
        val accepted = local.filter { it.videoId != song.videoId && TrackMatcher.score(it, target) != null &&
            (song.artist.isBlank() || TrackMatcher.sharesArtist(song.artist, it.artist)) }
        when {
            accepted.isEmpty() -> LibraryResolution(song, song, if (same.isNotEmpty()) LibraryChoice.LOCAL_UNAVAILABLE else LibraryChoice.ONLINE)
            accepted.mapNotNull { it.albumName?.trim()?.lowercase(java.util.Locale.ROOT)?.ifBlank { null } }.distinct().size > 1 ||
                TrackMatcher.hasConflictingAlbums(accepted, target) -> LibraryResolution(song, song, LibraryChoice.AMBIGUOUS)
            else -> {
                val best = TrackMatcher.ranked(accepted, target).firstOrNull { available(it) }
                if (best == null) LibraryResolution(song, song, LibraryChoice.LOCAL_UNAVAILABLE)
                else LibraryResolution(song, withLocation(song, best), LibraryChoice.MATCHED_LOCAL_FILE)
            }
        }
    }
}

private fun withLocation(requested: Song, file: Song) = requested.copy(
    localPath = file.localPath, localUri = file.localUri, downloadFormat = file.downloadFormat,
    sourceQuality = file.sourceQuality, localDateModifiedSeconds = file.localDateModifiedSeconds,
)

fun List<LibraryResolution>.summary(): String {
    val local = count { it.choice == LibraryChoice.SAME_RECORDING || it.choice == LibraryChoice.MATCHED_LOCAL_FILE }
    val ambiguous = count { it.choice == LibraryChoice.AMBIGUOUS }
    val missing = count { it.choice == LibraryChoice.LOCAL_UNAVAILABLE }
    return buildString {
        append("$local from your library, ${size - local} from their sources")
        if (ambiguous > 0) append(" ($ambiguous had more than one local edition)")
        if (missing > 0) append(" ($missing local files unavailable)")
    }
}
