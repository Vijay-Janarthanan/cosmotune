package com.cosmotune.lyrics

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Verify an upload's catalogue identity through an explicit link in the release's own track list. */
internal object YouTubeLyricsRecording {
    suspend fun counterpart(
        videoId: String,
        candidates: List<Song>,
        album: suspend (String) -> List<Song>,
    ): Song? {
        val releases = candidates.asSequence()
            .filter { !it.isVideo && it.videoId != videoId && it.albumId?.startsWith("MPREb_") == true }
            .take(8).groupBy { it.albumId!! }.entries.take(3)
        for ((release, songs) in releases) {
            val tracks = try { album(release) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { continue }
            // Title similarity, artist/composer overlap or a nearby duration alone cannot establish
            // that an upload is this recording. The album must explicitly link that exact video ID,
            // under the candidate's own title, artist and recording duration.
            songs.firstOrNull { candidate -> tracks.any { linked ->
                linked.videoId == videoId && RecordingMatch.accepts(
                    candidate.title, candidate.artist, candidate.durationMillis(),
                    linked.title, linked.artist, linked.durationMillis(),
                )
            } }?.let { return it }
        }
        return null
    }

    /** Ignore recommendations/carousels: only the album's primary vertical track shelf is evidence. */
    fun albumTracks(page: JsonElement): List<Song> {
        if (InnertubeParser.parseBrowseHeader(page) == null) return emptyList()
        val shelf = objects(page, "musicShelfRenderer").firstOrNull() ?: return emptyList()
        return InnertubeParser.collectSongsDeep(shelf)
    }

    private fun objects(node: JsonElement, key: String): Sequence<JsonObject> = sequence {
        when (node) {
            is JsonObject -> for ((name, value) in node) {
                if (name == key && value is JsonObject) yield(value)
                yieldAll(objects(value, key))
            }
            is JsonArray -> node.forEach { yieldAll(objects(it, key)) }
            else -> Unit
        }
    }
}
