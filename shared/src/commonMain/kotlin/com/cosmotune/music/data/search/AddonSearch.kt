package com.cosmotune.music.data.search

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One catalogue the Add-ons search asks: an HTTP add-on of the source list, or an installed `.sflx` provider package with the
 * metadata role, run through its own scoped runtime. [search] is that source's own search call, so the source's settings,
 * credentials and network scope apply exactly as they do for its other requests; nothing here adds a header, a cookie or an
 * account to it.
 */
class AddonSearchSource(val name: String, val search: suspend (query: String, limit: Int) -> List<Song>)

/**
 * The Add-ons source of the Search tab (upstream `2160a822`), shared by the phone and the desktop.
 *
 * The rules it keeps, which the tests pin:
 * - A query leaves for the add-ons only when it is submitted ([shouldSearch]): no typeahead, no completion, no per-keystroke
 *   request goes to a third-party catalogue. YouTube Music is not asked at all, so nothing is recorded in its history and the
 *   account's cookies are never involved (the anonymous-search rule of `36d55dd6`).
 * - Every source is asked at once; a slow source is left out after [TIMEOUT_MS], a failing one costs only its own rows, and
 *   cancelling the search (a new query, the field cleared) cancels every request.
 * - The answer is round-robin across the sources, so no single catalogue buries the others, and each source gives at most
 *   [LIMIT] rows.
 */
object AddonSearch {
    /** Per-source budget: a slow add-on is left out, not waited on. */
    const val TIMEOUT_MS = 12_000L

    /** Rows asked of, and kept from, each source. */
    const val LIMIT = 20

    /** Whether [query] may be sent to the add-ons: only an explicit submission of text that is not blank. */
    fun shouldSearch(query: String, submitted: Boolean): Boolean = submitted && query.isNotBlank()

    /** Asks every one of [sources] for [query] in parallel and interleaves the answers; see the class rules. */
    suspend fun search(
        query: String,
        sources: List<AddonSearchSource>,
        timeoutMs: Long = TIMEOUT_MS,
        limit: Int = LIMIT,
    ): List<Song> {
        val text = query.trim()
        if (text.isEmpty() || sources.isEmpty()) return emptyList()
        val perSource = coroutineScope {
            sources.map { source ->
                async {
                    withTimeoutOrNull(timeoutMs) {
                        try {
                            source.search(text, limit).take(limit)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } ?: emptyList()
                }
            }.awaitAll()
        }
        return interleave(perSource)
    }

    /**
     * The first row of every source, then the second of every source, and so on. A row whose id an earlier row already has is
     * left out (two packages wrapping one catalogue); an add-on's stereo and Atmos rows have different ids and both stay.
     */
    fun interleave(perSource: List<List<Song>>): List<Song> {
        val seen = HashSet<String>()
        val longest = perSource.maxOfOrNull { it.size } ?: 0
        return buildList {
            for (i in 0 until longest) for (rows in perSource) {
                val song = rows.getOrNull(i) ?: continue
                if (seen.add(song.videoId)) add(song)
            }
        }
    }
}

/**
 * The words typed into the Add-ons search before, newest first, for a host that keeps them itself (the desktop; the phone's
 * `SearchHistory.addonRecent` applies the same rules). Text only: an add-on search has no entity worth remembering, and running
 * the words again is what a tap on one does. [read] and [write] are the host's storage, so a test never touches the real one.
 */
class AddonQueryHistory(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val max: Int = MAX_ENTRIES,
) {
    private val _recent = MutableStateFlow(decode(read()))
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    /** Puts [query] at the top (trimmed), dropping an earlier entry that differs only in case. */
    fun record(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        save((listOf(text) + _recent.value.filterNot { it.equals(text, ignoreCase = true) }).take(max))
    }

    fun remove(query: String) = save(_recent.value.filterNot { it == query })

    fun clear() = save(emptyList())

    private fun save(value: List<String>) {
        _recent.value = value
        write(json.encodeToString(serializer, value))
    }

    private fun decode(text: String?): List<String> =
        runCatching { json.decodeFromString(serializer, text ?: "[]") }.getOrDefault(emptyList())
            .map(String::trim).filter(String::isNotEmpty).take(max)

    companion object {
        const val MAX_ENTRIES = 20
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(String.serializer())
    }
}
