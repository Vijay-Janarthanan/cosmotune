package com.cosmotune.podio

import com.cosmotune.music.data.model.Song
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PodioChapterImage(val episodeId: String, val chapter: PodcastChapter, val artworkUri: String, val bytes: ByteArray)

/** Application/service-owned chapter metadata. Existing position events drive transitions; there is no polling loop. */
class PodioChapterMedia(private val directory: File, catalogue: PodioCatalogue = PodioCatalogue(),
    private val fetchChapters: suspend (String) -> List<PodcastChapter> = { url ->
        catalogue.chapters(PodcastEpisode("metadata", "", "", "", chaptersUrl = url))
    }, private val fetchImage: suspend (String) -> ByteArray? = catalogue::picture) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var generation = 0L
    private var episode: Pair<String, String>? = null
    private var position = 0L
    private var chapters: List<PodcastChapter>? = null
    private var requested: PodcastChapter? = null
    private var metadata: Job? = null
    private var image: Job? = null
    private val lists = LinkedHashMap<String, List<PodcastChapter>>()
    private val images = LinkedHashMap<String, Pair<String, ByteArray>?>()
    private val mutable = MutableStateFlow<PodioChapterImage?>(null)
    val state = mutable.asStateFlow()
    private val loadedFlow = MutableStateFlow<String?>(null)
    /** The episode whose chapters are known now (it has some), or null: the notification's Next chapter follows it. */
    val loaded = loadedFlow.asStateFlow()

    /** Whether the episode playing has chapters. */
    val hasChapters: Boolean get() = synchronized(lock) { !chapters.isNullOrEmpty() }

    /** Where the chapter after the playing one starts, or null in the last one. */
    fun nextChapterStart(): Long? = synchronized(lock) { chapters?.firstOrNull { it.startMs > position + 1_000 }?.startMs }

    fun observe(song: Song?, positionMs: Long) = synchronized(lock) {
        val next = song?.takeIf { it.videoId.startsWith("podio:episode:") }?.let { value ->
            value.podcastChaptersUrl?.let(PodioCatalogue::httpUrl)?.let { value.videoId to it }
        }
        position = positionMs.coerceAtLeast(0)
        if (next != episode) {
            generation++; metadata?.cancel(); image?.cancel(); episode = next; chapters = null; requested = null; mutable.value = null; loadedFlow.value = null
            if (next != null) {
                val epoch = generation
                metadata = scope.launch {
                    val loaded = synchronized(lock) { lists[next.second] } ?: try { fetchChapters(next.second).take(5_000) }
                        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
                    synchronized(lock) {
                        lists[next.second] = loaded; while (lists.size > 32) lists.remove(lists.keys.first())
                        if (epoch == generation && episode == next) { chapters = loaded; loadedFlow.value = next.first.takeIf { loaded.isNotEmpty() }; select(epoch, next.first) }
                    }
                }
            }
        } else if (next != null) select(generation, next.first)
    }

    private fun select(epoch: Long, id: String) {
        val chapter = chapters?.lastOrNull { it.startMs <= position }
        if (chapter == requested) return
        requested = chapter; image?.cancel(); mutable.value = null
        val selected = chapter ?: return
        val url = selected.image?.let(PodioCatalogue::httpUrl) ?: return
        image = scope.launch {
            val cached = synchronized(lock) { if (images.containsKey(url)) images[url] else null }
            val known = synchronized(lock) { images.containsKey(url) }
            val found = if (known) cached else try {
                val bytes = fetchImage(url)?.takeIf { it.size in 8..2_000_000 && imageBytes(it) }
                bytes?.let {
                    check(directory.isDirectory || directory.mkdirs())
                    val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
                    val file = File(directory, "$name.img")
                    file.writeBytes(it)
                    directory.listFiles().orEmpty().filter { candidate -> candidate.name.matches(Regex("[a-f0-9]{64}\\.img")) }
                        .sortedByDescending { candidate -> candidate.lastModified() }.drop(64).forEach { candidate -> candidate.delete() }
                    file.toURI().toString() to it
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            synchronized(lock) {
                images[url] = found; while (images.size > 24) images.remove(images.keys.first())
                if (epoch == generation && episode?.first == id && requested == selected && found != null)
                    mutable.value = PodioChapterImage(id, selected, found.first, found.second)
            }
        }
    }

    override fun close() { synchronized(lock) { generation++; episode = null; mutable.value = null }; scope.cancel() }

    companion object {
        private fun imageBytes(bytes: ByteArray) = bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) ||
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() ||
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    }
}
