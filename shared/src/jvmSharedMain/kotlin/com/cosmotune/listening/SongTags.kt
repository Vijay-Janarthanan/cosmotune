package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A song the listener rated or tagged (the idea from Namida's moods, tags and ratings pages, see NOTICE.md): enough of the song
 * to list and play it again, its stars (0 for none, else 1 to 5) and its moods and tags, in the order they were added.
 */
@Serializable
data class TaggedSong(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val rating: Int = 0,
    val tags: List<String> = emptyList(),
    val at: Long = 0L,
    val localUri: String? = null,
    val localPath: String? = null,
    val isVideo: Boolean = false,
) {
    fun toSong(): Song = Song(videoId = id, title = title, artist = artist, thumbnailUrl = thumbnailUrl, durationText = durationText, albumName = albumName,
        isVideo = isVideo, localUri = localUri, localPath = localPath)
}

/** The rules for the rated and tagged songs, kept as JSON in the settings store. */
object SongTags {
    const val MAX_SONGS = 3_000
    const val MAX_TAGS = 12
    const val MAX_TAG = 30

    /** Moods offered before the listener has made any of their own. */
    val SUGGESTED = listOf("Happy", "Calm", "Sad", "Energetic", "Focus", "Workout", "Party", "Romantic", "Late night", "Road trip")

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(songs: List<TaggedSong>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TaggedSong.serializer()), songs)

    fun decode(text: String): List<TaggedSong> = if (text.isBlank()) emptyList() else runCatching {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TaggedSong.serializer()), text)
    }.getOrDefault(emptyList()).filter { it.id.isNotBlank() }.map { it.copy(rating = it.rating.coerceIn(0, 5), tags = tidyTags(it.tags)) }
        .filter { it.rating > 0 || it.tags.isNotEmpty() }.distinctBy { it.id }.take(MAX_SONGS)

    fun tidyTag(tag: String): String = tag.replace(Regex("\\s+"), " ").trim().take(MAX_TAG)

    private fun tidyTags(tags: List<String>): List<String> = tags.map(::tidyTag).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_TAGS)

    /**
     * [songs] with [song]'s stars and tags set to [rating] and [tags]: a song with neither is dropped, a new one goes first, and the
     * oldest leave past [MAX_SONGS]. A tag written in another case than one already used takes that one's spelling.
     */
    fun set(songs: List<TaggedSong>, song: Song, rating: Int, tags: List<String>, now: Long): List<TaggedSong> {
        val known = allTags(songs).associateBy { it.lowercase() }
        val tidy = tidyTags(tags).map { known[it.lowercase()] ?: it }
        val rest = songs.filterNot { it.id == song.videoId }
        if (song.videoId.isBlank()) return songs
        if (rating !in 1..5 && tidy.isEmpty()) return rest
        val old = songs.firstOrNull { it.id == song.videoId }
        val entry = TaggedSong(song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName, rating.coerceIn(0, 5), tidy, old?.at ?: now,
            song.localUri, song.localPath, song.isVideo)
        return (listOf(entry) + rest).take(MAX_SONGS)
    }

    fun of(songs: List<TaggedSong>, id: String): TaggedSong? = songs.firstOrNull { it.id == id }

    /** Every tag in use, the most used first, then by name. */
    fun allTags(songs: List<TaggedSong>): List<String> = songs.flatMap { it.tags }.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }

    /** What a page lists: [Page.TopRated] four or five stars, best first; [Page.Rated] any stars; [Page.Tag] that tag, newest first. */
    sealed interface Page {
        data object TopRated : Page
        data object Rated : Page
        data class Tag(val name: String) : Page
    }

    fun page(songs: List<TaggedSong>, page: Page): List<TaggedSong> = when (page) {
        Page.TopRated -> songs.filter { it.rating >= 4 }.sortedByDescending { it.rating }
        Page.Rated -> songs.filter { it.rating > 0 }.sortedByDescending { it.rating }
        is Page.Tag -> songs.filter { song -> song.tags.any { it.equals(page.name, ignoreCase = true) } }
    }
}
