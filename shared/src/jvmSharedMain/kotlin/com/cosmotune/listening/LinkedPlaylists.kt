package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One playlist row, identified by its source-qualified recording ID. Titles are display only.
 * A ghost is a placeholder for a track the source removed or can no longer play: it keeps its
 * place, identity, likes and listening history, and is skipped by playback until it returns.
 */
@Serializable
data class LinkedEntry(
    val track: ListeningTrack,
    val ghost: Boolean = false,
    val ghostReason: GhostReason? = null,
    /** Added on this device rather than by the source; never removed by a sync. */
    val local: Boolean = false,
)

@Serializable
enum class GhostReason { REMOVED_FROM_SOURCE, UNAVAILABLE }

@Serializable
data class LinkedPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Which catalogue the playlist belongs to, such as `youtube`. */
    val source: String,
    /** The source's own playlist ID; never a URL with credentials. */
    val remoteId: String,
    /** The source as of the last applied sync, for three-way merges. */
    val base: List<ListeningTrack> = emptyList(),
    val entries: List<LinkedEntry> = emptyList(),
    val syncedAt: Long? = null,
) {
    fun playable(): List<Song> = entries.filterNot { it.ghost }.map { it.track.song() }
}

/** What the source currently states. [available] is false for rows it shows but cannot play. */
data class RemoteTrack(val song: Song, val available: Boolean = true)
data class RemotePlaylist(val title: String, val tracks: List<RemoteTrack>)

data class LinkedSyncPreview(
    val playlistId: String,
    val before: LinkedPlaylist,
    val after: LinkedPlaylist,
    val added: List<ListeningTrack>,
    val ghosted: List<ListeningTrack>,
    val restored: List<ListeningTrack>,
    /** Tracks removed on this device that the source still lists; they stay removed. */
    val keptLocalRemovals: List<ListeningTrack>,
    /** The source returned far fewer rows than last time, which can mean a partial response. */
    val suspiciousShrink: Boolean,
) {
    val changed: Boolean get() = after.entries != before.entries || after.base != before.base
}

/**
 * Three-way merge of the last synced source ([LinkedPlaylist.base]), this device's list and the
 * current source. Local edits win: removals stay removed, local additions and order are kept.
 * Source additions are inserted after the nearest preceding source row. Nothing is deleted by a
 * sync; a vanished or unplayable source row becomes a ghost and is restored if it returns.
 * Repeated recordings are matched by occurrence, so duplicates survive.
 */
fun previewLinkedSync(playlist: LinkedPlaylist, remote: RemotePlaylist): LinkedSyncPreview {
    require(remote.tracks.size <= ListeningStore.MAX_TRACKS) { "This playlist has more than ${ListeningStore.MAX_TRACKS} tracks." }
    require(remote.tracks.all { it.song.videoId.isNotBlank() }) { "The source returned a track without an identity." }
    fun keyed(ids: List<String>): List<Pair<String, Int>> {
        val seen = HashMap<String, Int>()
        return ids.map { id -> id to seen.merge(id, 1, Int::plus)!! }
    }
    val baseKeys = keyed(playlist.base.map { it.id }).toSet()
    val remoteTracks = remote.tracks
    val remoteKeys = keyed(remoteTracks.map { it.song.videoId })
    val remoteByKey = remoteKeys.zip(remoteTracks).toMap()
    val localKeys = keyed(playlist.entries.map { it.track.id })
    val localByKey = localKeys.zip(playlist.entries).toMap()

    val added = mutableListOf<ListeningTrack>()
    val ghosted = mutableListOf<ListeningTrack>()
    val restored = mutableListOf<ListeningTrack>()
    // Existing rows: update ghost state and refresh display metadata, keeping local fields.
    val merged = localKeys.map { key ->
        val entry = localByKey.getValue(key)
        val now = remoteByKey[key]
        when {
            now != null -> {
                val fresh = ListeningTrack.of(now.song).let { incoming ->
                    // Display metadata follows the source; local file details and queue identity stay.
                    entry.track.copy(title = incoming.title, artist = incoming.artist, album = incoming.album ?: entry.track.album,
                        artwork = incoming.artwork ?: entry.track.artwork, duration = incoming.duration ?: entry.track.duration,
                        artists = incoming.artists.ifEmpty { entry.track.artists })
                }
                if (!now.available) {
                    if (!entry.ghost || entry.ghostReason != GhostReason.UNAVAILABLE) ghosted += fresh
                    key to entry.copy(track = fresh, ghost = true, ghostReason = GhostReason.UNAVAILABLE, local = false)
                } else {
                    if (entry.ghost) restored += fresh
                    key to entry.copy(track = fresh, ghost = false, ghostReason = null, local = false)
                }
            }
            key in baseKeys && !entry.local -> {
                if (!entry.ghost || entry.ghostReason != GhostReason.REMOVED_FROM_SOURCE) ghosted += entry.track
                key to entry.copy(ghost = true, ghostReason = GhostReason.REMOVED_FROM_SOURCE)
            }
            else -> key to entry
        }
    }.toMutableList()

    val localKeySet = localKeys.toSet()
    val keptLocalRemovals = remoteKeys.filter { it in baseKeys && it !in localKeySet }.map { ListeningTrack.of(remoteByKey.getValue(it).song) }
    // Source rows new since the last sync: insert after the closest earlier source row present here.
    remoteKeys.forEachIndexed { index, key ->
        if (key in baseKeys || key in localKeySet) return@forEachIndexed
        val row = remoteByKey.getValue(key)
        val entry = LinkedEntry(ListeningTrack.of(row.song), ghost = !row.available,
            ghostReason = if (row.available) null else GhostReason.UNAVAILABLE)
        val anchor = (index - 1 downTo 0).map { remoteKeys[it] }.firstNotNullOfOrNull { previous ->
            merged.indexOfFirst { it.first == previous }.takeIf { it >= 0 }
        }
        merged.add(if (anchor == null) (if (index == 0) 0 else merged.size) else anchor + 1, key to entry)
        added += entry.track
    }
    require(merged.size <= ListeningStore.MAX_TRACKS) { "The merged playlist would exceed ${ListeningStore.MAX_TRACKS} tracks." }
    val after = playlist.copy(base = remoteTracks.map { ListeningTrack.of(it.song) }, entries = merged.map { it.second })
    val shrink = playlist.base.size >= 20 && remote.tracks.size * 2 < playlist.base.size
    return LinkedSyncPreview(playlist.id, playlist, after, added, ghosted, restored, keptLocalRemovals, shrink)
}

