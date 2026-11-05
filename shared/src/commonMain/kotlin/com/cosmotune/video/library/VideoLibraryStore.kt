package com.cosmotune.video.library

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.jvm.Synchronized
import kotlin.random.Random

/** A lock that common code can take: the body runs while no other thread is inside [withLock] of the same instance. */
internal class StoreLock {
    @Synchronized
    fun <R> withLock(block: () -> R): R = block()
}

/** The default log line: standard output (logcat on the phone, the console on the desktop). */
internal fun libraryLog(message: String) {
    println("Cosmotune Video: $message")
}

/**
 * Reads one library file. A missing file is [empty]. A file that cannot be read or parsed is set aside as
 * `<name>.broken-<time>` (spec 8.1, plan task Y-7 step 2), a line is logged, and the result is [empty]: nothing is lost, and
 * the person starts from an empty list instead of a crash.
 */
internal fun <T> loadDocument(files: LibraryFiles, file: LibraryFile, clock: () -> Long, log: (String) -> Unit, decode: (String) -> T, empty: T): T {
    val name = file.fileName
    try {
        val text = files.read(name) ?: return empty
        return decode(text)
    } catch (e: Exception) {
        val aside = "$name.broken-${clock()}"
        log("$name could not be read (${e.message}); keeping it as $aside and starting empty")
        try {
            files.rename(name, aside)
        } catch (second: Exception) {
            log("$name could not be set aside: ${second.message}")
        }
        return empty
    }
}

/**
 * Writes changed files 500 ms after their last change, off the caller's thread (plan task Y-7, step 2). [changed] marks a file
 * and restarts its timer; when the timer ends the file is encoded from the state as it is then ([encode]) and written on
 * [ioDispatcher]. One file's timer does not touch another's, so a position update every 10 s only rewrites `history.json`.
 * [flush] writes what is waiting at once on the calling thread (for the moment the app goes to the background or closes).
 * A failed write is logged and the file stays marked, so the next change or flush tries again.
 */
internal class DebouncedFileWriter(
    private val files: LibraryFiles,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: (String) -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val encode: (name: String) -> String,
) {
    private val stateLock = StoreLock()
    private val ioLock = StoreLock()
    private val dirty = LinkedHashSet<String>()
    private val timers = HashMap<String, Job>()

    /** [name] changed: write it [debounceMs] after the last call. */
    fun changed(name: String) {
        stateLock.withLock {
            dirty.add(name)
            timers[name]?.cancel()
            timers[name] = scope.launch {
                delay(debounceMs)
                withContext(ioDispatcher) { writeNow(name) }
            }
        }
    }

    /** Writes every file that is waiting now, on this thread, and cancels the timers. */
    fun flush() {
        val names = stateLock.withLock {
            timers.values.forEach { it.cancel() }
            timers.clear()
            dirty.toList()
        }
        names.forEach { writeNow(it) }
    }

    private fun writeNow(name: String) {
        ioLock.withLock {
            if (!stateLock.withLock { dirty.remove(name) }) return@withLock
            try {
                files.write(name, encode(name))
            } catch (e: Exception) {
                stateLock.withLock { dirty.add(name) }
                log("$name could not be written: ${e.message}")
            }
        }
    }

    companion object {
        /** Files are written 500 ms after the last change (plan task Y-7). */
        const val DEBOUNCE_MS = 500L
    }
}

/**
 * The library on the device (plan task Y-7; spec 8.1): the lists live in memory as `StateFlow`s, loaded from their files when
 * the store is made (a missing file is an empty list), changed on the caller's thread by [LibraryReducers], and written to
 * their own file 500 ms after the last change to it, on [ioDispatcher], atomically if [files] is (spec decision V8). The state
 * is never read back from disk after construction.
 *
 * A file that cannot be read is set aside as `<name>.broken-<time>` and logged to [log]. Call [flush] when the app is going
 * away: a change younger than 500 ms is otherwise lost with the process.
 */
