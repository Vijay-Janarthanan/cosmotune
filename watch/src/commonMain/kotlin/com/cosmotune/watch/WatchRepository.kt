package com.cosmotune.watch

import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.MyListEntry
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProgress
import com.cosmotune.watch.model.WatchProvider
import com.cosmotune.watch.plugin.AddSourceResult
import com.cosmotune.watch.plugin.PluginInfo
import com.cosmotune.watch.plugin.UserSourceSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

/** The chips at the top of Watch home. */
enum class HomeFilter { ALL, FILMS, SERIES, LIVE }

/** Where titles and pages come from now: TMDB with a key, otherwise Cinemeta and the plugins' own metadata. */
enum class MetadataSource { TMDB, FALLBACK }

/** One horizontal row of Watch home ("Free films", "Trending this week", "Drama"). */
@Serializable
data class HomeRow(
    val id: String,
    val title: String,
    val items: List<TitleSummary>,
    val subtitle: String? = null,
    /** The plugin the row comes from, or null for TMDB rows. */
    val pluginId: String? = null,
    /** Posters (false) or wide stills (true). */
    val wide: Boolean = false,
    /** More can be loaded with [WatchRepository.rowPage]. */
    val pageable: Boolean = true,
)

/** A Continue watching card: "Harbour Town · S2 E4 · 24 min left". */
@Serializable
data class ContinueItem(
    val progress: WatchProgress,
    val summary: TitleSummary,
    /** The episode to resume, for a series. */
    val episode: Episode? = null,
) {
    val key: WatchKey get() = progress.key
    /** "S2 E4" for an episode, null for a film. */
    val episodeLabel: String? get() = episode?.let { "S${it.season} E${it.number}" }
    val remainingMin: Int get() = ((progress.remainingMs + 59_999) / 60_000).toInt()
}

/** A channel with what is on now and next (Live now row, channel list). */
@Serializable
data class LiveNowItem(val channel: Channel, val now: EpgProgramme? = null, val next: EpgProgramme? = null)

/** The featured card of Watch home. */
@Serializable
data class Featured(val summary: TitleSummary, val badges: List<String> = emptyList(), val progress: WatchProgress? = null)

@Serializable
data class WatchHome(
    val featured: Featured?,
    val continueWatching: List<ContinueItem>,
    val liveNow: List<LiveNowItem>,
    val rows: List<HomeRow>,
    /** Shown on first run when nothing could load (every plugin off or offline). */
    val empty: Boolean = false,
)

/** "Next up" on a series page: the episode in progress, else the first unwatched one. */
data class NextUp(val episode: Episode, val progress: WatchProgress?, val resume: Boolean) {
    val label: String get() = if (resume) "Resume S${episode.season} E${episode.number}" else "Play S${episode.season} E${episode.number}"
}

/**
 * The "Play from" list as it fills in: plugins answer one by one. [streams] is ranked best first and the first is what
 * Play uses. [pending] are plugins still answering; [errors] plugin id to message ("Add-on not responding").
 */
data class SourcesState(
    val target: PlayTarget,
    val streams: List<Stream>,
    val pending: Set<String>,
    val errors: Map<String, String> = emptyMap(),
    /** Where the title streams elsewhere (TMDB providers, WatchHub). Not playable here. */
    val whereToWatch: List<WatchProvider> = emptyList(),
) {
    val best: Stream? get() = streams.firstOrNull { it.isPlayable }
    val done: Boolean get() = pending.isEmpty()
    /** Nothing playable once every plugin answered: the page shows "Not on your sources". */
    val notFound: Boolean get() = done && best == null
}

/** A chip of Live TV; [yours] marks the viewer's own country or language (listed first). */
@Serializable
data class Facet(val id: String, val name: String, val count: Int, val yours: Boolean = false)

/** The chips of Live TV. Countries are ISO codes ("in"), languages ISO 639-3 ("tam"), categories as sources name them. */
@Serializable
data class ChannelFacets(val countries: List<Facet>, val languages: List<Facet>, val categories: List<Facet>, val total: Int)