/** Applies a preview only if nobody edited the playlist since it was made. */
fun applyLinkedSync(state: ListeningState, preview: LinkedSyncPreview, now: Long = System.currentTimeMillis()): ListeningState {
    val current = state.linkedPlaylists.firstOrNull { it.id == preview.playlistId }
        ?: throw IllegalArgumentException("This linked playlist was removed.")
    require(current == preview.before) { "The playlist changed. Check for changes again." }
    return state.copy(linkedPlaylists = state.linkedPlaylists.map { if (it.id == current.id) preview.after.copy(syncedAt = now) else it })
}

/** Starts a link from the source's current rows; nothing is a ghost yet unless the source says so. */
fun newLinkedPlaylist(source: String, remoteId: String, remote: RemotePlaylist, now: Long = System.currentTimeMillis()): LinkedPlaylist {
    require(source.matches(Regex("[a-z0-9_-]{1,32}")) && remoteId.matches(Regex("[A-Za-z0-9_-]{2,128}"))) { "Enter a playlist ID or link." }
    val tracks = remote.tracks.take(ListeningStore.MAX_TRACKS)
    return LinkedPlaylist(name = ListeningStore.cleanName(remote.title.ifBlank { "Linked playlist" }.take(80)), source = source, remoteId = remoteId,
        base = tracks.map { ListeningTrack.of(it.song) },
        entries = tracks.map { LinkedEntry(ListeningTrack.of(it.song), ghost = !it.available, ghostReason = if (it.available) null else GhostReason.UNAVAILABLE) },
        syncedAt = now)
}

/** Accepts a bare playlist ID or a music.youtube.com / youtube.com link and returns only the ID. */
fun youtubePlaylistId(input: String): String? {
    val raw = input.trim()
    val id = Regex("[?&]list=([A-Za-z0-9_-]+)").find(raw)?.groupValues?.get(1) ?: raw.removePrefix("VL")
    return id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,128}")) }
}

/** Local edits on a linked playlist; a sync never undoes them. */
fun LinkedPlaylist.removeEntry(index: Int): LinkedPlaylist = copy(entries = entries.filterIndexed { i, _ -> i != index })
fun LinkedPlaylist.addLocal(song: Song): LinkedPlaylist {
    require(entries.size < ListeningStore.MAX_TRACKS) { "This playlist is full." }
    return copy(entries = entries + LinkedEntry(ListeningTrack.of(song), local = true))
}
fun LinkedPlaylist.move(from: Int, to: Int): LinkedPlaylist {
    require(from in entries.indices && to in entries.indices)
    val list = entries.toMutableList(); list.add(to, list.removeAt(from))
    return copy(entries = list)
}
fun LinkedPlaylist.dropGhosts(): LinkedPlaylist = copy(entries = entries.filterNot { it.ghost })
