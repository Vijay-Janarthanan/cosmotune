package com.cosmotune.ui.listening

import com.cosmotune.listening.ListeningLibrary
import com.cosmotune.music.data.model.Song
import com.cosmotune.remote.SyncPlaylist
import com.cosmotune.remote.SyncRecord
import com.cosmotune.remote.SyncTrack

/**
 * Likes and recent plays travel in Device sync as reserved shared playlists beside the listener's own, so the
 * phone and the computer can swap them over the home network without an account.
 */
object LibraryExchange {
    /** Each device's lists carry its own sync id, so two devices' likes never overwrite each other in the journal. */
    const val LIKED = "cosmotune-liked-"
    const val RECENT = "cosmotune-recent-"
    const val LIMIT = 2_000

    fun isReserved(id: String): Boolean = id.startsWith(LIKED) || id.startsWith(RECENT)

    /** This device's likes and recent plays as shareable lists; local files stay behind (their paths mean nothing elsewhere). */
    fun playlists(library: ListeningLibrary, device: String): List<SyncPlaylist> {
        val songs = library.songs.filter(::portable).distinctBy { it.videoId }
        val liked = songs.filter { it.videoId in library.likedIds }.take(LIMIT)
        val recent = songs.filter { it.videoId in library.recentIds }.take(LIMIT)
        return listOfNotNull(
            SyncPlaylist(LIKED + device, "Liked songs", liked.map(::track)).takeIf { liked.isNotEmpty() },
            SyncPlaylist(RECENT + device, "Recently played", recent.map(::track)).takeIf { recent.isNotEmpty() },
        )
    }

    /**
     * What the journal checks the published lists against: the native playlists, this device's fresh likes and plays,
     * and every like or play list already in the journal that is not being refreshed (another device's, or this
     * device's while its library is still loading), so none of them is recorded as removed.
     */
    fun published(native: List<SyncPlaylist>, library: ListeningLibrary, device: String, records: List<SyncRecord>): List<SyncPlaylist> {
        val fresh = playlists(library, device)
        val kept = records.filterNot { it.deleted }.mapNotNull { it.playlist }
            .filter { isReserved(it.id) && fresh.none { mine -> mine.id == it.id } }
        return native.filterNot { isReserved(it.id) } + fresh + kept
    }

    data class Split(val liked: List<Song>, val recent: List<Song>, val playlists: List<SyncRecord>)

    /** Received likes and plays apart from ordinary playlists. A removed list never unlikes anything here. */
    fun split(records: List<SyncRecord>): Split {
        fun songsOf(prefix: String) = records.filter { it.id.startsWith("playlist:$prefix") && !it.deleted }
            .flatMap { it.playlist?.tracks.orEmpty() }
            .filterNot { it.id.startsWith("file:") || it.id.startsWith("local:") }
            .map(::song).distinctBy { it.videoId }
        return Split(songsOf(LIKED), songsOf(RECENT), records.filterNot { isReserved(it.id.removePrefix("playlist:")) })
    }

    /** Recent plays from elsewhere join after this device's own, newest first, without repeats. */
    fun mergeHistory(own: List<Song>, received: List<Song>, max: Int = LIMIT): List<Song> =
        (own + received.filter { incoming -> own.none { it.videoId == incoming.videoId } }).distinctBy { it.videoId }.take(max)

    private fun portable(song: Song): Boolean = song.localPath == null && song.localUri == null &&
        song.videoId.isNotBlank() && !song.videoId.contains("://") && !song.videoId.startsWith("/") &&
        !song.videoId.startsWith("local:") && !song.videoId.startsWith("file:")

    private fun track(song: Song) = SyncTrack(song.videoId, song.title.take(500), song.artist.take(500), song.albumName?.take(500), song.durationText)

    private fun song(track: SyncTrack) = Song(
        videoId = track.id, title = track.title, artist = track.artist,
        thumbnailUrl = track.id.takeIf { it.matches(VIDEO_ID) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
        durationText = track.duration, albumName = track.album,
    )

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
}
