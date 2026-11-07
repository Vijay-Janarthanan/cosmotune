package com.cosmotune.cache

import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * What a page shows of a [CachedLoad]: the last content it loaded ([data], null until something has loaded, from the network or
 * from the disk), whether a fetch is on its way ([refreshing]), and why the last fetch failed ([error]). A page shows its
 * placeholders only when there is no [data] yet and nothing has failed ([showPlaceholders]); with [data], a failure is a small
 * "Couldn't refresh" note over the content ([refreshFailed]), never a blank page.
 */
data class CachedState<out T>(
    val data: T? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** When [data] was fetched, in the clock's milliseconds (0: never). */
    val updatedAtMs: Long = 0L,
) {
    /** Nothing to show yet and no failure: the only time a page draws its skeleton. */
    val showPlaceholders: Boolean get() = data == null && error == null
    /** The last fetch failed while there was content: the page keeps it and notes that it could not refresh. */
    val refreshFailed: Boolean get() = data != null && error != null
    /** The last fetch failed and nothing was ever loaded: the page's own error state. */
    val failedEmpty: Boolean get() = data == null && error != null
}

/** Turns a cached value into text for the disk and back. A [decode] that throws (an old format) is treated as no copy. */
class CacheCodec<T>(val encode: (T) -> String, val decode: (String) -> T) {
    companion object {
        /** The JSON the caches write: unknown keys (a newer version's) and missing ones (an older version's) are tolerated. */
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false; coerceInputValues = true }

        fun <T> json(serializer: KSerializer<T>): CacheCodec<T> =
            CacheCodec({ json.encodeToString(serializer, it) }, { json.decodeFromString(serializer, it) })
    }
}

/** Where the caches keep their copies between runs: text by name. Implementations are safe to call from several threads. */
interface CacheDisk {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun remove(name: String)
    fun clear()
}

/**
 * A folder of cache files (the app's own cache folder, so the system may clear it): one file per name, written whole through a
 * temporary file, at most [maxEntryBytes] each; when the folder holds more than [maxBytes] the files read or written longest
 * ago go first.
 */
