package com.cosmotune.podio.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cosmotune.podio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.cosmotune.cache.CacheCodec
import com.cosmotune.cache.FEED_FRESH_MS
import com.cosmotune.cache.PageCache
import com.cosmotune.cache.SLOW_FRESH_MS
import com.cosmotune.ui.orbit.removeAfterUndo

/** How many episodes of the followed shows Keep going lines up after the queue. */
private const val KEEP_GOING = 10

/** A removal that can be taken back: what the notice says, and the way back. */
internal class PodioUndo(val message: String, val undo: () -> Unit)

/** Genres of Apple's podcast chart (id, name). */
internal val PODCAST_GENRES = listOf("1489" to "News", "1303" to "Comedy", "1318" to "Technology", "1321" to "Business", "1324" to "Society & culture",
    "1304" to "Education", "1512" to "Health & fitness", "1545" to "Sports", "1488" to "True crime", "1487" to "History", "1533" to "Science",
    "1310" to "Music", "1301" to "Arts", "1305" to "Kids & family", "1309" to "TV & film", "1483" to "Fiction")
internal val REGIONS = listOf("" to "World", "IN" to "India", "LK" to "Sri Lanka", "US" to "US", "GB" to "UK", "CA" to "Canada", "AU" to "Australia", "DE" to "Germany",
    "FR" to "France", "ES" to "Spain", "IT" to "Italy", "BR" to "Brazil", "MX" to "Mexico", "JP" to "Japan", "KR" to "South Korea", "AE" to "UAE", "SG" to "Singapore",
    "MY" to "Malaysia", "PK" to "Pakistan", "BD" to "Bangladesh", "NP" to "Nepal", "NG" to "Nigeria", "ZA" to "South Africa", "ID" to "Indonesia", "TR" to "Türkiye", "RU" to "Russia", "NL" to "Netherlands")
internal val LANGUAGES = listOf("" to "All languages", "Tamil" to "Tamil", "Telugu" to "Telugu", "Hindi" to "Hindi", "English" to "English", "Malayalam" to "Malayalam",
    "Kannada" to "Kannada", "Marathi" to "Marathi", "Bengali" to "Bengali", "Punjabi" to "Punjabi", "Gujarati" to "Gujarati", "Urdu" to "Urdu", "Sinhala" to "Sinhala",
    "Arabic" to "Arabic", "Spanish" to "Spanish", "French" to "French", "German" to "German", "Portuguese" to "Portuguese", "Italian" to "Italian", "Russian" to "Russian",
    "Japanese" to "Japanese", "Korean" to "Korean", "Chinese" to "Chinese", "Turkish" to "Turkish", "Indonesian" to "Indonesian")

/** An episode of a followed show, for Following and the Podcasts home (AntennaPod's inbox): the episode and the show it came from. */
@kotlinx.serialization.Serializable
internal data class InboxEpisode(val episode: PodcastEpisode, val show: PodcastShow) {
    /** The playable item, with the show's art when the episode has none of its own. */
    fun item(): PodioItem = episode.item().let { if (it.artwork.isBlank() && show.artwork.isNotBlank()) it.copy(artwork = show.artwork) else it }
}

/** What one directory request showed (the stations, or the podcast chart or results and their title), as Podio keeps it. */
@kotlinx.serialization.Serializable
internal data class DirectoryPage(val title: String = "", val stations: List<RadioStation> = emptyList(), val shows: List<PodcastShow> = emptyList())

private val DIRECTORY_CODEC = CacheCodec.json(DirectoryPage.serializer())
private val INBOX_CODEC = CacheCodec.json(kotlinx.serialization.builtins.ListSerializer(InboxEpisode.serializer()))

/**
 * The catalogue Podio uses when the host hands in none: one for the process, so the page caches that are kept by catalogue
 * ([PageCache.of]) survive leaving Podio for another mode.
 */
internal object PodioDefaults {
    val catalogue: PodioCatalogue by lazy { PodioCatalogue(youtubePodcasts = YouTubePodcasts()) }
}

/** The host's ways out: playback, downloads and the native file dialogs. Null when the platform has none. */
internal class PodioHost(
    val onPlay: (PodioItem, Long) -> Unit,
    val onDownloadEpisode: ((PodioItem) -> Unit)?,
    val onRemoveDownload: (suspend (PodioItem) -> Boolean)?,
    val onImportOpmlFile: (suspend () -> String?)?,
    val remoteGlobeControls: Boolean,
    val copyText: (String) -> Unit,
    /**
     * Plays an episode with what follows it in the queue, so the player itself goes on to the next one (with the screen off, or
     * after leaving Podio). Null: the host plays one item at a time ([onPlay]).
     */
    val onPlayQueue: ((PodioItem, Long, List<PodioItem>) -> Unit)? = null,
    /** Replaces what comes after the playing episode in the player, after a change of the queue. */
    val onUpcoming: ((List<PodioItem>) -> Unit)? = null,
    /** The system's share sheet for a text (its subject, the text); null where there is none, and Share copies instead. */
    val share: ((String, String) -> Unit)? = null,
)

