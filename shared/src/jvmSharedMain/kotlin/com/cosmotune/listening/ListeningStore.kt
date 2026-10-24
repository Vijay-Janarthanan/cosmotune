package com.cosmotune.listening

import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.PlaybackSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Portable metadata; downloaded files stay on the device that owns them. */
@Serializable
data class ListeningArtist(val name: String, val id: String? = null)

@Serializable
data class ListeningTrack(
    val id: String, val title: String, val artist: String,
    val artwork: String? = null, val duration: String? = null,
    val artistId: String? = null, val albumId: String? = null, val album: String? = null,
    val uri: String? = null, val path: String? = null,
    val video: Boolean = false, val videoOrigin: Boolean = video,
    val tier: String = QueueTier.CONTEXT.name,
    val added: Long? = null, val quality: String? = null,
    val explicit: Boolean? = null, val format: String? = null, val modified: Long? = null,
    val artists: List<ListeningArtist> = emptyList(), val entryId: String? = null,
    val sourceTitle: String? = null, val sourceType: String? = null, val sourceId: String? = null,
    val radio: String? = null, val setVideoId: String? = null,
    val hiRes: Boolean = false,
) {
    fun song(): Song = Song(
        videoId = id, title = title, artist = artist, thumbnailUrl = artwork, durationText = duration,
        artistId = artistId, albumId = albumId, albumName = album, localUri = uri, localPath = path,
        isVideo = video, isVideoOrigin = videoOrigin,
        queueTier = QueueTier.entries.firstOrNull { it.name == tier } ?: QueueTier.CONTEXT,
        localDateAddedSeconds = added, sourceQuality = quality,
        isExplicit = explicit, downloadFormat = format, localDateModifiedSeconds = modified, sourceHiRes = hiRes,
        artists = artists.map { ArtistRef(it.name, it.id) }, queueEntryId = entryId,
        playbackSource = sourceTitle, playbackSourceType = PlaybackSourceType.entries.firstOrNull { it.name == sourceType },
        playbackSourceId = sourceId, radioName = radio, setVideoId = setVideoId,
    )

    companion object {
        fun of(song: Song) = ListeningTrack(
            song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText,
            song.artistId, song.albumId, song.albumName, song.localUri, song.localPath,
            song.isVideo, song.isVideoOrigin, song.queueTier.name, song.localDateAddedSeconds, song.sourceQuality,
            song.isExplicit, song.downloadFormat, song.localDateModifiedSeconds,
            song.artists.map { ListeningArtist(it.name, it.browseId) }, song.queueEntryId,
            song.playbackSource, song.playbackSourceType?.name, song.playbackSourceId, song.radioName, song.setVideoId, song.sourceHiRes,
        )
    }
}

@Serializable
data class ListeningSession(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val tracks: List<ListeningTrack>, val index: Int, val positionMs: Long,
    val repeat: Int = 0, val shuffle: Boolean = false, val savedAt: Long = System.currentTimeMillis(),
) {
    /** Keep duplicate queue entries and find the current entry after unavailable files drop out. */
    fun playable(available: (ListeningTrack) -> Boolean): ListeningSession? {
        val kept = tracks.withIndex().filter { available(it.value) }
        if (kept.isEmpty()) return null
        val exact = kept.indexOfFirst { it.index == index }
        val next = kept.indexOfFirst { it.index > index }.takeIf { it >= 0 } ?: kept.lastIndex
        return copy(tracks = kept.map { it.value }, index = if (exact >= 0) exact else next,
            positionMs = if (exact >= 0) positionMs else 0L)
    }
}

@Serializable
enum class SmartOrder { TITLE, RECENTLY_ADDED, ARTIST, SHUFFLE, LONGEST, SHORTEST }

@Serializable
enum class SmartDuration(val range: LongRange) { ANY(0L..Long.MAX_VALUE), SHORT(0L..179L), MEDIUM(180L..359L), LONG(360L..Long.MAX_VALUE) }

/**
 * Rules evaluated against the local library on every open. Every field defaults to "no filter",
 * so rules saved by older versions keep their meaning.
 */