class VideoLibraryStore(
    private val files: LibraryFiles,
    scope: CoroutineScope,
    private val clock: () -> Long,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = ::libraryLog,
) : VideoLibrary {
    private val stateLock = StoreLock()

    private val subscriptionFlow = MutableStateFlow(
        load(LibraryFile.SUBSCRIPTIONS, LibraryCodec::decodeSubscriptions, emptyList()),
    )
    private val loadedHistory = load(LibraryFile.HISTORY, LibraryCodec::decodeHistory, HistoryDocument(emptyList(), paused = false))
    private val historyFlow = MutableStateFlow(loadedHistory.entries.take(LibraryReducers.HISTORY_CAP))
    private val pausedFlow = MutableStateFlow(loadedHistory.paused)
    private val watchLaterFlow = MutableStateFlow(
        load(LibraryFile.WATCH_LATER, LibraryCodec::decodeItems, emptyList()).take(LibraryReducers.LIST_CAP),
    )
    private val likedFlow = MutableStateFlow(
        load(LibraryFile.LIKED, LibraryCodec::decodeItems, emptyList()).take(LibraryReducers.LIST_CAP),
    )
    private val playlistFlow = MutableStateFlow(
        load(LibraryFile.PLAYLISTS, LibraryCodec::decodePlaylists, emptyList()).take(LibraryReducers.PLAYLIST_CAP),
    )
    private val savedFlow = MutableStateFlow(
        load(LibraryFile.SAVED_PLAYLISTS, LibraryCodec::decodeSavedPlaylists, emptyList()).take(LibraryReducers.SAVED_CAP),
    )
    private val searchFlow = MutableStateFlow(
        load(LibraryFile.SEARCHES, LibraryCodec::decodeSearches, emptyList()).take(LibraryReducers.SEARCH_CAP),
    )

    private val writer = DebouncedFileWriter(files, scope, ioDispatcher, log, encode = ::encodeFile)

    override val subscriptions: StateFlow<List<Subscription>> = subscriptionFlow.asStateFlow()
    override val history: StateFlow<List<HistoryEntry>> = historyFlow.asStateFlow()
    override val historyPaused: StateFlow<Boolean> = pausedFlow.asStateFlow()
    override val watchLater: StateFlow<List<VideoItem>> = watchLaterFlow.asStateFlow()
    override val liked: StateFlow<List<VideoItem>> = likedFlow.asStateFlow()
    override val playlists: StateFlow<List<LocalPlaylist>> = playlistFlow.asStateFlow()
    override val savedPlaylists: StateFlow<List<PlaylistItem>> = savedFlow.asStateFlow()
    override val searches: StateFlow<List<String>> = searchFlow.asStateFlow()

    /** Writes whatever is waiting for its 500 ms now, on the calling thread. Call it when the app goes to the background or closes. */
    fun flush() = writer.flush()

    // ------------------------------------------------------------------ subscriptions

    override fun subscribe(channel: ChannelItem) {
        mutate(subscriptionFlow, LibraryFile.SUBSCRIPTIONS) { LibraryReducers.subscribe(it, channel, clock()) }
    }

    override fun unsubscribe(channelId: String) {
        mutate(subscriptionFlow, LibraryFile.SUBSCRIPTIONS) { LibraryReducers.unsubscribe(it, channelId) }
    }

    // ------------------------------------------------------------------ history

    override fun recordWatch(item: VideoItem, positionMs: Long, durationMs: Long) {
        if (pausedFlow.value) return
        mutate(historyFlow, LibraryFile.HISTORY) { LibraryReducers.recordWatch(it, item, positionMs, durationMs, clock()) }
    }

    override fun positionOf(videoId: String): Long? = historyFlow.value.firstOrNull { it.item.id == videoId }?.positionMs

    override fun removeFromHistory(videoId: String) {
        mutate(historyFlow, LibraryFile.HISTORY) { LibraryReducers.removeFromHistory(it, videoId) }
    }

    override fun clearHistory() {
        mutate(historyFlow, LibraryFile.HISTORY) { if (it.isEmpty()) it else emptyList() }
    }

    override fun setHistoryPaused(paused: Boolean) {
        val changed = stateLock.withLock {
            if (pausedFlow.value == paused) false else {
                pausedFlow.value = paused
                true
            }
        }
        if (changed) writer.changed(LibraryFile.HISTORY.fileName)
    }

    // ------------------------------------------------------------------ watch later and liked

    override fun addToWatchLater(item: VideoItem) {
        mutate(watchLaterFlow, LibraryFile.WATCH_LATER) { LibraryReducers.addToList(it, item) }
    }

    override fun removeFromWatchLater(videoId: String) {
        mutate(watchLaterFlow, LibraryFile.WATCH_LATER) { LibraryReducers.removeFromList(it, videoId) }
    }

    override fun moveInWatchLater(from: Int, to: Int) {
        mutate(watchLaterFlow, LibraryFile.WATCH_LATER) { LibraryReducers.moveInList(it, from, to) }
    }

    override fun setLiked(item: VideoItem, liked: Boolean) {
        mutate(likedFlow, LibraryFile.LIKED) { if (liked) LibraryReducers.like(it, item) else LibraryReducers.removeFromList(it, item.id) }
    }

    // ------------------------------------------------------------------ your playlists

    /**
     * Creates a playlist and returns its id (random, unique among yours). At the cap of 500 playlists nothing is created and
     * the returned id belongs to no playlist, so editing it later does nothing.
     */
    override fun createPlaylist(name: String): String {
        var id = newPlaylistId()
        mutate(playlistFlow, LibraryFile.PLAYLISTS) { list ->
            while (list.any { it.id == id }) id = newPlaylistId()
            LibraryReducers.createPlaylist(list, id, name, clock())
        }
        return id
    }

    override fun renamePlaylist(id: String, name: String) {
        mutate(playlistFlow, LibraryFile.PLAYLISTS) { LibraryReducers.renamePlaylist(it, id, name, clock()) }
    }

    override fun deletePlaylist(id: String) {
        mutate(playlistFlow, LibraryFile.PLAYLISTS) { LibraryReducers.deletePlaylist(it, id) }
    }

    override fun addToPlaylist(id: String, item: VideoItem) {
        mutate(playlistFlow, LibraryFile.PLAYLISTS) { LibraryReducers.addToPlaylist(it, id, item, clock()) }
    }

    override fun removeFromPlaylist(id: String, videoId: String) {
        mutate(playlistFlow, LibraryFile.PLAYLISTS) { LibraryReducers.removeFromPlaylist(it, id, videoId, clock()) }
    }

    override fun moveInPlaylist(id: String, from: Int, to: Int) {
        mutate(playlistFlow, LibraryFile.PLAYLISTS) { LibraryReducers.moveInPlaylist(it, id, from, to, clock()) }
    }

    // ------------------------------------------------------------------ saved playlists and searches

    override fun savePlaylist(item: PlaylistItem) {
        mutate(savedFlow, LibraryFile.SAVED_PLAYLISTS) { LibraryReducers.savePlaylist(it, item) }
    }

    override fun unsavePlaylist(id: String) {
        mutate(savedFlow, LibraryFile.SAVED_PLAYLISTS) { LibraryReducers.unsavePlaylist(it, id) }
    }

    override fun addSearch(query: String) {
        mutate(searchFlow, LibraryFile.SEARCHES) { LibraryReducers.addSearch(it, query) }
    }

    override fun removeSearch(query: String) {
        mutate(searchFlow, LibraryFile.SEARCHES) { LibraryReducers.removeSearch(it, query) }
    }

    override fun clearSearches() {
        mutate(searchFlow, LibraryFile.SEARCHES) { if (it.isEmpty()) it else emptyList() }
    }

    // ------------------------------------------------------------------ export and import

    override fun exportJson(): String = LibraryExport.encode(
        LibraryImport(
            subscriptions = subscriptionFlow.value,
            history = historyFlow.value,
            playlists = playlistFlow.value,
            savedPlaylists = savedFlow.value,
            watchLater = watchLaterFlow.value,
            liked = likedFlow.value,
            searches = searchFlow.value,
        ),
        exportedAtMs = clock(),
    )

    override fun importDocument(doc: LibraryImport): ImportReport {
        val now = clock()
        val changedFiles = ArrayList<LibraryFile>()
        var subscriptionsAdded = 0
        var historyChanged = 0
        var playlistsChanged = 0
        var skipped = 0
        stateLock.withLock {
            val subs = LibraryReducers.mergeSubscriptions(subscriptionFlow.value, doc.subscriptions)
            if (subs.list !== subscriptionFlow.value) {
                subscriptionFlow.value = subs.list
                changedFiles += LibraryFile.SUBSCRIPTIONS
            }
            subscriptionsAdded = subs.added
            skipped += subs.skipped

            val hist = LibraryReducers.mergeHistory(historyFlow.value, doc.history)
            if (hist.list !== historyFlow.value) {
                historyFlow.value = hist.list
                changedFiles += LibraryFile.HISTORY
            }
            historyChanged = hist.added
            skipped += hist.skipped

            val lists = LibraryReducers.mergePlaylists(playlistFlow.value, doc.playlists, now)
            if (lists.list !== playlistFlow.value) {
                playlistFlow.value = lists.list
                changedFiles += LibraryFile.PLAYLISTS
            }
            playlistsChanged = lists.added
            skipped += lists.skipped

            val saved = LibraryReducers.mergeSavedPlaylists(savedFlow.value, doc.savedPlaylists)
            if (saved.list !== savedFlow.value) {
                savedFlow.value = saved.list
                changedFiles += LibraryFile.SAVED_PLAYLISTS
            }
            skipped += saved.skipped

            val later = LibraryReducers.mergeItems(watchLaterFlow.value, doc.watchLater)
            if (later.list !== watchLaterFlow.value) {
                watchLaterFlow.value = later.list
                changedFiles += LibraryFile.WATCH_LATER
            }
            skipped += later.skipped

            val likes = LibraryReducers.mergeItems(likedFlow.value, doc.liked)
            if (likes.list !== likedFlow.value) {
                likedFlow.value = likes.list
                changedFiles += LibraryFile.LIKED
            }
            skipped += likes.skipped

            val searches = LibraryReducers.mergeSearches(searchFlow.value, doc.searches)
            if (searches.list !== searchFlow.value) {
                searchFlow.value = searches.list
                changedFiles += LibraryFile.SEARCHES
            }
            skipped += searches.skipped
        }
        changedFiles.forEach { writer.changed(it.fileName) }
        return ImportReport(subscriptions = subscriptionsAdded, history = historyChanged, playlists = playlistsChanged, skipped = skipped)
    }

    // ------------------------------------------------------------------ plumbing

    /** Applies [transform] to [flow] on this thread; when it changed the list, marks [file] for writing. */
    private fun <T> mutate(flow: MutableStateFlow<List<T>>, file: LibraryFile, transform: (List<T>) -> List<T>) {
        val changed = stateLock.withLock {
            val old = flow.value
            val new = transform(old)
            if (new === old) false else {
                flow.value = new
                true
            }
        }
        if (changed) writer.changed(file.fileName)
    }

    private fun <T> load(file: LibraryFile, decode: (String) -> T, empty: T): T = loadDocument(files, file, clock, log, decode, empty)

    private fun encodeFile(name: String): String = when (name) {
        LibraryFile.SUBSCRIPTIONS.fileName -> LibraryCodec.encodeSubscriptions(subscriptionFlow.value)
        LibraryFile.HISTORY.fileName -> LibraryCodec.encodeHistory(historyFlow.value, pausedFlow.value)
        LibraryFile.WATCH_LATER.fileName -> LibraryCodec.encodeItems(watchLaterFlow.value)
        LibraryFile.LIKED.fileName -> LibraryCodec.encodeItems(likedFlow.value)
        LibraryFile.PLAYLISTS.fileName -> LibraryCodec.encodePlaylists(playlistFlow.value)
        LibraryFile.SAVED_PLAYLISTS.fileName -> LibraryCodec.encodeSavedPlaylists(savedFlow.value)
        LibraryFile.SEARCHES.fileName -> LibraryCodec.encodeSearches(searchFlow.value)
        else -> error("not a file of the library: $name")
    }

    private fun newPlaylistId(): String = "pl-" + Random.nextLong().toULong().toString(16).padStart(16, '0')
}
