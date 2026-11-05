package com.cosmotune.music.data.search

import com.cosmotune.music.data.model.Song
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One catalogue the Add-ons search asks: an HTTP add-on of the source list, a module index, JioSaavn, a Subsonic server, an
 * installed `.sflx` provider package run through its own scoped runtime, or (for "All") YouTube Music's anonymous song search.
 * [search] is that source's own search call, so the source's settings, credentials and network scope apply exactly as they do
 * for its other requests; nothing here adds a header, a cookie or an account to it.
 *
 * [id] is stable for the source (its configured id, `sflx-<name>` for a provider package, [AddonSearch.YOUTUBE] for YouTube Music)
 * and is what the "Search in" picker remembers. [queueTimeoutMs] is for a source that first waits for a shared, bounded worker
 * (provider packages share two script runtimes): its own [AddonSearch.TIMEOUT_MS] budget starts when [addonSearchStarted]'s
 * signal fires, not while it is still queued behind the others, so the third package of five is not timed out for waiting.
 */
class AddonSearchSource(
    val name: String,
    val id: String = name,
    val queueTimeoutMs: Long = 0,
    val search: suspend (query: String, limit: Int) -> List<Song>,
)

/** Thrown by a source's search when it needs a sign-in or a human verification before it can answer. */
class AddonSearchAccessRequired(message: String = "Verify to search") : IllegalStateException(message)

/** Thrown by a source's search when that source has no search at all (a lyrics-only or download-only package). */
class AddonSearchUnsupported(message: String = "This source does not offer search") : IllegalStateException(message)

/** Where a source stands in the current search. */
enum class AddonSourceStatus { SEARCHING, DONE, FAILED, TIMED_OUT, NEEDS_VERIFICATION, UNSUPPORTED }

/** One source's part of a search: its rows once it has answered, or why it has none. */
data class AddonSourceResult(
    val id: String,
    val name: String,
    val status: AddonSourceStatus,
    val songs: List<Song> = emptyList(),
    /** A short, safe reason for [AddonSourceStatus.FAILED]; never an address, a token or a response body. */
    val reason: String? = null,
)

/** A search in flight or finished, source by source, in the order the sources were given. */
data class AddonSearchProgress(val query: String, val sources: List<AddonSourceResult>) {
    val searching: Boolean get() = sources.any { it.status == AddonSourceStatus.SEARCHING }

    /** Every answered source's rows, round-robin; see [AddonSearch.interleave]. */
    val songs: List<Song> get() = AddonSearch.interleave(sources.map { it.songs })

    fun source(id: String): AddonSourceResult? = sources.firstOrNull { it.id == id }

    /**
     * The merged list as the pages' older single-list parameter: loading until the first source answers, then the rows so far.
     * Sources that are still searching keep their own state in [sources].
     */
    fun asUiState(): com.cosmotune.music.data.model.UiState<List<Song>> =
        if (searching && sources.none { it.songs.isNotEmpty() }) com.cosmotune.music.data.model.UiState.Loading
        else com.cosmotune.music.data.model.UiState.Success(songs)
}

/** Lets a [AddonSearchSource] with a [AddonSearchSource.queueTimeoutMs] say that its own request has begun. */
class AddonSearchStart internal constructor(internal val started: CompletableDeferred<Unit>) :
    AbstractCoroutineContextElement(AddonSearchStart) {
    companion object Key : CoroutineContext.Key<AddonSearchStart>
}

/** The signal a queued source calls once it is actually working; a no-op outside an Add-ons search. */
suspend fun addonSearchStarted(): () -> Unit {
    val element = currentCoroutineContext()[AddonSearchStart]
    return { element?.started?.complete(Unit) }
}

/**
 * The Add-ons source of the Search tab (upstream `2160a822`), shared by the phone and the desktop.
 *
 * The rules it keeps, which the tests pin:
 * - A query leaves for the add-ons only when it is submitted ([shouldSearch]): no typeahead, no completion, no per-keystroke
 *   request goes to a third-party catalogue. YouTube Music is asked only when the listener picks "All", and then only through
 *   its anonymous search, so nothing is recorded in its history and the account's cookies are never involved (the
 *   anonymous-search rule of `36d55dd6`).
 * - Every source is asked at once; a slow source is left out after [TIMEOUT_MS] of its own work, a failing one costs only its own
 *   rows, and cancelling the search (a new query, the field cleared) cancels every request.
 * - [stream] reports each source as it answers, so one slow or failing source never holds back the others' rows.
 * - The answer is round-robin across the sources, so no single catalogue buries the others, and each source gives at most
 *   [LIMIT] rows.
 */
