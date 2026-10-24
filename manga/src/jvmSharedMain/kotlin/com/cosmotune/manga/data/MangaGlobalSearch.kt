package com.cosmotune.manga.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class MangaSearchResults(val books: List<MangaBook> = emptyList(), val errors: Map<String, String> = emptyMap(),
    val completed: Int = 0, val total: Int = 0, val truncated: Boolean = false)

/** First-page fanout streams partial results; one unavailable source cannot hide the others. */
fun mangaGlobalSearch(configurations: List<MangaSourceConfig>, query: String, timeoutMs: Long = 20_000,
    browse: suspend (MangaSourceConfig, String) -> MangaBrowsePage): Flow<MangaSearchResults> = channelFlow {
    val term = query.trim()
    require(term.length in 2..500) { "Enter at least two characters to search enabled sources" }
    val sources = configurations.filter { it.enabled }.distinctBy { it.id }
    require(sources.size <= 2_000) { "Enable at most 2,000 sources for one search" }
    require(timeoutMs in 1..60_000)
    val permits = Semaphore(3)
    val lock = Mutex()
    val results = LinkedHashMap<String, List<MangaBook>>()
    val errors = LinkedHashMap<String, String>()
    var completed = 0
    var truncated = false
    send(MangaSearchResults(total = sources.size))
    sources.forEach { config -> launch {
        permits.withPermit {
            var books = emptyList<MangaBook>()
            var error: String? = null
            try {
                val page = withTimeout(timeoutMs) { browse(config, term) }
                require(page.books.all { it.sourceId == config.id }) { "Source returned another site's series" }
                books = page.books.distinctBy { it.id }.take(25)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                error = if (failure is TimeoutCancellationException) "Source timed out" else failure.message?.take(200) ?: "Source could not be searched"
            }
            lock.withLock {
                val room = (2_000 - results.values.sumOf { it.size }).coerceAtLeast(0)
                if (books.size > room) truncated = true
                results[config.id] = books.take(room)
                error?.let { errors[config.id] = it }
                completed++
                send(MangaSearchResults(sources.flatMap { results[it.id].orEmpty() }, errors.toMap(), completed, sources.size, truncated))
            }
        }
    } }
}.buffer(Channel.CONFLATED).flowOn(Dispatchers.IO)
