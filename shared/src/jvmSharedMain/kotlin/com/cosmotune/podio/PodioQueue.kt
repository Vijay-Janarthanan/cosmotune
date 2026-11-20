package com.cosmotune.podio

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One episode in Podio's queue, with the show it belongs to; [playNext] puts it in the "Play next" group ahead of the rest. */
@Serializable data class PodioQueueEntry(val episode: PodcastEpisode, val show: PodcastShow, val playNext: Boolean = false) {
    /** The id the player knows the episode by ("podio:episode:…"). */
    val id: String get() = episode.item().id
}

/**
 * Podio's queue as it is kept: what plays now ([current], with its show, so the player can show its chapters after a restart),
 * then [entries] in playing order, the "Play next" ones first. [keepGoing] goes on with the newest unheard episodes of the
 * followed shows when the queue runs out.
 */
@Serializable data class PodioQueueState(val current: PodioQueueEntry? = null, val entries: List<PodioQueueEntry> = emptyList(),
    val keepGoing: Boolean = false) {
    val playNext: List<PodioQueueEntry> get() = entries.filter { it.playNext }
    val upNext: List<PodioQueueEntry> get() = entries.filterNot { it.playNext }
    /** How long the queued episodes run together, in seconds. */
    val seconds: Long get() = entries.sumOf { it.episode.durationSeconds.coerceAtLeast(0) }
}

/**
 * The rules of the queue, apart from the file so they can be tested: every change keeps the "Play next" entries ahead of the
 * others, an episode is never queued twice, and the playing episode is never in [PodioQueueState.entries].
 */
object PodioQueueOrder {
    const val MAX_ENTRIES = 300

    private fun PodioQueueState.normal(): PodioQueueState {
        val unique = entries.filter { it.id != current?.id }.distinctBy { it.id }
        return copy(entries = (unique.filter { it.playNext } + unique.filterNot { it.playNext }).take(MAX_ENTRIES))
    }

    /** [entry] right after the other "Play next" episodes (moved there if it was queued already). */
    fun playNext(state: PodioQueueState, entry: PodioQueueEntry): PodioQueueState {
        if (entry.id == state.current?.id) return state
        val rest = state.entries.filterNot { it.id == entry.id }
        val at = rest.indexOfLast { it.playNext } + 1
        return state.copy(entries = rest.take(at) + entry.copy(playNext = true) + rest.drop(at)).normal()
    }

    /** [entry] at the end of "Up next"; one already queued keeps its place. */
    fun add(state: PodioQueueState, entry: PodioQueueEntry): PodioQueueState {
        if (entry.id == state.current?.id || state.entries.any { it.id == entry.id }) return state
        return state.copy(entries = state.entries + entry.copy(playNext = false)).normal()
    }

    fun remove(state: PodioQueueState, id: String): PodioQueueState = state.copy(entries = state.entries.filterNot { it.id == id })

    /** Puts back what [remove] took out, at [index] (the Undo of a removal). */
    fun restore(state: PodioQueueState, entry: PodioQueueEntry, index: Int): PodioQueueState {
        val rest = state.entries.filterNot { it.id == entry.id }
        val at = index.coerceIn(0, rest.size)
        return state.copy(entries = rest.take(at) + entry + rest.drop(at)).normal()
    }

    /**
     * Moves the entry at [from] to [to] (indexes of [PodioQueueState.entries]). A move stays inside its own group: an index past
     * the group's end stops at its edge, as the drag does.
     */
    fun move(state: PodioQueueState, from: Int, to: Int): PodioQueueState {
        val list = state.entries
        if (from !in list.indices) return state
        val moving = list[from]
        val group = list.indices.filter { list[it].playNext == moving.playNext }
        val target = to.coerceIn(group.first(), group.last())
        if (target == from) return state
        val next = list.toMutableList().apply { add(target, removeAt(from)) }
        return state.copy(entries = next)
    }

    fun clearPlayNext(state: PodioQueueState): PodioQueueState = state.copy(entries = state.entries.filterNot { it.playNext })

    fun clear(state: PodioQueueState): PodioQueueState = state.copy(entries = emptyList())

    /** "Up next" in a new order; "Play next" stays as it is. */
    fun shuffle(state: PodioQueueState, random: Random = Random.Default): PodioQueueState =
        state.copy(entries = state.playNext + state.upNext.shuffled(random))

    /** [entry] plays now: it leaves the queue (wherever it was) and becomes [PodioQueueState.current]. */
    fun play(state: PodioQueueState, entry: PodioQueueEntry): PodioQueueState =
        state.copy(current = entry.copy(playNext = false), entries = state.entries.filterNot { it.id == entry.id })

    /**
     * The player moved on to [id] by itself (the end of an episode, the next button, the notification): a queued episode becomes
     * the current one and leaves the queue. Anything else leaves the state alone (the page asked for it and set the current one,
     * or it is an episode from elsewhere, which readers match against what plays before they use [PodioQueueState.current]).
     */
    fun started(state: PodioQueueState, id: String): PodioQueueState {
        if (state.current?.id == id) return state
        val queued = state.entries.firstOrNull { it.id == id } ?: return state
        return play(state, queued)
    }
}

/**
 * Podio's queue on the disk (queue.json beside the library): one store per file for the process, so the playback service that
 * notices the player moving on and the page that shows the queue share it. Writes go to a temporary file first.
 */
class PodioQueue(private val file: File) {
    companion object {
        private class Store(file: File) {
            val lock = Any()
            val state = MutableStateFlow(runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<PodioQueueState>(file.readText()) }.getOrDefault(PodioQueueState()))
        }
        private val stores = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<Store>>()
        private fun sharedStore(file: File): Store = synchronized(stores) {
            val key = file.canonicalPath
            stores[key]?.get() ?: Store(file).also { store ->
                stores.entries.removeIf { it.value.get() == null }
                stores[key] = java.lang.ref.WeakReference(store)
            }
        }

        /** The queue kept beside a library file (podio/library.json → podio/queue.json). */
        fun besides(library: File): PodioQueue = PodioQueue(File(library.parentFile ?: File("."), "queue.json"))
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val store = sharedStore(file)
    val state: StateFlow<PodioQueueState> = store.state

    fun update(change: (PodioQueueState) -> PodioQueueState) = synchronized(store.lock) {
        val next = change(state.value)
        if (next == state.value) return@synchronized
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(PodioQueueState.serializer(), next))
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        store.state.value = next
    }

    /** The player is on [id] now (see [PodioQueueOrder.started]); only episodes count, a station leaves the queue as it is. */
    fun started(id: String?) {
        if (id == null || !id.startsWith("podio:episode:")) return
        update { PodioQueueOrder.started(it, id) }
    }
}