class FileCacheDisk(
    private val directory: File,
    private val maxBytes: Long = 48L * 1024 * 1024,
    private val maxEntryBytes: Int = 6 * 1024 * 1024,
) : CacheDisk {
    private val lock = Any()

    private fun fileOf(name: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        val readable = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        return File(directory, "$readable-${digest.take(16)}.json")
    }

    override fun read(name: String): String? = synchronized(lock) {
        val file = fileOf(name)
        if (!file.isFile) return null
        runCatching { file.readText(Charsets.UTF_8).also { file.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }

    override fun write(name: String, text: String) = synchronized(lock) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val file = fileOf(name)
        if (bytes.size > maxEntryBytes) { file.delete(); return }
        runCatching {
            directory.mkdirs()
            val temporary = File(directory, file.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) { file.delete(); if (!temporary.renameTo(file)) temporary.delete() }
            trim()
        }
        Unit
    }

    override fun remove(name: String) = synchronized(lock) { fileOf(name).delete(); Unit }

    override fun clear() = synchronized(lock) { directory.listFiles()?.forEach { it.delete() }; Unit }

    private fun trim() {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }
}

/**
 * One page's content, stale-while-revalidate: [state] holds the last content at once (from memory, or from the disk after a
 * restart) and [load] refreshes it in the background, swapping the new content in when it arrives. A failed refresh keeps the
 * content and sets [CachedState.error]. Content fetched less than [freshForMs] ago in this run is not fetched again by [load];
 * [refresh] (a pull or a tap) always fetches. Content restored from the disk is shown at once and refreshed.
 *
 * Lives as long as its [PageCache] (the process, for the app's), not as long as the page: leaving a mode and coming back finds it.
 */
class CachedLoad<T> internal constructor(
    val key: String,
    private val freshForMs: Long,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val disk: CacheDisk?,
    private val codec: CacheCodec<T>?,
    private val io: CoroutineDispatcher,
    fetch: suspend () -> T,
) {
    /** The fetch the latest caller handed in (a page asks with its own repository; the newest one wins). */
    @Volatile var fetcher: suspend () -> T = fetch

    private val _state = MutableStateFlow(CachedState<T>())
    val state: StateFlow<CachedState<T>> = _state.asStateFlow()

    private val restoreLock = Mutex()
    @Volatile private var restored = disk == null || codec == null
    /** When content was last fetched in this run (0: only the disk's copy, or nothing). */
    @Volatile private var fetchedAtMs = 0L
    @Volatile private var job: Job? = null
    private val jobLock = Any()

    /** The content was fetched in this run less than [freshForMs] ago. */
    val isFresh: Boolean get() = _state.value.data != null && fetchedAtMs > 0 && clock() - fetchedAtMs in 0 until freshForMs

    /**
     * The page appeared: shows what the disk has if memory has nothing, then fetches unless the content is fresh or a fetch is
     * already on its way. Returns the running job, or null when nothing needed doing.
     */
    fun load(): Job? = start(force = false)

    /** Fetches now whatever the age of the content (a pull to refresh, Try again). */
    fun refresh(): Job = start(force = true)!!

    private fun start(force: Boolean): Job? = synchronized(jobLock) {
        job?.takeIf { it.isActive }?.let { return it }
        if (!force && restored && isFresh) return null
        scope.launch { restore(); if (force || !isFresh) fetchNow() }.also { job = it }
    }

    /** Reads the disk's copy once, when memory holds nothing yet. */
    suspend fun restore() {
        if (restored) return
        restoreLock.withLock {
            if (restored) return
            val text = withContext(io) { runCatching { disk?.read(key) }.getOrNull() }
            val copy = text?.let(::decodeStamped)
            if (copy != null && _state.value.data == null) _state.update { it.copy(data = copy.second, updatedAtMs = copy.first) }
            restored = true
        }
    }

    /** One fetch, in the caller's coroutine; the state shows it as refreshing meanwhile. */
    suspend fun fetchNow() {
        _state.update { it.copy(refreshing = true) }
        try {
            val value = fetcher()
            val now = clock()
            fetchedAtMs = now
            _state.value = CachedState(data = value, refreshing = false, error = null, updatedAtMs = now)
            save(value, now)
        } catch (e: CancellationException) {
            _state.update { it.copy(refreshing = false) }
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(refreshing = false, error = e.message?.takeIf(String::isNotBlank) ?: "Couldn't refresh") }
        }
    }

    /** Replaces the content now (a page's own edit, or content another way loaded), and keeps it on the disk. */
    fun set(value: T) {
        val now = clock()
        fetchedAtMs = now
        restored = true
        _state.value = CachedState(data = value, refreshing = _state.value.refreshing, error = null, updatedAtMs = now)
        scope.launch { save(value, now) }
    }

    /** Changes the content in place (an item removed, a page appended); nothing happens while there is none. */
    fun update(transform: (T) -> T) {
        val current = _state.value.data ?: return
        val next = transform(current)
        _state.update { it.copy(data = next) }
        scope.launch { save(next, _state.value.updatedAtMs) }
    }

    /** Forgets the content, in memory and on the disk (a sign-out). */
    fun clear() {
        synchronized(jobLock) { job?.cancel(); job = null }
        fetchedAtMs = 0
        _state.value = CachedState()
        scope.launch { withContext(io) { runCatching { disk?.remove(key) } } }
    }

    private suspend fun save(value: T, at: Long) {
        val codec = codec ?: return
        val disk = disk ?: return
        withContext(io) { runCatching { disk.write(key, "$at\n" + codec.encode(value)) } }
    }

    private fun decodeStamped(text: String): Pair<Long, T>? = runCatching {
        val at = text.substringBefore('\n').toLong()
        at to codec!!.decode(text.substringAfter('\n'))
    }.getOrNull()
}