data class ChannelFilter(
    val country: String? = null,
    val language: String? = null,
    val category: String? = null,
    val favouritesOnly: Boolean = false,
    val query: String? = null,
    /** Only channels of one plugin (a user's playlist). */
    val pluginId: String? = null,
)

@Serializable
data class GuideRow(val channel: Channel, val programmes: List<EpgProgramme>)

/** The guide grid between [fromMs] and [toMs]: channels down, time across. A channel with no guide has no programmes. */
@Serializable
data class GuideWindow(val fromMs: Long, val toMs: Long, val rows: List<GuideRow>)

data class SearchResults(
    val query: String,
    val films: List<TitleSummary>,
    val series: List<TitleSummary>,
    val channels: List<Channel>,
) {
    val isEmpty: Boolean get() = films.isEmpty() && series.isEmpty() && channels.isEmpty()
}

/** The Install sheet of a one-tap install link. [manifestUrl] is normalised (stremio:// becomes https://). */
data class InstallPreview(
    val manifestUrl: String,
    val name: String,
    val description: String?,
    val logoUrl: String? = null,
    val version: String? = null,
    /** "Films", "Series", "Live TV", "Subtitles", "Catalogues". */
    val offers: List<String> = emptyList(),
    val alreadyInstalled: Boolean = false,
    /** Set when the add-on cannot be installed (unreachable, not an add-on, needs configuring first, or peer-to-peer). */
    val problem: String? = null,
) {
    val installable: Boolean get() = problem == null && !alreadyInstalled
}

/** One add-on in a shared pack. */
data class PluginPackItem(val manifestUrl: String, val name: String, val alreadyInstalled: Boolean = false)

data class ImportPackResult(val added: List<PluginInfo>, val skipped: Int, val failed: Map<String, String>)

/** An entry of "Find plugins". */
data class DirectoryLink(val title: String, val description: String, val url: String) {
    companion object {
        val ALL = listOf(
            DirectoryLink("Stremio community add-ons", "A directory of add-ons people have made; check each before installing", "https://stremio-addons.net"),
            DirectoryLink("Nuvio project", "Docs on Nuvio-protocol plugins", "https://github.com/tapframe/NuvioStreaming"),
            DirectoryLink("iptv-org playlists", "Free public channel playlists by country, language and category", "https://github.com/iptv-org/iptv"),
            DirectoryLink("Free-TV/IPTV", "Free channels by country", "https://github.com/Free-TV/IPTV"),
            DirectoryLink("Jellyfin", "Your own media server, free and self-hosted", "https://jellyfin.org/downloads/server"),
        )
    }
}

/**
 * Everything Watch's screens ask for. One instance per app; every call is safe from the main thread (work moves to IO
 * inside). Plugins that are off are never asked. A plugin that fails is left out of the answer, never fails the call.
 * See WATCH_INTERFACES.md at the module root.
 */
class WatchPlaybackLease(val url: String, val close: () -> Unit) {
    var resolvedBy: String? = null
}

