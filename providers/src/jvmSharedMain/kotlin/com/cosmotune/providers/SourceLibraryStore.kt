package com.cosmotune.providers

import com.cosmotune.listening.ListeningTrack
import com.cosmotune.music.data.model.*
import com.cosmotune.music.data.sources.SourceTrackKeys
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class SourceRating(val track: ListeningTrack, val status: String, val changedAtMs: Long = System.currentTimeMillis())
@Serializable data class SourceCollectionLink(val id: String, val title: String, val subtitle: String = "", val artwork: String? = null)
@Serializable data class SavedSourceCollection(val id: String, val title: String, val subtitle: String = "", val artwork: String? = null,
    val songs: List<ListeningTrack> = emptyList(), val related: List<SourceCollectionLink> = emptyList(), val savedAtMs: Long = System.currentTimeMillis()) {
    fun page(): SourceCataloguePage {
        val key = requireNotNull(ProviderBrowseKey.parse(id))
        val rows = songs.map { it.song() }
        val collections = related.map { link -> ProviderCollection(requireNotNull(ProviderBrowseKey.parse(link.id)), link.title, link.subtitle, link.artwork) }
        return SourceCataloguePage(ProviderCollection(key, title, subtitle, artwork, collections = collections), rows)
    }
    fun card(): ShelfItem = ShelfItem(title, subtitle, artwork, null, id)
}
@Serializable data class SourceLibraryState(val version: Int = 1, val ratings: Map<String, SourceRating> = emptyMap(),
    val collections: List<SavedSourceCollection> = emptyList(), val followed: List<SavedSourceCollection> = emptyList()) {
    val liked: List<Song> get() = ratings.values.filter { it.status == LikeStatus.LIKE.name }.sortedByDescending { it.changedAtMs }.map { it.track.song() }
    fun rating(id: String): LikeStatus = ratings[id]?.status?.let { runCatching { LikeStatus.valueOf(it) }.getOrNull() } ?: LikeStatus.INDIFFERENT
}