/**
 * The state of Podio's pages and what they do, in one place so the phone, tablet and desktop layouts share it. The saveable parts
 * ([query], the filters, the section) are handed in from `rememberSaveable`; the rest lives as long as the page does. Every
 * behaviour here is the one the page had before the revamp (feed loading and its cancellation, older episodes, OPML, favourites).
 */
internal class PodioModel(
    val catalogue: PodioCatalogue,
    val library: PodioLibrary,
    private val scope: CoroutineScope,
    var host: PodioHost,
    queryState: MutableState<String>, languageState: MutableState<String>, countryState: MutableState<String>, genreState: MutableState<String>,
    searchingState: MutableState<Boolean>, searchShowsState: MutableState<Boolean>,
    satelliteState: MutableState<Boolean>, globeFullState: MutableState<Boolean>,
    /**
     * Where the station lists, the podcast charts and the new episodes are kept between visits (and runs): what was shown last
     * comes back at once while it refreshes in the background.
     */
    private val pages: PageCache = PageCache.of(catalogue, persistent = false),
) {
    /** Podio's queue (Play next, Up next), kept beside the library and shared with the playback service's tracker. */
    val queue: PodioQueue = library.queue()
    var query by queryState
    var language by languageState
    var country by countryState
    var genre by genreState
    /** The search page is open; [searchShows] chooses its scope (shows, or stations). */
    var searching by searchingState
    var searchShows by searchShowsState
    var satellite by satelliteState
    var globeFull by globeFullState

    var radio by mutableStateOf(emptyList<RadioStation>())
    var shows by mutableStateOf(emptyList<PodcastShow>())
    var showsTitle by mutableStateOf("")
    var opened by mutableStateOf<PodcastFeed?>(null)
    var archiveLoading by mutableStateOf(false)
    var archiveError by mutableStateOf<String?>(null)
    private var archiveJob: Job? = null
    var selected by mutableStateOf<RadioStation?>(null)
    var nearby by mutableStateOf(emptyList<RadioStation>())
    var directoryLoading by mutableStateOf(false)
    var feedLoading by mutableStateOf(false)
    var requestedFeed by mutableStateOf<PodcastShow?>(null)
    var error by mutableStateOf<String?>(null)
    var request by mutableIntStateOf(0)
    var addFeed by mutableStateOf(false)
    var feedAddress by mutableStateOf("")
    var importingOpml by mutableStateOf(false)
    var pickingFile by mutableStateOf(false)
    var opmlText by mutableStateOf("")
    var opmlError by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    private var openJob: Job? = null
    /** What was last asked to play, for its art in the players. */
    var lastPlayed by mutableStateOf<PodioItem?>(null)
    /** The episode whose details sheet is open, and the show it belongs to. */
    var episodeSheet by mutableStateOf<InboxEpisode?>(null)
    /** Heard on the radio's full list is open. */
    var songsOpen by mutableStateOf(false)

    fun clearRadioHistory() {
        val before = library.state.value.let { it.heardSongs to it.recentStations }
        save(PodioHistory::clear)
        offerUndo(PodioUndo("Radio history cleared") { save { it.copy(heardSongs = before.first, recentStations = before.second) } })
    }

    /** The followed show whose settings sheet is open. */
    var showSettingsFor by mutableStateOf<PodcastShow?>(null)
    /** The phone's episode player sheet is open. */
    var playerSheet by mutableStateOf(false)
    /** The queue page (Up next) is open over the section. */
    var queueOpen by mutableStateOf(false)
    /**
     * The window's "Removed · Undo" snackbar ([com.cosmotune.ui.orbit.LocalOrbitUndo]), which Podio's removals (the queue, the inbox,
     * an unfollow, a download) offer their way back on; null where no shell draws one, and a removal then offers no undo.
     */
    var undoBar: com.cosmotune.ui.orbit.OrbitUndoState? = null

    /** Shows [offer] on the window's snackbar for its 10 s. */
    fun offerUndo(offer: PodioUndo) { undoBar?.show(offer.message, offer.undo) }

    var inbox by mutableStateOf(emptyList<InboxEpisode>())
    var inboxLoading by mutableStateOf(false)
    var inboxError by mutableStateOf<String?>(null)
    private var inboxFor: List<String>? = null
    private var inboxGeneration = 0L

    val loading: Boolean get() = feedLoading || requestedFeed == null && directoryLoading

    fun save(change: (PodioSaved) -> PodioSaved) { scope.launch {
        try { withContext(Dispatchers.IO) { library.update(change) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Couldn't save your Podio library. Check available storage and retry." }
    } }

    fun toggleFavourite(station: RadioStation) = save { old ->
        old.copy(stations = if (old.stations.any { it.id == station.id }) old.stations.filterNot { it.id == station.id } else old.stations + station)
    }

    /** The station dialog is open: null closed, a blank station to add one, a saved one to change it. */
    var stationDialog by mutableStateOf<RadioStation?>(null)
    var stationDialogError by mutableStateOf<String?>(null)
    var savingStation by mutableStateOf(false)

    fun addStation() { stationDialogError = null; stationDialog = RadioStation("", "", "") }
    fun editStation(station: RadioStation) { stationDialogError = null; stationDialog = station }

    /**
     * Saves the station of the dialog: a playlist's address becomes the stream it names. A new station is added to Your stations
     * and plays; an edited one keeps its place there.
     */
    fun saveStation(name: String, address: String, picture: String, editing: RadioStation?, onDone: () -> Unit) {
        if (savingStation) return
        savingStation = true; stationDialogError = null
        scope.launch {
            try {
                val stream = catalogue.stationStream(address)
                val changed = editing?.takeIf { it.id.isNotEmpty() }
                val station = if (changed != null && !PodioStationLinks.isCustom(changed)) changed.copy(name = name.trim().ifBlank { changed.name }, url = stream,
                        artwork = PodioCatalogue.httpUrl(picture).orEmpty())
                    else requireNotNull(PodioStationLinks.station(name, stream, picture, changed?.country.orEmpty(), changed?.tags.orEmpty())) { "Enter an HTTP or HTTPS address." }
                withContext(Dispatchers.IO) { library.update { PodioStationLinks.put(it, station, replacing = changed?.id) } }
                if (selected?.id == changed?.id) selected = station
                onDone()
                if (changed == null) { playStation(station); notice = "Added ${station.name} to your stations." } else notice = "Saved ${station.name}."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { stationDialogError = when (failure) {
                is IllegalArgumentException -> failure.message ?: "Enter an HTTP or HTTPS address."
                else -> "Couldn't read this address. Check it and try again."
            } }
            finally { savingStation = false }
        }
    }

    fun toggleFollow(show: PodcastShow) { scope.launch {
        try {
            var added = false
            withContext(Dispatchers.IO) { library.update { old ->
                added = old.shows.none { it.id == show.id }
                old.copy(shows = if (added) old.shows + show else old.shows.filterNot { it.id == show.id })
            } }
            // Following is an explicit freshness request, even when an older inbox cache is still fresh.
            if (added) loadInbox(library.state.value.shows, force = true, episodeLimit = library.state.value.inboxEpisodesPerShow)
            else offerUndo(PodioUndo("Unfollowed ${show.title.ifBlank { "the show" }}") { toggleFollow(show) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Couldn't save or refresh your followed show. Retry from Following." }
    } }

    /** The playable item of [entry] with its show's playback choices (skip silence). */
    fun itemOf(entry: InboxEpisode): PodioItem = entry.item().copy(skipSilence = library.state.value.skipSilenceByShow[entry.show.id])

    private fun itemOf(entry: PodioQueueEntry): PodioItem = itemOf(InboxEpisode(entry.episode, entry.show))

    fun playEpisode(entry: InboxEpisode) {
        val item = itemOf(entry)
        start(entry, item, library.resumePosition(item.id, entry.episode.durationSeconds * 1000))
    }

    fun playEpisodeAt(entry: InboxEpisode, positionMs: Long) = start(entry, itemOf(entry), positionMs.coerceAtLeast(0))

    /** [entry] plays now: it leaves the queue, and what is queued after it goes to the player with it. */
    private fun start(entry: InboxEpisode, item: PodioItem, positionMs: Long) {
        lastPlayed = item
        runCatching { queue.update { PodioQueueOrder.play(it, PodioQueueEntry(entry.episode, entry.show)) } }
        val queuePlay = host.onPlayQueue
        if (queuePlay != null) queuePlay(item, positionMs, upcoming()) else host.onPlay(item, positionMs)
    }

    /**
     * What the player goes on to after the playing episode: the queue in its order, then (with Keep going) the newest episodes of
     * the followed shows that are neither heard, started, seen nor queued.
     */
    fun upcoming(state: PodioQueueState = queue.state.value): List<PodioItem> {
        val queued = state.entries.map(::itemOf)
        if (!state.keepGoing) return queued
        val taken = queued.map { it.id }.toSet() + listOfNotNull(state.current?.id, lastPlayed?.id)
        val saved = library.state.value
        val more = inbox.asSequence().map { it to itemOf(it) }.filter { (_, item) ->
            item.id !in taken && item.id !in saved.completedEpisodes && item.id !in saved.seenEpisodes && (saved.progress[item.id] ?: 0) <= 0
        }.take(KEEP_GOING).map { it.second }.toList()
        return queued + more
    }

    private fun changeQueue(change: (PodioQueueState) -> PodioQueueState) {
        try { queue.update(change) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Couldn't save your queue. Check available storage and retry." }
    }

    fun playNext(entry: InboxEpisode) { changeQueue { PodioQueueOrder.playNext(it, PodioQueueEntry(entry.episode, entry.show)) }; notice = "Plays next: ${entry.episode.title}" }

    fun addToQueue(entry: InboxEpisode) { changeQueue { PodioQueueOrder.add(it, PodioQueueEntry(entry.episode, entry.show)) }; notice = "Added to Up next" }

    fun removeFromQueue(id: String) {
        val state = queue.state.value
        val index = state.entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val removed = state.entries[index]
        changeQueue { PodioQueueOrder.remove(it, id) }
        offerUndo(PodioUndo("Removed from the queue") { changeQueue { PodioQueueOrder.restore(it, removed, index) } })
    }

    /** The chosen queue entries to the top (or the end) of their group. */
    fun moveQueuedToEdge(ids: Set<String>, top: Boolean) {
        changeQueue { PodioQueueOrder.moveToEdge(it, ids, top) }
        notice = (if (ids.size == 1) "1 episode" else "${ids.size} episodes") + if (top) " moved to the top" else " moved to the end"
    }

    /** Takes the chosen entries out of the queue, with one Undo. */
    fun removeQueued(ids: Set<String>) {
        val before = queue.state.value.entries
        if (before.none { it.id in ids }) return
        changeQueue { PodioQueueOrder.removeAll(it, ids) }
        offerUndo(PodioUndo(if (ids.size == 1) "Removed from the queue" else "${ids.size} removed from the queue") {
            changeQueue { now -> now.copy(entries = before.filter { it.id in ids || now.entries.any { e -> e.id == it.id } } + now.entries.filter { e -> before.none { it.id == e.id } }) }
        })
    }

    fun toggleFavouriteEpisode(entry: InboxEpisode) {
        val was = PodioFavourites.isFavourite(library.state.value, entry.item().id)
        save { PodioFavourites.toggle(it, PodioQueueEntry(entry.episode, entry.show)) }
        notice = if (was) "Removed from favourites" else "Added to favourites"
    }

    fun setShowTags(showId: String, tags: Collection<String>) = save { PodioTags.set(it, showId, tags) }

    fun moveInQueue(from: Int, to: Int) = changeQueue { PodioQueueOrder.move(it, from, to) }
    fun clearPlayNext() = changeQueue(PodioQueueOrder::clearPlayNext)
    fun clearQueue() {
        val before = queue.state.value.entries
        if (before.isEmpty()) return
        changeQueue(PodioQueueOrder::clear)
        offerUndo(PodioUndo("Queue cleared") { changeQueue { it.copy(entries = before + it.entries) } })
    }
    fun shuffleQueue() = changeQueue { PodioQueueOrder.shuffle(it) }
    fun setKeepGoing(on: Boolean) = changeQueue { it.copy(keepGoing = on) }

    /** Plays a queued episode at once (a tap on its row in Up next). */
    fun playQueued(entry: PodioQueueEntry) = playEpisode(InboxEpisode(entry.episode, entry.show))

    /** The speed chosen for [showId] with "Use for this show", or null. */
    fun speedFor(showId: String?): Float? = showId?.let { library.state.value.speedByShow[it] }

    fun setShowSpeed(showId: String, speed: Float?) = save { old ->
        old.copy(speedByShow = if (speed == null || speed == 1f) old.speedByShow - showId else old.speedByShow + (showId to speed.coerceIn(0.5f, 3f)))
    }

    /** Puts [ids] away from Following's New list (they stay in the show's own list). */
    fun markSeen(ids: Collection<String>, undoable: Boolean = true) {
        val fresh = ids.filterNot { it in library.state.value.seenEpisodes }
        if (fresh.isEmpty()) return
        save { it.copy(seenEpisodes = it.seenEpisodes + fresh) }
        if (undoable) offerUndo(PodioUndo(if (fresh.size == 1) "Marked as seen" else "${fresh.size} marked as seen") {
            save { it.copy(seenEpisodes = it.seenEpisodes - fresh.toSet()) }
        })
    }

    /** Marks [positionMs] in the episode [item] (the player's Bookmark). */
    fun addBookmark(item: PodioItem, positionMs: Long) {
        if (!item.id.startsWith("podio:episode:")) return
        save { PodioBookmarks.add(it, PodioBookmark(item.id, positionMs, item.title, item.author, item.url, item.artwork, item.durationSeconds, createdMs = System.currentTimeMillis())) }
        notice = "Bookmarked at ${PodioFormat.clock(positionMs)}"
    }

    /** Plays a bookmark's episode from its time. */
    fun playBookmark(mark: PodioBookmark) {
        val item = mark.item().copy(skipSilence = PodioShowRules.showOf(mark.episodeId, library.state.value, queue.state.value, mark.showTitle)
            ?.let { library.state.value.skipSilenceByShow[it] })
        if (PodioCatalogue.httpUrl(item.url) == null) { error = "This bookmark's episode can't be played any more."; return }
        lastPlayed = item
        host.onPlay(item, mark.positionMs)
    }

    fun removeBookmark(mark: PodioBookmark) {
        save { PodioBookmarks.remove(it, mark.key) }
        offerUndo(PodioUndo("Bookmark removed") { save { PodioBookmarks.add(it, mark) } })
    }

    /** Shares [text] through the system's share sheet, or copies it where there is none. */
    fun shareText(subject: String, text: String) {
        val share = host.share
        if (share != null) share(subject, text) else { host.copyText(text); notice = "Copied the link." }
    }

    /** A show to share: its name and its page (or its feed). */
    fun shareShow(show: PodcastShow) = shareText(show.title.ifBlank { "Podcast" }, listOf(show.title, show.website.ifBlank { show.feed }).filter(String::isNotBlank).joinToString("\n"))

    /** An episode to share, at [positionMs] when given ("listen from 12:34"). */
    fun shareEpisode(episode: PodcastEpisode, show: String, positionMs: Long? = null) {
        val link = if (positionMs != null && positionMs > 0) PodioBookmarks.linkAt(episode.url, positionMs) else episode.url
        val at = positionMs?.takeIf { it > 0 }?.let { " (from ${PodioFormat.clock(it)})" }.orEmpty()
        shareText(episode.title, "${episode.title} · ${show.ifBlank { episode.show }}$at\n$link")
    }

    fun setRefreshInBackground(on: Boolean) = save { it.copy(refreshInBackground = on) }
    fun setAutoDownloadNew(on: Boolean) = save { it.copy(autoDownloadNew = on) }

    fun play(item: PodioItem) {
        val resume = if (item.live) 0 else library.resumePosition(item.id, item.durationSeconds * 1000)
        lastPlayed = item
        host.onPlay(item, resume)
    }

    fun playStation(station: RadioStation) {
        selected = station; PodioRecorder.playing(station); play(station.item())
        save { PodioHistory.played(it, station) }
        scope.launch { catalogue.stationPlayed(station.id) }
    }

    fun open(show: PodcastShow) {
        archiveJob?.cancel(); archiveLoading = false; archiveError = null
        val previous = openJob
        previous?.cancel()
        // Another show starts from its skeleton; a refresh of the same one keeps its list on screen.
        if (opened?.show?.feed != show.feed && requestedFeed?.feed != show.feed) opened = null
        requestedFeed = show
        openJob = scope.launch {
            previous?.join()
            feedLoading = true; error = null
            try { opened = catalogue.feed(show.feed) }
            catch (e: CancellationException) { throw e }
            catch (failure: Exception) { error = when {
                PodioCatalogue.isSpotifyLink(show.feed) -> "No matching public feed was available for this Spotify link. Try the publisher's RSS feed; Spotify-only audio isn't supported."
                YouTubePodcasts.showId(show.feed) != null && failure is IllegalArgumentException && failure.message.orEmpty().contains("not identified as a podcast") -> "This YouTube playlist isn't identified as a podcast show."
                YouTubePodcasts.showId(show.feed) != null -> "Couldn't load this public YouTube Music podcast. Please retry."
                else -> "Couldn't load this podcast feed. Please retry."
            } }
            finally { feedLoading = false }
        }
    }

    fun browse() {
        archiveJob?.cancel(); archiveLoading = false; archiveError = null
        // Keep the cancelled job so a subsequent open can join its final state update.
        openJob?.cancel()
        requestedFeed = null; opened = null; feedLoading = false; error = null
    }

    fun moreEpisodes() {
        val feed = opened ?: return
        if (archiveLoading || feedLoading || feed.nextPage == null) return
        archiveLoading = true; archiveError = null
        archiveJob = scope.launch {
            try { opened = catalogue.moreEpisodes(feed) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { archiveError = "Couldn't load older episodes. Your current list is still available." }
            finally { archiveLoading = false }
        }
    }

    fun openLink(address: String, onOpened: () -> Unit) {
        val url = PodioCatalogue.httpUrl(address)
        addFeed = false
        if (url == null) { error = "Enter an HTTP or HTTPS feed URL."; return }
        onOpened()
        open(PodcastShow(PodioCatalogue.identity(url), "", "", url))
    }

    fun importSubscriptions(text: String, onImported: () -> Unit) { scope.launch {
        try {
            val added = withContext(Dispatchers.IO) { library.importOpml(text) }
            notice = "Imported $added new podcast subscriptions."; importingOpml = false; onImported()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = e.message?.take(180) ?: "Couldn't import this OPML document."
            if (importingOpml) opmlError = message else error = message
        }
    } }

    /** "Import OPML": the native file dialog where the host has one, else the paste dialog. */
    fun importOpml(onImported: () -> Unit) {
        val pick = host.onImportOpmlFile
        if (pick == null) { pasteOpml(); return }
        scope.launch {
            pickingFile = true
            try { pick()?.let { importSubscriptions(it, onImported) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't read that OPML file. Please retry." }
            finally { pickingFile = false }
        }
    }

    fun pasteOpml() { opmlText = ""; opmlError = null; importingOpml = true }

    /**
     * The newest episodes of the followed shows (up to 40 shows, four feeds at a time), newest first. A show
     * that fails is left out; only when every one fails is it an error. The same follows are not fetched twice unless [force].
     */
    suspend fun loadInbox(follows: List<PodcastShow>, force: Boolean, episodeLimit: Int = 12) {
        val limit = episodeLimit.coerceIn(1, 24)
        val key = listOf(limit.toString()) + follows.map { it.id }
        if (!force && key == inboxFor && inboxError == null) return
        val generation = ++inboxGeneration
        if (follows.isEmpty()) { inbox = emptyList(); inboxFor = key; inboxError = null; return }
        // The episodes these follows showed last time come back at once (from memory, or the disk after a restart); they are
        // fetched again only when older than the freshness window, or on Refresh.
        val cacheKey = "podio:inbox:" + follows.joinToString(",") { it.id }.hashCode() + if (limit == 12) "" else ":$limit"
        val entry = pages.entry(cacheKey, FEED_FRESH_MS, INBOX_CODEC) { fetchInbox(follows, limit) }
        entry.restore()
        if (generation != inboxGeneration) return
        entry.state.value.data?.let { inbox = it }
        if (!force && entry.isFresh) { inboxFor = key; inboxError = null; return }
        inboxLoading = true; inboxError = null
        try {
            entry.fetchNow()
            if (generation != inboxGeneration) return
            val state = entry.state.value
            if (state.error != null) inboxError = "Couldn't refresh your shows. Check your connection and try again."
            else { inbox = state.data.orEmpty(); inboxFor = key; learn(inbox) }
        } finally { if (generation == inboxGeneration) inboxLoading = false }
    }

    /** What Following just fetched is what the background refresh need not announce again ([PodioRefresh.learn]). */
    private fun learn(entries: List<InboxEpisode>) {
        val seen = entries.groupBy { it.show.id }.map { (_, list) -> list.first().show to list.map { it.episode } }
        save { it.copy(knownEpisodes = PodioRefresh.learn(it.knownEpisodes, seen), lastRefreshMs = System.currentTimeMillis()) }
    }

    /** Marks an episode heard (or not), as its row's or sheet's "Mark played" does. */
    fun markPlayed(item: PodioItem, durationMs: Long, played: Boolean) = save { old ->
        if (played) old.copy(progress = (old.progress - item.id) + (item.id to durationMs.coerceAtLeast(1)), completedEpisodes = old.completedEpisodes + item.id)
        else old.copy(progress = old.progress - item.id, completedEpisodes = old.completedEpisodes - item.id)
    }

    private suspend fun fetchInbox(follows: List<PodcastShow>, episodeLimit: Int): List<InboxEpisode> {
        val permits = Semaphore(4)
        val fetched = coroutineScope {
            follows.take(40).map { show -> async { permits.withPermit {
                try {
                    val feed = catalogue.feed(show.feed)
                    val shown = if (show.artwork.isBlank()) show.copy(artwork = feed.show.artwork) else show
                    feed.episodes.sortedByDescending { PodioFormat.publishedAt(it.published) ?: Long.MIN_VALUE }
                        .take(episodeLimit).map { InboxEpisode(it, shown) }
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            } } }.awaitAll()
        }
        if (fetched.all { it == null }) error("Every followed show failed")
        return fetched.filterNotNull().flatten().distinctBy { it.episode.id }
            .sortedByDescending { PodioFormat.publishedAt(it.episode.published) ?: Long.MIN_VALUE }.take(200)
    }

    /** Fetches what the current section or search scope shows: the stations, or the podcast chart or results. */
    suspend fun loadDirectory(stations: Boolean) {
        // Retry and Clear filters ask again whatever the age of what is shown.
        val force = request != servedRequest
        servedRequest = request
        val storefront = country.ifBlank { java.util.Locale.getDefault().country.ifBlank { "US" } }
        val typed = query.trim()
        val key = if (stations) "podio:stations:$query|$country|$language" else "podio:shows:$storefront|$genre|$language|$typed"
        val asked = listOf(query, country, language, genre)
        // What this request showed last comes back at once (from memory, or the disk after a restart) and stays while it refreshes;
        // inside the freshness window it is not asked for again.
        val entry = pages.entry(key, SLOW_FRESH_MS, DIRECTORY_CODEC) { fetchDirectory(stations, asked[0], asked[1], asked[2], asked[3], storefront, typed) }
        entry.restore()
        entry.state.value.data?.let { publish(it, stations) }
        if (!force && entry.isFresh) { if (requestedFeed == null) error = null; return }
        directoryLoading = true
        if (requestedFeed == null) error = null
        try {
            entry.fetchNow()
            val state = entry.state.value
            state.data?.let { publish(it, stations) }
            if (state.error != null && requestedFeed == null) error = "Couldn't reach the directory. Check your connection and try again."
        } finally { directoryLoading = false }
    }

    private var servedRequest = 0

    private fun publish(page: DirectoryPage, stations: Boolean) {
        if (stations) radio = page.stations else { shows = page.shows; showsTitle = page.title }
    }

    private suspend fun fetchDirectory(stations: Boolean, query: String, country: String, language: String, genre: String, storefront: String, typed: String): DirectoryPage {
        if (stations) return DirectoryPage(stations = PodioLocal.rank(catalogue.stations(query, country, language), PodioLocal.current()))
        val genreName = PODCAST_GENRES.firstOrNull { it.first == genre }?.second
        return if (typed.isNotBlank()) DirectoryPage("Results for \"$typed\"", shows = catalogue.podcasts(listOf(typed, language).filter(String::isNotBlank).joinToString(" "), storefront))
        else if (language.isBlank()) {
            val chart = try { catalogue.topPodcasts(storefront, genre.ifBlank { null }) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            DirectoryPage((genreName ?: "Popular") + " · " + REGIONS.firstOrNull { it.first == country }?.second.orEmpty().ifBlank { "top charts" },
                shows = chart.ifEmpty { catalogue.podcasts(genreName ?: "popular podcasts", storefront) })
        } else DirectoryPage("$language ${genreName ?: "podcasts"}", shows = catalogue.podcasts("$language ${genreName ?: "podcasts"}", storefront))
    }

    /** Clears the search and both filters, and asks again. */
    fun clearFilters() { browse(); country = ""; language = ""; query = ""; genre = ""; request++ }

    val filtered: Boolean get() = country.isNotBlank() || language.isNotBlank() || query.isNotBlank() || genre.isNotBlank()

    /** "2 stations · Worldwide", "40 stations · India". */
    fun stationCount(): String = "${radio.size} ${if (radio.size == 1) "station" else "stations"}" +
        if (country.isBlank()) " · Worldwide" else " · " + (REGIONS.firstOrNull { it.first == country }?.second ?: country)

    /** Downloads taken off the lists while their Undo runs; each is deleted once its 10 s are up ([removeDownload]). */
    var removingDownloads by mutableStateOf(emptySet<String>())

    /**
     * Removes the downloaded [item] with "Removed · Undo" instead of asking first: it leaves the lists at once and its file is deleted
     * only once Undo has run out, so Undo puts it back as it was.
     */
    fun removeDownload(item: PodioItem) {
        val remove = host.onRemoveDownload ?: return
        if (item.id in removingDownloads) return
        undoBar.removeAfterUndo(item.title.ifBlank { "the episode" },
            hide = { removingDownloads = removingDownloads + item.id },
            restore = { removingDownloads = removingDownloads - item.id },
        ) {
            val removed = try { remove(item) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (!removed) error = "Couldn't remove the downloaded episode."
            // The host's list drops it as the file goes; once it has, the id may come back with a new download.
            else delay(2_000)
            removingDownloads = removingDownloads - item.id
        }
    }

    /** Removes every played download of [downloads] at once, with one "Removed · Undo" (Downloads' "Remove played"). */
    fun removePlayedDownloads(downloads: List<PodioItem>, playingId: String?) {
        val remove = host.onRemoveDownload ?: return
        val played = PodioRetention.played(downloads, library.state.value, playingId).filter { it.id !in removingDownloads }
        if (played.isEmpty()) return
        val ids = played.map { it.id }.toSet()
        undoBar.removeAfterUndo(if (played.size == 1) played.single().title.ifBlank { "the episode" } else "${played.size} played downloads",
            hide = { removingDownloads = removingDownloads + ids },
            restore = { removingDownloads = removingDownloads - ids },
        ) {
            val failed = played.count { item -> !(try { remove(item) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }) }
            if (failed > 0) error = "Couldn't remove $failed of the played downloads."
            delay(2_000)
            removingDownloads = removingDownloads - ids
        }
    }

    private var retaining = false

    /**
     * Removes the downloads that "Remove played downloads" and "Downloads to keep" (the app's or a show's own) no longer keep, and
     * says how many went. The playing episode and started ones stay.
     */
    suspend fun applyRetention(downloads: List<PodioItem>, playingId: String?) {
        val remove = host.onRemoveDownload ?: return
        if (retaining) return
        val gone = PodioRetention.toRemove(downloads.filter { it.id !in removingDownloads }, library.state.value, playingId, queue.state.value)
        if (gone.isEmpty()) return
        retaining = true
        try {
            var count = 0
            for (item in gone) if (try { remove(item) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }) count++
            if (count > 0) notice = if (count == 1) "Removed a download you no longer need: ${gone.first().title}" else "Removed $count downloads you no longer need"
        } finally { retaining = false }
    }

    /** Where recordings of stations are kept; null where the host keeps none. */
    var recordingsFolder: java.io.File? = null
    /** The recordings kept, newest first (Downloads' Recordings). */
    var recordings by mutableStateOf(emptyList<PodioRecording>())
    var removingRecordings by mutableStateOf(emptySet<String>())

    suspend fun loadRecordings() {
        val folder = recordingsFolder ?: return
        recordings = withContext(Dispatchers.IO) { PodioRecordings.list(folder) }
    }

    fun startRecording(station: RadioStation) {
        val folder = recordingsFolder ?: return
        // The recording is what the player reads, so the station plays first.
        if (lastPlayed?.id != station.item().id) playStation(station)
        PodioRecorder.start(station, folder, split = library.state.value.recordBySong)
        notice = if (library.state.value.recordBySong) "Recording ${station.name}: each song is saved as its own file in Downloads." else "Recording ${station.name} to Downloads."
    }

    fun stopRecording() { PodioRecorder.stop(); scope.launch { loadRecordings() } }

    /** Removes a recording with "Removed · Undo"; its file goes once Undo has run out. */
    fun removeRecording(recording: PodioRecording) {
        val path = recording.file.absolutePath
        undoBar.removeAfterUndo(recording.title.ifBlank { "the recording" },
            hide = { removingRecordings = removingRecordings + path },
            restore = { removingRecordings = removingRecordings - path },
        ) {
            val gone = withContext(Dispatchers.IO) { recording.file.delete() }
            if (!gone) error = "Couldn't remove the recording."
            loadRecordings()
            removingRecordings = removingRecordings - path
        }
    }

    /** What a download button shows for [item], and the word for its state. */
    fun downloadMark(item: PodioItem, downloaded: List<PodioItem>, running: Set<String>): DownloadMark = when {
        host.onDownloadEpisode == null -> DownloadMark.NONE
        downloaded.any { it.id == item.id } -> DownloadMark.DONE
        item.id in running -> DownloadMark.RUNNING
        else -> DownloadMark.AVAILABLE
    }
}

/**
 * The listener's country and language (Music's discovery preferences: India and Tamil here), so the station lists open on what is
 * near them rather than on the directory's most clicked worldwide. [country] is ISO alpha-2 upper case, [language] the English name
 * Radio Browser uses ("Tamil"); either may be empty.
 */
data class PodioPlace(val country: String, val language: String)

object PodioLocal {
    fun current(): PodioPlace {
        val region = runCatching { com.cosmotune.music.data.recommendations.DiscoveryPreferences.state.value }.getOrNull()
        val country = (region?.country ?: java.util.Locale.getDefault().country).takeIf { it != "ZZ" }.orEmpty().uppercase()
        val language = region?.musicLanguage?.takeIf { it.isNotBlank() }
            ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH) }.orEmpty()
        return PodioPlace(country, language)
    }

    /** 0 the listener's country in their language, 1 their country, 2 their language elsewhere, 3 the rest. */
    fun closeness(station: com.cosmotune.podio.RadioStation, place: PodioPlace): Int {
        val country = place.country.isNotEmpty() && station.countryCode.equals(place.country, ignoreCase = true)
        val language = place.language.isNotEmpty() && station.language.split(',', ';', '/').any { it.trim().equals(place.language, ignoreCase = true) }
        return when {
            country && language -> 0
            country -> 1
            language -> 2
            else -> 3
        }
    }

    /** [stations] (most listened first, as the directory gives them) with the listener's own first, each group keeping its order. */
    fun rank(stations: List<com.cosmotune.podio.RadioStation>, place: PodioPlace): List<com.cosmotune.podio.RadioStation> =
        if (place.country.isEmpty() && place.language.isEmpty()) stations else stations.sortedBy { closeness(it, place) }
}