@Serializable
data class SmartPlaylist(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val likedOnly: Boolean = true, val downloadedOnly: Boolean = false,
    val excludeRecent: Boolean = false, val order: SmartOrder = SmartOrder.TITLE,
    val pinned: Boolean = false,
    val recentOnly: Boolean = false,
    /** Case-insensitive text that the artist (or any credited artist) must contain. */
    val artistContains: String = "",
    val albumContains: String = "",
    val duration: SmartDuration = SmartDuration.ANY,
    /** Only tracks added to this device within this many days; null keeps every date. */
    val addedWithinDays: Int? = null,
    val excludeExplicit: Boolean = false,
    /** Downloads or files saved in a lossless format (by container, not proof of the original master). */
    val losslessOnly: Boolean = false,
    /** Keep at most this many tracks after ordering; null keeps all. */
    val limit: Int? = null,
) {
    fun songs(library: ListeningLibrary, now: Long = System.currentTimeMillis()): List<Song> {
        val artist = artistContains.trim().lowercase(java.util.Locale.ROOT)
        val album = albumContains.trim().lowercase(java.util.Locale.ROOT)
        val since = addedWithinDays?.let { now / 1000 - it.toLong() * 86_400 }
        val selected = library.songs.distinctBy { it.videoId }.filter {
            (!likedOnly || it.videoId in library.likedIds) &&
                (!downloadedOnly || it.videoId in library.downloadedIds) &&
                (!excludeRecent || it.videoId !in library.recentIds) &&
                (!recentOnly || it.videoId in library.recentIds) &&
                (artist.isEmpty() || (listOf(it.artist) + it.artists.map { a -> a.name }).any { name -> name.lowercase(java.util.Locale.ROOT).contains(artist) }) &&
                (album.isEmpty() || it.albumName.orEmpty().lowercase(java.util.Locale.ROOT).contains(album)) &&
                (duration == SmartDuration.ANY || durationSeconds(it.durationText)?.let { d -> d in duration.range } == true) &&
                (since == null || (it.localDateAddedSeconds ?: Long.MIN_VALUE) >= since) &&
                (!excludeExplicit || it.isExplicit != true) &&
                (!losslessOnly || it.sourceHiRes || it.downloadFormat?.lowercase(java.util.Locale.ROOT)?.let { f -> LOSSLESS.any(f::contains) } == true)
        }
        val ordered = when (order) {
            SmartOrder.TITLE -> selected.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
            SmartOrder.ARTIST -> selected.sortedWith(compareBy({ it.artist.lowercase(java.util.Locale.ROOT) }, { it.title.lowercase(java.util.Locale.ROOT) }))
            SmartOrder.RECENTLY_ADDED -> selected.sortedByDescending { it.localDateAddedSeconds ?: 0L }
            // Stable per rule: the same library gives the same order until the rule or library changes.
            SmartOrder.SHUFFLE -> selected.sortedBy { (id + it.videoId).hashCode() }
            SmartOrder.LONGEST -> selected.sortedByDescending { durationSeconds(it.durationText) ?: -1L }
            SmartOrder.SHORTEST -> selected.sortedBy { durationSeconds(it.durationText) ?: Long.MAX_VALUE }
        }
        return limit?.let { ordered.take(it) } ?: ordered
    }

    companion object {
        private val LOSSLESS = listOf("flac", "alac", "wav", "aiff", "dsd", "dsf", "ape", "wavpack")
        /** "3:45" or "1:02:03" to seconds; null when the text is not a duration. */
        fun durationSeconds(text: String?): Long? {
            val parts = text?.trim()?.split(':')?.takeIf { it.size in 2..3 } ?: return null
            val numbers = parts.map { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return numbers.fold(0L) { total, n -> total * 60 + n }
        }
    }
}

data class ListeningLibrary(
    val songs: List<Song> = emptyList(), val likedIds: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(), val recentIds: Set<String> = emptySet(),
)

@Serializable
data class WatchedArtist(val id: String, val name: String, val known: Set<String> = emptySet(), val checkedAt: Long? = null)

@Serializable
data class ReleaseNotice(
    val id: String, val artistId: String, val artist: String, val title: String,
    val artwork: String? = null, val discoveredAt: Long = System.currentTimeMillis(), val read: Boolean = false,
)

@Serializable
data class ListeningPreferences(
    val setupComplete: Boolean = false, val contentLanguage: String = "", val preferLocal: Boolean = false,
    /** Check followed artists about once a day while Cosmotune is open. */
    val autoCheckReleases: Boolean = false,
    /** Artist names containing separators that are one artist (applied to matching and statistics). */
    val artistExceptions: List<String> = emptyList(),
)

/** Followed artists whose last successful check is older than [intervalMs], oldest first. */
fun dueForReleaseCheck(artists: List<WatchedArtist>, now: Long, intervalMs: Long = 24L * 60 * 60 * 1000): List<WatchedArtist> =
    artists.filter { (it.checkedAt ?: Long.MIN_VALUE) <= now - intervalMs }.sortedBy { it.checkedAt ?: Long.MIN_VALUE }

/** Only catalogue artist pages can be checked for releases; local-file artist keys are skipped. */
private val CATALOGUE_ARTIST = Regex("(UC|MPLA)[A-Za-z0-9_-]{10,64}")

/** Artists in the local library by how many tracks they have, excluding those already followed. */
fun libraryArtists(songs: List<Song>, followed: Collection<WatchedArtist>, limit: Int = 20): List<Pair<WatchedArtist, Int>> {
    val known = followed.mapTo(HashSet()) { it.id }
    return songs.distinctBy { it.videoId }.flatMap { song ->
        (song.artists.mapNotNull { a -> a.browseId?.let { it to a.name } }.ifEmpty { listOfNotNull(song.artistId?.let { it to song.artist }) })
    }.filter { (id, name) -> CATALOGUE_ARTIST.matches(id) && name.isNotBlank() && id !in known }
        .groupBy({ it.first }, { it.second })
        .map { (id, names) -> WatchedArtist(id, names.groupingBy { it }.eachCount().maxBy { it.value }.key.take(80)) to names.size }
        .sortedWith(compareByDescending<Pair<WatchedArtist, Int>> { it.second }.thenBy { it.first.name.lowercase(java.util.Locale.ROOT) })
        .take(limit)
}

@Serializable
data class ListeningState(
    val version: Int = 1,
    val sessions: List<ListeningSession> = emptyList(),
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
    val artists: List<WatchedArtist> = emptyList(),
    val inbox: List<ReleaseNotice> = emptyList(),
    val preferences: ListeningPreferences = ListeningPreferences(),
    val pinnedPlaylistIds: Set<String> = emptySet(),
    val diagnostics: ListeningDiagnostics? = null,
    val linkedPlaylists: List<LinkedPlaylist> = emptyList(),
)

/** Identical file format on Android, Windows, Linux and macOS. Never stores account secrets. */
class ListeningStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fileLock = locks[(file.canonicalPath.let { if (File.separatorChar == '\\') it.lowercase(java.util.Locale.ROOT) else it }.hashCode() and Int.MAX_VALUE) % locks.size]
    private val mutable = MutableStateFlow(synchronized(fileLock) { read() })
    val state = mutable.asStateFlow()

    private fun read(): ListeningState {
        if (!file.exists()) return ListeningState()
        require(file.length() <= MAX_BYTES) { "Listening data is too large. Keep this file for recovery." }
        return validate(json.decodeFromString<ListeningState>(file.readText()))
    }

    @Synchronized
    fun update(change: (ListeningState) -> ListeningState) {
        synchronized(fileLock) {
        // Another window may have saved since this instance was opened.
        // Repairs must compare their preview against the actual durable state.
        val next = validate(change(read()))
        val raw = json.encodeToString(next)
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Listening data is full. Remove an older saved session." }
        file.parentFile?.mkdirs()
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".cosmotune-listening-", ".tmp")
        try {
            java.io.FileOutputStream(temp.toFile()).use { out -> out.write(raw.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        mutable.value = next
        }
    }

    fun saveSession(name: String, songs: List<Song>, index: Int, positionMs: Long, repeat: Int, shuffle: Boolean) {
        require(songs.isNotEmpty()) { "Add songs to your queue first." }
        require(songs.size <= MAX_TRACKS) { "Save a queue with at most $MAX_TRACKS tracks." }
        val session = ListeningSession(name = cleanName(name), tracks = songs.map(ListeningTrack::of),
            index = index.coerceIn(songs.indices), positionMs = positionMs.coerceAtLeast(0L), repeat = repeat.coerceIn(0, 2), shuffle = shuffle)
        update { it.copy(sessions = listOf(session) + it.sessions) }
    }

    fun renameSession(id: String, name: String) = update { s -> s.copy(sessions = s.sessions.map { if (it.id == id) it.copy(name = cleanName(name)) else it }) }
    fun deleteSession(id: String) = update { it.copy(sessions = it.sessions.filterNot { q -> q.id == id }) }
    fun saveSmart(rule: SmartPlaylist) = update { it.copy(smartPlaylists = it.smartPlaylists.filterNot { p -> p.id == rule.id } + rule.copy(name = cleanName(rule.name))) }
    fun deleteSmart(id: String) = update { it.copy(smartPlaylists = it.smartPlaylists.filterNot { p -> p.id == id }) }
    fun pinPlaylist(id: String) = update { it.copy(pinnedPlaylistIds = if (id in it.pinnedPlaylistIds) it.pinnedPlaylistIds - id else it.pinnedPlaylistIds + id) }
    fun recordDiagnostics(result: ListeningDiagnostics) = update { it.copy(diagnostics = result) }
    fun saveLinked(playlist: LinkedPlaylist) = update { s ->
        require(s.linkedPlaylists.size < 50 || s.linkedPlaylists.any { it.id == playlist.id }) { "You can link up to 50 playlists." }
        require(s.linkedPlaylists.none { it.id != playlist.id && it.source == playlist.source && it.remoteId == playlist.remoteId }) { "This playlist is already linked." }
        val named = playlist.copy(name = cleanName(playlist.name))
        s.copy(linkedPlaylists = if (s.linkedPlaylists.any { it.id == playlist.id }) s.linkedPlaylists.map { if (it.id == playlist.id) named else it } else s.linkedPlaylists + named)
    }
    fun unlink(id: String) = update { it.copy(linkedPlaylists = it.linkedPlaylists.filterNot { p -> p.id == id }) }

    fun watchArtist(id: String, name: String) = update {
        require(id.isNotBlank()) { "Choose an artist from search." }
        require(it.artists.size < 50 || it.artists.any { a -> a.id == id }) { "You can follow up to 50 artists here." }
        if (it.artists.any { a -> a.id == id }) it else it.copy(artists = it.artists + WatchedArtist(id, cleanName(name)))
    }
    fun unwatchArtist(id: String) = update { it.copy(artists = it.artists.filterNot { a -> a.id == id }, inbox = it.inbox.filterNot { r -> r.artistId == id }) }
    fun markRead(id: String) = update { it.copy(inbox = it.inbox.map { r -> if (r.id == id) r.copy(read = true) else r }) }
    fun acceptReleases(artistId: String, releases: List<ReleaseNotice>, now: Long = System.currentTimeMillis()) = update { s ->
        val artist = s.artists.firstOrNull { it.id == artistId } ?: return@update s
        val incoming = releases.distinctBy { it.id }.filter { it.id.isNotBlank() && it.artistId == artistId }
        // The first successful check establishes a baseline, so an old catalogue is never presented as new.
        val fresh = if (artist.checkedAt == null) emptyList() else incoming.filter { it.id !in artist.known }.map { it.copy(discoveredAt = now, read = false) }
        s.copy(artists = s.artists.map { if (it.id == artistId) it.copy(known = it.known + incoming.map { r -> r.id }, checkedAt = now) else it },
            inbox = (fresh + s.inbox).distinctBy { it.artistId to it.id }.take(500))
    }

    fun export(): String = synchronized(fileLock) {
        val latest = read()
        mutable.value = latest
        json.encodeToString(latest)
    }

    /** Additive, ID-based import: repeat imports are idempotent and existing local edits win. */
    fun import(raw: String) {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Choose a listening backup smaller than 16 MB." }
        val incoming = validate(json.decodeFromString<ListeningState>(raw))
        update { current -> current.copy(
            sessions = (current.sessions + incoming.sessions).distinctBy { it.id },
            smartPlaylists = (current.smartPlaylists + incoming.smartPlaylists).distinctBy { it.id },
            artists = (current.artists + incoming.artists).distinctBy { it.id },
            inbox = (current.inbox + incoming.inbox).distinctBy { it.artistId to it.id }.take(500),
            pinnedPlaylistIds = current.pinnedPlaylistIds + incoming.pinnedPlaylistIds,
            preferences = current.preferences.copy(artistExceptions = (current.preferences.artistExceptions + incoming.preferences.artistExceptions)
                .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(200)),
            linkedPlaylists = (current.linkedPlaylists + incoming.linkedPlaylists).distinctBy { it.id }
                .distinctBy { it.source to it.remoteId }.take(50),
        ) }
    }

    private fun validate(s: ListeningState): ListeningState {
        require(s.version == 1) { "This backup needs a newer version of Cosmotune." }
        require(s.sessions.size <= 100 && s.smartPlaylists.size <= 100 && s.artists.size <= 50) { "Listening backup has too many entries." }
        s.sessions.forEach { q ->
            require(q.id.isNotBlank() && q.name.isNotBlank() && q.name.length <= 80 && q.tracks.isNotEmpty() && q.tracks.size <= MAX_TRACKS && q.index in q.tracks.indices && q.positionMs >= 0 && q.repeat in 0..2) { "A saved session is invalid." }
            require(q.tracks.all { it.id.isNotBlank() }) { "A saved track has no identity." }
        }
        require(s.sessions.map { it.id }.distinct().size == s.sessions.size) { "Saved session identities must be unique." }
        require(s.smartPlaylists.all { it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 80 && it.artistContains.length <= 200 &&
            it.albumContains.length <= 200 && (it.addedWithinDays ?: 1) in 1..36_500 && (it.limit ?: 1) in 1..ListeningStore.MAX_TRACKS }) { "A smart playlist is invalid." }
        require(s.smartPlaylists.map { it.id }.distinct().size == s.smartPlaylists.size) { "Smart playlist identities must be unique." }
        require(s.artists.all { it.id.isNotBlank() && it.name.isNotBlank() && it.known.size <= 10000 }) { "An artist entry is invalid." }
        require(s.artists.map { it.id }.distinct().size == s.artists.size) { "Artist identities must be unique." }
        require(s.inbox.size <= 500 && s.inbox.all { it.id.isNotBlank() && it.artistId.isNotBlank() } &&
            s.inbox.map { it.artistId to it.id }.distinct().size == s.inbox.size) { "The release inbox is invalid." }
        require(s.linkedPlaylists.size <= 50 && s.linkedPlaylists.map { it.id }.distinct().size == s.linkedPlaylists.size) { "Linked playlists are invalid." }
        s.linkedPlaylists.forEach { p ->
            require(p.id.isNotBlank() && p.name.isNotBlank() && p.name.length <= 80 && p.remoteId.isNotBlank() && p.source.isNotBlank() &&
                p.entries.size <= MAX_TRACKS && p.base.size <= MAX_TRACKS && p.entries.all { it.track.id.isNotBlank() } && p.base.all { it.id.isNotBlank() }) { "A linked playlist is invalid." }
        }
        require(s.preferences.artistExceptions.size <= 200 && s.preferences.artistExceptions.all { it.length in 2..120 }) { "Artist exceptions are invalid." }
        s.diagnostics?.let { report ->
            require(report.checks.size <= 20 && report.checks.map { it.name }.distinct().size == report.checks.size) { "The diagnostic report is invalid." }
        }
        return s
    }

    companion object {
        private val locks = Array(64) { Any() }
        const val MAX_TRACKS = 5000
        private const val MAX_BYTES = 16 * 1024 * 1024
        fun cleanName(name: String): String = name.trim().also { require(it.isNotBlank() && it.length <= 80) { "Use a name between 1 and 80 characters." } }
    }
}