/** Local source ratings and saved pages never use a YouTube account or send source identifiers to it. */
class SourceLibraryStore(private val file: File, private val protection: ProviderFileProtection = providerFileProtection()) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = locks.computeIfAbsent(file.canonicalPath) { Mutex() }
    var recoveryMessage: String? = null
        private set
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    private fun read(): SourceLibraryState {
        if (!file.exists()) return SourceLibraryState()
        return try {
            require(file.length() <= MAX_BYTES + 64 * 1024)
            val bytes = protection.open(file.readBytes()); require(bytes.size <= MAX_BYTES)
            validate(json.decodeFromString<SourceLibraryState>(ProviderPackages.utf8(bytes)))
        } catch (_: Exception) {
            recoveryMessage = "The source library could not be opened. Its original file was left untouched."
            SourceLibraryState()
        }
    }
    private suspend fun update(change: (SourceLibraryState) -> SourceLibraryState) = mutex.withLock { withContext(Dispatchers.IO) {
        val current = read()
        check(recoveryMessage == null) { recoveryMessage.orEmpty() }
        val next = validate(change(current))
        if (next == current) { mutable.value = current; return@withContext }
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "The source library is full. Remove an older saved collection." }
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()); parent.ownerOnly()
        val temporary = File.createTempFile("source-library-", ".part", parent)
        try {
            temporary.ownerOnly()
            temporary.outputStream().use { it.write(protection.seal(bytes)); it.fd.sync() }
            ProviderStore.move(temporary, file)
            mutable.value = next
        } finally { temporary.delete() }
    } }

    suspend fun rate(song: Song, status: LikeStatus) {
        require(ownsTrack(song.videoId)) { "This recording belongs to another catalogue" }
        val record = ListeningTrack.of(song).copy(artwork = checkedArtwork(song.thumbnailUrl),
            entryId = null, sourceTitle = null, sourceType = null, sourceId = null, radio = null, setVideoId = null)
        update { state -> state.copy(ratings = if (status == LikeStatus.INDIFFERENT) state.ratings - song.videoId
            else state.ratings + (song.videoId to SourceRating(record, status.name))) }
    }
    suspend fun save(page: SourceCataloguePage, saved: Boolean) {
        require(page.collection.key.kind != "artist") { "Follow this artist instead" }
        val record = snapshot(page)
        update { old -> old.copy(collections = old.collections.filterNot { it.id == record.id } + if (saved) listOf(record) else emptyList()) }
    }
    suspend fun follow(page: SourceCataloguePage, followed: Boolean) {
        require(page.collection.key.kind == "artist")
        val record = snapshot(page)
        update { old -> old.copy(followed = old.followed.filterNot { it.id == record.id } + if (followed) listOf(record) else emptyList()) }
    }
    fun savedPage(id: String): SourceCataloguePage? = (state.value.collections + state.value.followed).find { it.id == id }?.page()?.copy(fromCache = true)

    private fun snapshot(page: SourceCataloguePage) = SavedSourceCollection(page.collection.key.encode(), page.collection.title,
        page.collection.subtitle, checkedArtwork(page.collection.artwork), page.songs.map { song ->
            require(ownsTrack(song.videoId)); ListeningTrack.of(song).copy(artwork = checkedArtwork(song.thumbnailUrl),
                entryId = null, sourceTitle = null, sourceType = null, sourceId = null, radio = null, setVideoId = null)
        }, page.collection.collections.map { SourceCollectionLink(it.key.encode(), it.title, it.subtitle, checkedArtwork(it.artwork)) })

    private fun validate(value: SourceLibraryState): SourceLibraryState = value.also {
        require(it.version == 1 && it.ratings.size <= 20_000 && it.collections.size <= 500 && it.followed.size <= 1_000)
        it.ratings.forEach { (id, rating) ->
            require(ownsTrack(id) && rating.track.id == id && rating.status in setOf("LIKE", "DISLIKE"))
            validateTrack(rating.track)
        }
        require(it.collections.map { item -> item.id }.distinct().size == it.collections.size)
        require(it.followed.map { item -> item.id }.distinct().size == it.followed.size)
        (it.collections + it.followed).forEach { page ->
            val key = requireNotNull(ProviderBrowseKey.parse(page.id))
            require(page.title.length <= 2_000 && page.subtitle.length <= 8_192)
            require(page.artwork == checkedArtwork(page.artwork) && page.songs.size <= 1_000 && page.related.size <= 100)
            page.songs.forEach { track ->
                validateTrack(track)
                require(SourceTrackKeys.parse(track.id)?.first == if (key.namespace == "provider") "sflx-${key.provider}" else key.provider)
            }
            page.related.forEach { link -> require(ProviderBrowseKey.parse(link.id) != null && link.title.length <= 2_000 && link.subtitle.length <= 8_192 && link.artwork == checkedArtwork(link.artwork)) }
        }
        require(it.collections.all { page -> ProviderBrowseKey.parse(page.id)?.kind != "artist" })
        require(it.followed.all { page -> ProviderBrowseKey.parse(page.id)?.kind == "artist" })
    }
    private fun validateTrack(track: ListeningTrack) {
        require(ownsTrack(track.id) && track.title.length <= 2_000 && track.artist.length <= 8_192 && track.artists.size <= 100)
        require(track.artwork == checkedArtwork(track.artwork))
    }

    companion object {
        const val LIKED_BROWSE_ID = "local:sources:liked"
        private const val MAX_BYTES = 16 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
        fun ownsTrack(id: String): Boolean = id.length <= 8_400 && SourceTrackKeys.parse(id)?.let { (source, recording) ->
            source.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) && recording.isNotBlank() && recording.length <= 8_192
        } == true
        private fun checkedArtwork(value: String?): String? = value?.takeIf(CatalogueArtwork::isReference)
    }
}