interface WatchRepository {
    suspend fun scriptRepository(url: String): List<WatchScriptEntry> = emptyList()
    /**
     * Downloads an installed Watch script's current source and compares it with the pinned one,
     * without changing anything. Null for anything that is not an installed script.
     */
    suspend fun checkScriptUpdate(id: String): WatchScriptUpdate? = null
    /**
     * Installs the version [checkScriptUpdate] downloaded, and only if its fingerprint is still
     * [sha256]: what is installed is exactly what was offered, never a later upstream change.
     */
    suspend fun applyScriptUpdate(id: String, sha256: String): Boolean = false
    val playlists: StateFlow<List<com.cosmotune.watch.model.WatchPlaylist>> get() = DEFAULT_WATCH_PLAYLISTS
    suspend fun createPlaylist(name: String): String? = null
    suspend fun renamePlaylist(id: String, name: String) {}
    suspend fun deletePlaylist(id: String) {}
    suspend fun setPlaylistItem(id: String, title: TitleSummary, included: Boolean) {}
    suspend fun setAnimeMapping(series: WatchKey, season: Int, malId: Int?) {}
    suspend fun skipIntervals(target: PlayTarget, durationMs: Long): List<com.cosmotune.watch.model.WatchSkip> = emptyList()
    val configuredServices: StateFlow<Set<String>> get() = DEFAULT_WATCH_SERVICES
    suspend fun setServiceKey(service: String, key: String) {}
    val tracking: WatchTracking? get() = null
    val profiles: StateFlow<List<com.cosmotune.watch.model.WatchProfile>> get() = DEFAULT_WATCH_PROFILES
    val activeProfile: StateFlow<String> get() = DEFAULT_WATCH_PROFILE
    val profileUnlocked: StateFlow<Boolean> get() = DEFAULT_WATCH_UNLOCKED
    suspend fun addProfile(name: String): String? = null
    suspend fun selectProfile(id: String, pin: String? = null): Boolean = false
    suspend fun setProfilePin(pin: String?) {}
    suspend fun removeProfile(id: String) {}
    val preferences: StateFlow<WatchPreferences> get() = DEFAULT_WATCH_PREFERENCES
    /** Whether Home's first-use guide was put away on this device. A repository without one never shows it. */
    val setupGuideDismissed: StateFlow<Boolean> get() = DEFAULT_WATCH_UNLOCKED
    suspend fun dismissSetupGuide() {}
    suspend fun setPreferences(preferences: WatchPreferences) {}
    /** Opens a playback-only address and owns any resources it needs until the player closes it. */
    suspend fun openPlayback(stream: Stream): WatchPlaybackLease = WatchPlaybackLease(stream.url) {}

    /** Every plugin, built in first (in the Plugins page order), then the user's own. */
    val plugins: StateFlow<List<PluginInfo>>
    val myList: StateFlow<List<MyListEntry>>
    /** Newest first; completed items drop out (a finished episode is replaced by the next one). */
    val continueWatching: StateFlow<List<ContinueItem>>
    val favouriteChannels: StateFlow<Set<WatchKey>>
    val metadataSource: StateFlow<MetadataSource>

    // Home and browsing
    suspend fun home(filter: HomeFilter = HomeFilter.ALL): WatchHome
    /** The next page of a [HomeRow] (infinite scroll), [skip] items in. */
    suspend fun rowPage(rowId: String, skip: Int): List<TitleSummary>
    suspend fun liveNow(limit: Int = 12): List<LiveNowItem>

    // Pages
    /** The film or series page; null when nothing knows the key. */
    suspend fun title(key: WatchKey): Title?
    suspend fun episodes(series: WatchKey, season: Int): List<Episode>
    suspend fun nextUp(series: WatchKey): NextUp?
    /** The target Play uses for a card, a page or an episode row. */
    fun targetOf(summary: TitleSummary): PlayTarget
    fun targetOf(series: Title, episode: Episode): PlayTarget
    fun targetOf(channel: Channel): PlayTarget = PlayTarget.Live(channel)

    // Sources and subtitles
    /**
     * The copies of [target] the enabled plugins can play, best first, filling in as plugins answer. [hint] (the last
     * release played, or the room's) ranks the matching copy first.
     */
    fun sources(target: PlayTarget, hint: StreamHint? = null): Flow<SourcesState>
    /** The whole list once every plugin answered (or timed out). */
    suspend fun sourcesNow(target: PlayTarget, hint: StreamHint? = null): SourcesState
    /** External subtitles (OpenSubtitles and other subtitle plugins), [languages] first. */
    suspend fun subtitles(target: PlayTarget, stream: Stream?, languages: List<String> = emptyList()): List<Subtitle>

