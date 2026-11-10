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
    val onExportOpmlFile: (suspend (String) -> Boolean)?,
    val onShareOpmlFile: (suspend (String) -> Unit)?,
    val remoteGlobeControls: Boolean,
    val copyText: (String) -> Unit,
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
    /** The phone's episode player sheet is open. */
    var playerSheet by mutableStateOf(false)

    var inbox by mutableStateOf(emptyList<InboxEpisode>())
    var inboxLoading by mutableStateOf(false)
    var inboxError by mutableStateOf<String?>(null)
    private var inboxFor: List<String>? = null

    val loading: Boolean get() = feedLoading || requestedFeed == null && directoryLoading

    fun save(change: (PodioSaved) -> PodioSaved) { scope.launch {
        try { withContext(Dispatchers.IO) { library.update(change) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Couldn't save your Podio library. Check available storage and retry." }
    } }

    fun toggleFavourite(station: RadioStation) = save { old ->
        old.copy(stations = if (old.stations.any { it.id == station.id }) old.stations.filterNot { it.id == station.id } else old.stations + station)
    }

    fun toggleFollow(show: PodcastShow) = save { old ->
        old.copy(shows = if (old.shows.any { it.id == show.id }) old.shows.filterNot { it.id == show.id } else old.shows + show)
    }

    fun setInboxEpisodeLimit(limit: Int) = save { it.copy(inboxEpisodesPerShow = limit.coerceIn(1, 24)) }

    fun setShowSkipSilence(shows: Set<String>, enabled: Boolean?) = save { old ->
        old.copy(skipSilenceByShow = if (enabled == null) old.skipSilenceByShow - shows
            else old.skipSilenceByShow + shows.associateWith { enabled })
    }

    fun playEpisode(entry: InboxEpisode) = play(entry.item().copy(skipSilence = library.state.value.skipSilenceByShow[entry.show.id]))

    fun play(item: PodioItem) {
        val resume = if (item.live) 0 else library.resumePosition(item.id, item.durationSeconds * 1000)
        lastPlayed = item
        host.onPlay(item, resume)
    }

    fun playStation(station: RadioStation) { selected = station; play(station.item()); scope.launch { catalogue.stationPlayed(station.id) } }

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

    fun exportOpml() {
        val shows = library.state.value.shows
        val text = PodioOpml.encode(shows)
        val export = host.onExportOpmlFile
        if (export == null) { host.copyText(text); notice = "Copied ${shows.size} podcast subscriptions as OPML."; return }
        scope.launch {
            pickingFile = true
            try { if (export(text)) notice = "Saved podcast subscriptions as OPML." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't save the OPML file. Please retry." }
            finally { pickingFile = false }
        }
    }

    fun shareOpml() {
        val share = host.onShareOpmlFile ?: return
        scope.launch {
            try { share(PodioOpml.encode(library.state.value.shows)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't share the OPML file. Please retry." }
        }
    }

    /**
     * The newest episodes of the followed shows (up to 40 shows, four feeds at a time), newest first. A show
     * that fails is left out; only when every one fails is it an error. The same follows are not fetched twice unless [force].
     */
    suspend fun loadInbox(follows: List<PodcastShow>, force: Boolean, episodeLimit: Int = 12) {
        val limit = episodeLimit.coerceIn(1, 24)
        val key = listOf(limit.toString()) + follows.map { it.id }
        if (!force && key == inboxFor && inboxError == null) return
        if (follows.isEmpty()) { inbox = emptyList(); inboxFor = key; inboxError = null; return }
        // The episodes these follows showed last time come back at once (from memory, or the disk after a restart); they are
        // fetched again only when older than the freshness window, or on Refresh.
        val cacheKey = "podio:inbox:" + follows.joinToString(",") { it.id }.hashCode() + if (limit == 12) "" else ":$limit"
        val entry = pages.entry(cacheKey, FEED_FRESH_MS, INBOX_CODEC) { fetchInbox(follows, limit) }
        entry.restore()
        entry.state.value.data?.let { inbox = it }
        if (!force && entry.isFresh) { inboxFor = key; inboxError = null; return }
        inboxLoading = true; inboxError = null
        try {
            entry.fetchNow()
            val state = entry.state.value
            if (state.error != null) inboxError = "Couldn't refresh your shows. Check your connection and try again."
            else { inbox = state.data.orEmpty(); inboxFor = key }
        } finally { inboxLoading = false }
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

    var downloadToRemove by mutableStateOf<PodioItem?>(null)

    fun removeSelectedDownload() {
        val item = downloadToRemove ?: return
        val remove = host.onRemoveDownload ?: return
        downloadToRemove = null
        scope.launch {
            try {
                if (!remove(item)) error = "Couldn't remove the downloaded episode."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Couldn't remove the downloaded episode."
            }
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