object AddonSearch {
    /** Per-source budget: a slow add-on is left out, not waited on. */
    const val TIMEOUT_MS = 12_000L

    /** Rows asked of, and kept from, each source. */
    const val LIMIT = 20

    /** The "Search in" choice that asks every source. */
    const val ALL = "all"

    /** The "Search in" choice (and source id) of YouTube Music. */
    const val YOUTUBE = "youtube"

    /** Whether [query] may be sent to the add-ons: only an explicit submission of text that is not blank. */
    fun shouldSearch(query: String, submitted: Boolean): Boolean = submitted && query.isNotBlank()

    /**
     * The sources a "Search in" [choice] asks: all of them for [ALL] (or a choice whose source is no longer installed), else the
     * one source it names.
     */
    fun select(sources: List<AddonSearchSource>, choice: String): List<AddonSearchSource> =
        if (choice == ALL) sources else sources.filter { it.id == choice }.ifEmpty { sources }

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
            sources.map { source -> async { searchOne(text, source, timeoutMs, limit).songs } }.awaitAll()
        }
        return interleave(perSource)
    }

    /**
     * [search], reported as it goes: first every source searching, then a new [AddonSearchProgress] each time one of them
     * answers, fails or runs out of time. The flow completes when the last source has.
     */
    fun stream(
        query: String,
        sources: List<AddonSearchSource>,
        timeoutMs: Long = TIMEOUT_MS,
        limit: Int = LIMIT,
    ): Flow<AddonSearchProgress> = channelFlow {
        val text = query.trim()
        if (text.isEmpty() || sources.isEmpty()) return@channelFlow
        val state = MutableStateFlow(AddonSearchProgress(text, sources.map { searching(it) }))
        send(state.value)
        sources.forEach { source ->
            launch {
                val result = searchOne(text, source, timeoutMs, limit)
                state.update { it.replace(result) }
                send(state.value)
            }
        }
    }

    /** One source's answer, or why it has none. Never throws except to cancel. */
    suspend fun searchOne(text: String, source: AddonSearchSource, timeoutMs: Long = TIMEOUT_MS, limit: Int = LIMIT): AddonSourceResult {
        return try {
            val songs = coroutineScope<List<Song>?> {
                val started = CompletableDeferred<Unit>()
                val work = async(AddonSearchStart(started)) { source.search(text, limit).take(limit) }
                work.invokeOnCompletion { started.complete(Unit) }
                if (source.queueTimeoutMs > 0) {
                    if (withTimeoutOrNull(source.queueTimeoutMs) { started.await() } == null) {
                        work.cancel()
                        return@coroutineScope null
                    }
                }
                withTimeoutOrNull(timeoutMs) { work.await() } ?: run { work.cancel(); null }
            }
            if (songs == null) AddonSourceResult(source.id, source.name, AddonSourceStatus.TIMED_OUT, reason = "Timed out")
            else AddonSourceResult(source.id, source.name, AddonSourceStatus.DONE, songs)
        } catch (cancelled: CancellationException) {
            // Our own cancellation (a new query, the field cleared) goes on up. A timeout the source raised inside its own call
            // is that source's failure only, and must not end the others' search.
            currentCoroutineContext().ensureActive()
            AddonSourceResult(source.id, source.name, AddonSourceStatus.TIMED_OUT, reason = "Timed out")
        } catch (access: AddonSearchAccessRequired) {
            AddonSourceResult(source.id, source.name, AddonSourceStatus.NEEDS_VERIFICATION, reason = safeReason(access, "Verify to search"))
        } catch (unsupported: AddonSearchUnsupported) {
            AddonSourceResult(source.id, source.name, AddonSourceStatus.UNSUPPORTED, reason = safeReason(unsupported, "No search"))
        } catch (failure: Exception) {
            AddonSourceResult(source.id, source.name, AddonSourceStatus.FAILED, reason = safeReason(failure, "Search failed"))
        }
    }

    internal fun searching(source: AddonSearchSource) = AddonSourceResult(source.id, source.name, AddonSourceStatus.SEARCHING)

    /**
     * A failure's message when it is short and says nothing that identifies a server or a credential, else [fallback]. Add-on
     * and Subsonic errors can quote a request address, and a Subsonic address carries its token.
     */
    fun safeReason(failure: Throwable, fallback: String): String {
        val message = failure.message?.trim().orEmpty()
        val unsafe = message.isEmpty() || message.length > 120 || message.contains("://") || message.contains('?') ||
            message.contains('=') || message.contains('@') || message.contains("token", ignoreCase = true) ||
            message.contains("password", ignoreCase = true) || message.contains('{')
        return if (unsafe) fallback else message
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

private fun AddonSearchProgress.replace(result: AddonSourceResult) =
    copy(sources = sources.map { if (it.id == result.id) result else it })

/**
 * The Add-ons search a page holds: the current [AddonSearchProgress] as state, a new search replacing (and cancelling) the last,
 * and [retry] re-asking a single source of the current search (after its verification, say) and merging its answer in place.
 * Used by both hosts, so the phone's view model and the desktop's root keep one rule set.
 */
class AddonSearchSession(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = AddonSearch.TIMEOUT_MS,
    private val limit: Int = AddonSearch.LIMIT,
) {
    private val _state = MutableStateFlow<AddonSearchProgress?>(null)
    val state: StateFlow<AddonSearchProgress?> = _state.asStateFlow()
    private var job: Job? = null
    private var sources: List<AddonSearchSource> = emptyList()
    // A StateFlow rather than a plain field so a source answering on another thread reads the current value.
    private val generation = MutableStateFlow(0L)

    /** Asks [sources] for [query]; whatever was in flight is cancelled first. A blank query or no source clears the state. */
    fun start(query: String, sources: List<AddonSearchSource>) {
        cancel()
        val text = query.trim()
        if (text.isEmpty() || sources.isEmpty()) return
        this.sources = sources
        val run = generation.value + 1
        generation.value = run
        _state.value = AddonSearchProgress(text, sources.map(AddonSearch::searching))
        val parent = SupervisorJob(scope.coroutineContext[Job])
        job = parent
        sources.forEach { source -> scope.launch(parent) { ask(run, text, source) } }
    }

    /** Re-asks the source [id] for the current query, if there is one and it is not already searching. */
    fun retry(id: String) {
        val current = _state.value ?: return
        val parent = job ?: return
        val source = sources.firstOrNull { it.id == id } ?: return
        if (current.source(id)?.status == AddonSourceStatus.SEARCHING) return
        val run = generation.value
        _state.update { progress -> progress?.takeIf { run == generation.value }?.replace(AddonSearch.searching(source)) ?: progress }
        scope.launch(parent) { ask(run, current.query, source) }
    }

    /** Stops every request and forgets the search. */
    fun cancel() {
        generation.value = generation.value + 1
        job?.cancel()
        job = null
        _state.value = null
    }

    private suspend fun ask(run: Long, text: String, source: AddonSearchSource) {
        val result = AddonSearch.searchOne(text, source, timeoutMs, limit)
        _state.update { progress -> if (progress != null && run == generation.value && progress.query == text) progress.replace(result) else progress }
    }
}

/**
 * The listener's last "Search in" choice ([AddonSearch.ALL], [AddonSearch.YOUTUBE] or a source id), for a host that keeps it
 * itself. [read] and [write] are the host's storage, so a test never touches the real one.
 */
class AddonSearchChoice(private val read: () -> String?, private val write: (String) -> Unit, default: String = AddonSearch.YOUTUBE) {
    private val _selected = MutableStateFlow(read()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: default)
    val selected: StateFlow<String> = _selected.asStateFlow()

    /** The last choice that was not YouTube Music: where the Add-ons source goes back to. */
    var lastAddonChoice: String = _selected.value.takeIf { it != AddonSearch.YOUTUBE } ?: AddonSearch.ALL
        private set

    fun select(choice: String) {
        val value = choice.trim().takeIf { it.isNotEmpty() && it.length <= 200 } ?: return
        if (value != AddonSearch.YOUTUBE) lastAddonChoice = value
        if (_selected.value == value) return
        _selected.value = value
        write(value)
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