    // Live TV
    suspend fun channels(filter: ChannelFilter = ChannelFilter()): List<Channel>
    suspend fun channel(key: WatchKey): Channel?
    suspend fun channelFacets(): ChannelFacets
    suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): GuideWindow
    suspend fun nowNext(channel: Channel): LiveNowItem
    suspend fun setFavouriteChannel(key: WatchKey, favourite: Boolean)

    // Live now: matches and live specials
    /**
     * The "Live now" strip's events: cricket and football matches in play, about to start (the next 24 hours) or just
     * finished (started in the last 16 hours), and big live shows on DD or an official YouTube channel. Emits what is cached at once,
     * then again after each poll for as long as it is collected (about every minute while something is live, every ten
     * minutes otherwise). Ranked: live first, then upcoming by start, then finished; within each, the viewer's country and
     * language first (`WatchPlace`). Each event's [LiveEvent.watchOptions] are free streams our plugins can play; a source
     * that is not set up or does not answer is left out, never fails the flow.
     */
    fun liveEvents(): Flow<List<LiveEvent>> = flowOf(emptyList())

    // Search
    suspend fun search(query: String): SearchResults

    // Plugins
    suspend fun setPluginEnabled(id: String, enabled: Boolean)
    /** Checks the source (manifest, sign-in, playlist) before adding it; nothing is stored when it fails. */
    suspend fun addUserSource(spec: UserSourceSpec): AddSourceResult
    suspend fun removeUserSource(id: String)
    /** Moves a user source up or down the Plugins page; the order is the order its streams rank in. */
    suspend fun moveUserSource(id: String, toIndex: Int) {}

    /**
     * Reads an install link (`stremio://host/path/manifest.json`, an https manifest URL, or a `cosmotune://watch/addon?url=`
     * deep link) without installing it: the add-on's name, description and what it offers, for the Install sheet.
     */
    suspend fun previewInstallLink(link: String): InstallPreview
    /** Installs the add-on of [link] (after [previewInstallLink]); the same checks as [addUserSource]. */
    suspend fun installFromLink(link: String): AddSourceResult
    /**
     * The user's add-ons as a short shareable payload (a `cosmotune://watch/pack?d=...` link, also fit for a QR code):
     * manifest URLs and names only, never servers, playlists with passwords or any credential.
     */
    suspend fun exportPluginPack(): String
    /** What a pack link holds, before importing it (the import sheet lists them with switches). */
    suspend fun readPluginPack(payload: String): List<PluginPackItem>
    /** Installs the chosen items of a pack (all when [only] is null); items already installed are skipped. */
    suspend fun importPluginPack(payload: String, only: Set<String>? = null): ImportPackResult
    /** "Find plugins": directories to browse in the web browser. Links only; nothing is fetched from them. */
    val pluginDirectories: List<DirectoryLink> get() = DirectoryLink.ALL

    // My list and progress
    suspend fun setInMyList(summary: TitleSummary, inList: Boolean)
    fun isInMyList(key: WatchKey): Boolean = myList.value.any { it.key == key }
    suspend fun progress(key: WatchKey): WatchProgress?
    /** Called by the player every few seconds and on stop. */
    suspend fun saveProgress(progress: WatchProgress)
    suspend fun clearProgress(key: WatchKey)
    /** The next episode after [episode], across seasons; null at the end of the series. */
    suspend fun nextEpisode(episode: WatchKey): Episode?
}

private val DEFAULT_WATCH_PREFERENCES: StateFlow<WatchPreferences> = kotlinx.coroutines.flow.MutableStateFlow(WatchPreferences())
private val DEFAULT_WATCH_PROFILES: StateFlow<List<com.cosmotune.watch.model.WatchProfile>> = kotlinx.coroutines.flow.MutableStateFlow(listOf(com.cosmotune.watch.model.WatchProfile("default", "Everyone")))
private val DEFAULT_WATCH_PROFILE: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow("default")
private val DEFAULT_WATCH_SERVICES: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet())
private val DEFAULT_WATCH_UNLOCKED: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)
private val DEFAULT_WATCH_PLAYLISTS: StateFlow<List<com.cosmotune.watch.model.WatchPlaylist>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())

data class WatchScriptEntry(val id: String, val name: String, val url: String, val description: String = "")

/** An installed script's pinned fingerprint against the one its address serves now. */
data class WatchScriptUpdate(val id: String, val name: String, val url: String, val installedSha256: String, val availableSha256: String, val sizeBytes: Int) {
    val available: Boolean get() = installedSha256 != availableSha256
}