/**
 * The [CachedLoad]s of one owner, by key. The app's ([shared]) lives for the process and keeps copies on [CacheDisk] once
 * [configure]d; [of] gives a repository that was handed in (a test's fake) its own, memory-only one that goes with it, so one
 * test's content never shows in another's page. At most [maxEntries] are kept; the ones used longest ago go first.
 */
class PageCache(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
    private val disk: () -> CacheDisk? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxEntries: Int = 160,
) {
    private val entries = object : LinkedHashMap<String, CachedLoad<*>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLoad<*>>): Boolean = size > maxEntries
    }

    /**
     * The entry for [key], made the first time with [freshForMs], [codec] (null: memory only) and [fetch]; a later call hands its
     * own [fetch] to the same entry, so the content survives the page that asked.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> entry(key: String, freshForMs: Long, codec: CacheCodec<T>? = null, fetch: suspend () -> T): CachedLoad<T> = synchronized(entries) {
        val existing = entries[key] as CachedLoad<T>?
        if (existing != null) { existing.fetcher = fetch; return existing }
        CachedLoad(key, freshForMs, scope, clock, codec?.let { disk() }, codec, io, fetch).also { entries[key] = it }
    }

    /** The entry for [key] if one was made. */
    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): CachedLoad<T>? = synchronized(entries) { entries[key] as CachedLoad<T>? }

    /** Reads [key]'s copy on the disk (when it was saved, and the value), without making an entry. */
    suspend fun <T> readSaved(key: String, codec: CacheCodec<T>): Pair<Long, T>? {
        val disk = disk() ?: return null
        return withContext(io) {
            runCatching {
                val text = disk.read(key) ?: return@runCatching null
                text.substringBefore('\n').toLong() to codec.decode(text.substringAfter('\n'))
            }.getOrNull()
        }
    }

    /** Keeps [value] on the disk under [key], in the background. */
    fun <T> writeSaved(key: String, value: T, codec: CacheCodec<T>, at: Long = clock()) {
        val disk = disk() ?: return
        scope.launch(io) { runCatching { disk.write(key, "$at\n" + codec.encode(value)) } }
    }

    /** Forgets every entry whose key starts with [prefix] (an account's, at sign-out), in memory and on the disk. */
    fun forget(prefix: String) {
        val gone = synchronized(entries) { entries.keys.filter { it.startsWith(prefix) }.map { entries.remove(it) } }
        gone.forEach { it?.clear() }
    }

    companion object {
        @Volatile private var diskStore: CacheDisk? = null

        /** The app's caches, for the process. */
        val shared: PageCache = PageCache(disk = { diskStore })

        /** Gives [shared] its disk: the app's cache folder ("pages" under it), called once at start-up. */
        fun configure(directory: File, maxBytes: Long = 48L * 1024 * 1024) {
            diskStore = FileCacheDisk(directory, maxBytes)
        }

        /** Sets the disk directly (tests). */
        fun configure(disk: CacheDisk?) { diskStore = disk }

        private val owned = WeakHashMap<Any, PageCache>()

        /**
         * [shared] when [persistent] (the app's own repository), else a memory-only cache of [owner]'s own that is dropped
         * with it.
         */
        fun of(owner: Any, persistent: Boolean): PageCache =
            if (persistent) shared else synchronized(owned) { owned.getOrPut(owner) { PageCache() } }
    }
}

/** A short freshness window for feeds that change through the day (Home rows, charts, channel lists): 10 minutes. */
const val FEED_FRESH_MS = 10 * 60_000L

/** For lists that change slowly (moods, a directory, a library's catalogue): 15 minutes. */
const val SLOW_FRESH_MS = 15 * 60_000L

/** For what follows the clock closely (a guide's now and next, a subscriptions feed): 5 minutes. */
const val LIVE_FRESH_MS = 5 * 60_000L
