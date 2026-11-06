package com.cosmotune.watch

import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.Container
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.MyListEntry
import com.cosmotune.watch.model.Person
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Season
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProgress
import com.cosmotune.watch.model.WatchProvider
import com.cosmotune.watch.plugin.AddSourceResult
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginInfo
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.UserSourceSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * An in-memory [WatchRepository] for building the screens and for previews: the titles, channels and plugins of the Watch
 * board, no network. [now] is the clock the guide is laid out around; [latencyMs] delays each answer so loading states show.
 */
class FakeWatchRepository(
    private val now: () -> Long = { 1_762_461_600_000L }, // Fri 06 Nov 2026, 20:40 UTC
    private val latencyMs: Long = 0,
) : WatchRepository {

    private val films = listOf(
        film(1, "The Long Night Shift", 2019, 118, 7.1, "A night nurse and a stranded pilot have until sunrise to find a missing child.", "Thriller", "FREE · PLUTO"),
        film(2, "Ember Road", 2021, 112, 7.4, "Two sisters drive across a burning valley to reach their father before the roads close.", "Drama"),
        film(3, "Paper Lanterns", 2017, 96, 6.8, "A shy calligrapher writes letters for a whole village.", "Romance"),
        film(4, "The Cartographer", 2015, 131, 7.9, "A mapmaker charts an island that keeps moving.", "Adventure"),
        film(5, "Nosferatu", 1922, 94, 7.8, "The silent classic, in the public domain.", "Horror", "FREE · ARCHIVE"),
        film(6, "The General", 1926, 78, 8.1, "Buster Keaton and a stolen locomotive.", "Comedy", "FREE · ARCHIVE"),
        film(7, "Monsoon Express", 2023, 141, 7.2, "A Chennai-to-Kolkata train, eleven strangers, one storm.", "Drama"),
        film(8, "Quiet Orbit", 2020, 104, 6.9, "An astronaut on the far side of the Moon loses the radio.", "Science Fiction"),
    )
    private val series = listOf(
        show(101, "Harbour Town", 2019, 8.1, "A fishing town, a missing trawler and three families who know more than they say.", 3),
        show(102, "The Green Line", 2022, 7.6, "Night shifts on the last tram of a divided city.", 2),
        show(103, "Kitchen Republic", 2024, 7.0, "Six chefs, one food truck, every state of India.", 1),
    )
    private val titles: Map<WatchKey, TitleSummary> = (films + series).associateBy { it.key }

    private val pluginsState = MutableStateFlow(BuiltInPlugins.all.map { BuiltInPlugins.info(it) } + listOf(
        PluginInfo("jellyfin:home", PluginKind.JELLYFIN, "My Jellyfin (home)", "Films, series · signed in", "JF", builtIn = false,
            enabled = true, defaultEnabled = true, capabilities = setOf(Capability.CATALOG, Capability.STREAMS, Capability.SEARCH), status = "signed in"),
        PluginInfo("xtream:family", PluginKind.XTREAM, "Family playlist", "Xtream · 340 channels · guide", "M3U", builtIn = false,
            enabled = true, defaultEnabled = true, capabilities = setOf(Capability.LIVE, Capability.EPG), status = "340 channels"),
    ))
    override val plugins: StateFlow<List<PluginInfo>> = pluginsState.asStateFlow()

    private val myListState = MutableStateFlow(listOf(MyListEntry(films[3].key, now() - 86_400_000, films[3])))
    override val myList: StateFlow<List<MyListEntry>> = myListState.asStateFlow()

    private val progressState = MutableStateFlow(
        mapOf(
            episodeKey(101, 2, 4) to WatchProgress(episodeKey(101, 2, 4), 25 * 60_000L, 49 * 60_000L, now() - 3_600_000, series[0], "Low Water"),
            films[1].key to WatchProgress(films[1].key, 40 * 60_000L + 12_000, 112 * 60_000L, now() - 7_200_000, films[1]),
            episodeKey(102, 1, 9) to WatchProgress(episodeKey(102, 1, 9), 40 * 60_000L, 46 * 60_000L, now() - 86_400_000, series[1], "Terminus"),
        ),
    )
    private val continueState = MutableStateFlow(continueItems())
    override val continueWatching: StateFlow<List<ContinueItem>> = continueState.asStateFlow()

    private val favourites = MutableStateFlow(setOf(WatchKey.channel("iptv", "News24.in")))
    override val favouriteChannels: StateFlow<Set<WatchKey>> = favourites.asStateFlow()
    override val metadataSource: StateFlow<MetadataSource> = MutableStateFlow(MetadataSource.TMDB)

    private val channelList = listOf(
        channel("iptv", "News24.in", "News 24", 1, "News", "in", "eng"),
        channel("pluto", "cineclassics", "Cine Classics", 2, "Movies", "us", "eng"),
        channel("iptv", "TamilMusic.in", "Tamil Music", 3, "Music", "in", "tam"),
        channel("samsung", "toontime", "Toon Time", 4, "Kids", "in", "eng"),
        channel("plex", "sportone", "Sport One", 5, "Sports", "in", "eng"),
        channel("iptv", "NatureDocs.uk", "Nature Docs", 6, "Documentary", "uk", "eng"),
        channel("iptv", "Sun.in", "Sun News", 7, "News", "in", "tam"),
    )

    private suspend fun wait() { if (latencyMs > 0) delay(latencyMs) }

    override suspend fun home(filter: HomeFilter): WatchHome {
        wait()
        val rows = buildList {
            if (filter != HomeFilter.SERIES && filter != HomeFilter.LIVE) {
                add(HomeRow("pluto:films", "Free films", films.take(4), "Pluto TV and Plex", pluginId = BuiltInPlugins.PLUTO))
                add(HomeRow("ia:classics", "Classic and public-domain", films.filter { it.badge == "FREE · ARCHIVE" }, "Internet Archive", pluginId = BuiltInPlugins.ARCHIVE))
                add(HomeRow("tmdb:trending:film", "Trending films", films.shuffledStable()))
            }
            if (filter != HomeFilter.FILMS && filter != HomeFilter.LIVE) add(HomeRow("tmdb:trending:series", "Series", series))
            if (filter == HomeFilter.ALL) add(HomeRow("tmdb:genre:drama", "Drama", films.filter { "Drama" in it.genres }))
        }
        val live = if (filter == HomeFilter.FILMS || filter == HomeFilter.SERIES) emptyList() else liveNow(6)
        return WatchHome(
            featured = if (filter == HomeFilter.LIVE) null else Featured(films[0], listOf("HD", "CC", "FREE · PLUTO")),
            continueWatching = continueState.value.filter {
                when (filter) { HomeFilter.FILMS -> it.summary.type == TitleType.FILM; HomeFilter.SERIES -> it.summary.type == TitleType.SERIES; else -> true }
            },
            liveNow = live,
            rows = rows,
        )
    }

    override suspend fun rowPage(rowId: String, skip: Int): List<TitleSummary> { wait(); return emptyList() }

    override suspend fun liveNow(limit: Int): List<LiveNowItem> = channelList.take(limit).map { nowNext(it) }

    override suspend fun title(key: WatchKey): Title? {
        wait()
        val summary = titles[key] ?: return null
        val cast = listOf(Person("Asha Varma", "Meera"), Person("Lin Okafor", "Tara"), Person("R. Menon", "Father"), Person("K. Doyle", "Ranger"))
        val more = (if (summary.type == TitleType.FILM) films else series).filter { it.key != key }.take(6)
        val seasons = if (summary.type == TitleType.SERIES) (1..(summary.runtimeMin ?: 1)).map { Season(key, it, "Season $it", 8) } else emptyList()
        return Title(summary.copy(runtimeMin = if (summary.type == TitleType.SERIES) 48 else summary.runtimeMin), certification = "12",
            seasons = seasons, cast = cast, moreLikeThis = more, firstYear = summary.year, lastYear = summary.year?.plus(seasons.size),
            status = if (summary.type == TitleType.SERIES) "Returning Series" else "Released",
            whereToWatch = listOf(WatchProvider("Netflix", type = "flatrate")))
    }

    override suspend fun episodes(series: WatchKey, season: Int): List<Episode> {
        wait()
        val names = listOf("The Tide Comes In", "Salt and Iron", "Fog Bank", "Low Water", "The Lighthouse Keeper", "Nets", "High Water", "Homecoming")
        return names.mapIndexed { i, name ->
            Episode(episodeKey(series.id.toInt(), season, i + 1), series, season, i + 1, name, "Episode ${i + 1} of season $season.",
                airDate = "20${19 + season}-0${(i % 9) + 1}-01", runtimeMin = 46 + i % 5)
        }
    }

    override suspend fun nextUp(series: WatchKey): NextUp? {
        val inProgress = progressState.value.values.filter { it.key.seriesKey == series && !it.completed }.maxByOrNull { it.updatedAtMs }
        if (inProgress != null) {
            val ep = episodes(series, inProgress.key.season ?: 1).first { it.key == inProgress.key }
            return NextUp(ep, inProgress, resume = true)
        }
        return episodes(series, 1).firstOrNull()?.let { NextUp(it, null, resume = false) }
    }

    override fun targetOf(summary: TitleSummary): PlayTarget = PlayTarget.Film(summary.key, summary.ids, summary.name, summary.year, summary.runtimeMin)
    override fun targetOf(series: Title, episode: Episode): PlayTarget =
        PlayTarget.EpisodeOf(episode.key, series.summary.ids, series.summary.name, episode.season, episode.number, episode.ids, episode.name)

    override fun sources(target: PlayTarget, hint: StreamHint?): Flow<SourcesState> = flow {
        val all = fakeStreams(target)
        val pending = all.map { it.source.pluginId }.toMutableSet()
        emit(SourcesState(target, emptyList(), pending.toSet()))
        val got = mutableListOf<Stream>()
        for (stream in all) {
            wait(); got += stream; pending -= stream.source.pluginId
            emit(SourcesState(target, StreamRanker().rank(got, hint), pending.toSet(), whereToWatch = listOf(WatchProvider("Netflix"))))
        }
        if (all.isEmpty()) emit(SourcesState(target, emptyList(), emptySet()))
    }

    override suspend fun sourcesNow(target: PlayTarget, hint: StreamHint?): SourcesState {
        wait(); return SourcesState(target, StreamRanker().rank(fakeStreams(target), hint), emptySet())
    }

    private fun fakeStreams(target: PlayTarget): List<Stream> {
        val sample = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        return when (target) {
            is PlayTarget.Live -> listOf(Stream(StreamSource(target.channel.pluginId, target.channel.pluginId, target.channel.pluginId.uppercase()),
                "Live", url = sample, protocol = StreamProtocol.HLS, isLive = true))
            else -> listOf(
                Stream(StreamSource("plex", "Plex", "PLEX"), "Free with ads", "1080p · English, Hindi audio · 12 subtitles", sample,
                    StreamProtocol.HLS, height = 1080, audioLanguages = listOf("eng", "hin"), hasAds = true),
                Stream(StreamSource("jellyfin:home", "My Jellyfin (home)", "JF"), "Direct play · HEVC", "4K HDR · 58 GB · MKV",
                    "https://example.invalid/Videos/1/stream?static=true", StreamProtocol.PROGRESSIVE, Container.MKV, height = 2160,
                    hdr = "HDR10", videoCodec = "hevc", sizeBytes = 58L shl 30, filename = "Ember.Road.2021.2160p.mkv"),
                Stream(StreamSource("stremio:addon", "An add-on you installed", "ADD"), "720p", "MP4",
                    "https://example.invalid/ember-road-720p.mp4", StreamProtocol.PROGRESSIVE, Container.MP4, height = 720),
            )
        }
    }

    override suspend fun subtitles(target: PlayTarget, stream: Stream?, languages: List<String>): List<Subtitle> = listOf(
        Subtitle("os:en", "https://example.invalid/en.srt", "eng", "English", "srt", SubtitleOrigin.OPENSUBTITLES),
        Subtitle("os:ta", "https://example.invalid/ta.srt", "tam", "Tamil", "srt", SubtitleOrigin.OPENSUBTITLES),
    )

    override suspend fun channels(filter: ChannelFilter): List<Channel> {
        wait()
        return channelList.filter { c ->
            (filter.country == null || filter.country in c.countries) && (filter.language == null || filter.language in c.languages) &&
                (filter.category == null || c.categories.any { it.equals(filter.category, true) }) &&
                (!filter.favouritesOnly || c.key in favourites.value) && (filter.pluginId == null || c.pluginId == filter.pluginId) &&
                (filter.query.isNullOrBlank() || c.name.contains(filter.query, true))
        }
    }

    override suspend fun channel(key: WatchKey): Channel? = channelList.firstOrNull { it.key == key }

    override suspend fun channelFacets(): ChannelFacets {
        fun facets(values: List<String>, name: (String) -> String) = values.groupingBy { it }.eachCount().map { (id, n) -> Facet(id, name(id), n) }.sortedByDescending { it.count }
        return ChannelFacets(
            facets(channelList.flatMap { it.countries }) { mapOf("in" to "India", "uk" to "United Kingdom", "us" to "United States")[it] ?: it },
            facets(channelList.flatMap { it.languages }) { mapOf("eng" to "English", "tam" to "Tamil")[it] ?: it },
            facets(channelList.flatMap { it.categories }) { it },
            total = channelList.size,
        )
    }

    override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): GuideWindow =
        GuideWindow(fromMs, toMs, channels.map { GuideRow(it, programmes(it).filter { p -> p.stopMs > fromMs && p.startMs < toMs }) })

    override suspend fun nowNext(channel: Channel): LiveNowItem {
        val list = programmes(channel)
        val t = now()
        val index = list.indexOfFirst { it.isOnAt(t) }
        return LiveNowItem(channel, list.getOrNull(index), list.getOrNull(index + 1))
    }

    private fun programmes(channel: Channel): List<EpgProgramme> {
        val epg = channel.epgId ?: return emptyList()
        val slot = 30 * 60_000L
        val start = now() / slot * slot - 2 * slot
        val names = listOf("Evening bulletin", "World tonight", "Market close", "Late edition", "Overnight", "Morning", "Headlines", "Studio")
        return (0 until 12).map { i ->
            val s = start + i * slot
            EpgProgramme(epg, s, s + slot, "${channel.name}: ${names[(i + channel.number!!) % names.size]}", description = "On ${channel.name}.")
        }
    }

    override suspend fun setFavouriteChannel(key: WatchKey, favourite: Boolean) {
        favourites.value = if (favourite) favourites.value + key else favourites.value - key
    }

    override suspend fun search(query: String): SearchResults {
        wait()
        val q = query.trim()
        if (q.isEmpty()) return SearchResults(query, emptyList(), emptyList(), emptyList())
        return SearchResults(query, films.filter { it.name.contains(q, true) }, series.filter { it.name.contains(q, true) },
            channelList.filter { it.name.contains(q, true) })
    }

    override suspend fun setPluginEnabled(id: String, enabled: Boolean) {
        pluginsState.value = pluginsState.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun addUserSource(spec: UserSourceSpec): AddSourceResult {
        wait()
        val (kind, badge) = when (spec) {
            is UserSourceSpec.StremioAddon -> PluginKind.STREMIO_ADDON to "ADD"
            is UserSourceSpec.Jellyfin -> PluginKind.JELLYFIN to "JF"
            is UserSourceSpec.Emby -> PluginKind.EMBY to "EMBY"
            is UserSourceSpec.Plex -> PluginKind.PLEX_SERVER to "PLEX"
            is UserSourceSpec.M3u -> PluginKind.M3U to "M3U"
            is UserSourceSpec.Xtream -> PluginKind.XTREAM to "M3U"
            is UserSourceSpec.LocalFolder -> PluginKind.LOCAL_FOLDER to "DIR"
        }
        val info = PluginInfo("${kind.name.lowercase()}:${pluginsState.value.size}", kind, spec.label.ifBlank { kind.name.lowercase() }, "Added",
            badge, builtIn = false, enabled = true, defaultEnabled = true, capabilities = setOf(Capability.STREAMS))
        pluginsState.value = pluginsState.value + info
        return AddSourceResult.Added(info)
    }

    override suspend fun removeUserSource(id: String) { pluginsState.value = pluginsState.value.filterNot { it.id == id && !it.builtIn } }

    override suspend fun previewInstallLink(link: String): InstallPreview {
        wait()
        val url = link.trim().replace(Regex("^stremio://"), "https://")
        if (!url.endsWith("manifest.json")) return InstallPreview(url, "Unknown", null, problem = "This is not an add-on link")
        return InstallPreview(url, "Sample add-on", "An add-on you are about to install.", offers = listOf("Films", "Series"),
            alreadyInstalled = pluginsState.value.any { it.description == url })
    }

    override suspend fun installFromLink(link: String): AddSourceResult = addUserSource(UserSourceSpec.StremioAddon(link, "Sample add-on"))

    override suspend fun exportPluginPack(): String = "cosmotune://watch/pack?d=fake"

    override suspend fun readPluginPack(payload: String): List<PluginPackItem> =
        listOf(PluginPackItem("https://example.invalid/addon/manifest.json", "Sample add-on"))

    override suspend fun importPluginPack(payload: String, only: Set<String>?): ImportPackResult {
        val added = readPluginPack(payload).filter { only == null || it.manifestUrl in only }
            .mapNotNull { (installFromLink(it.manifestUrl) as? AddSourceResult.Added)?.plugin }
        return ImportPackResult(added, 0, emptyMap())
    }

    override suspend fun setInMyList(summary: TitleSummary, inList: Boolean) {
        val rest = myListState.value.filterNot { it.key == summary.key }
        myListState.value = if (inList) listOf(MyListEntry(summary.key, now(), summary)) + rest else rest
    }

    override suspend fun progress(key: WatchKey): WatchProgress? = progressState.value[key]
    override suspend fun saveProgress(progress: WatchProgress) { progressState.value = progressState.value + (progress.key to progress); continueState.value = continueItems() }
    override suspend fun clearProgress(key: WatchKey) { progressState.value = progressState.value - key; continueState.value = continueItems() }

    override suspend fun nextEpisode(episode: WatchKey): Episode? {
        val series = episode.seriesKey ?: return null
        val season = episode.season ?: return null
        val list = episodes(series, season)
        val i = list.indexOfFirst { it.key == episode }
        return list.getOrNull(i + 1) ?: episodes(series, season + 1).takeIf { season < (titles[series]?.runtimeMin ?: 1) }?.firstOrNull()
    }

    private fun continueItems(): List<ContinueItem> = progressState.value.values.filter { !it.completed }.sortedByDescending { it.updatedAtMs }.map { p ->
        val summary = p.summary ?: titles[p.key.seriesKey ?: p.key]!!
        val ep = p.key.seriesKey?.let { s -> Episode(p.key, s, p.key.season!!, p.key.episode!!, p.episodeName ?: "Episode ${p.key.episode}") }
        ContinueItem(p, summary, ep)
    }

    private fun List<TitleSummary>.shuffledStable() = sortedBy { (it.name.hashCode() * 31) % 97 }

    private companion object {
        fun film(id: Int, name: String, year: Int, runtime: Int, rating: Double, overview: String, genre: String, badge: String? = null) = TitleSummary(
            WatchKey.tmdbFilm(id), TitleType.FILM, name, year, posterUrl = null, rating = rating, overview = overview, genres = listOf(genre),
            runtimeMin = runtime, ids = ExternalIds(tmdb = id, imdb = "tt${1_000_000 + id}"), badge = badge)

        /** For a fake series, [seasons] is carried in runtimeMin until the page builds its seasons. */
        fun show(id: Int, name: String, year: Int, rating: Double, overview: String, seasons: Int) = TitleSummary(
            WatchKey.tmdbSeries(id), TitleType.SERIES, name, year, rating = rating, overview = overview, genres = listOf("Drama"),
            runtimeMin = seasons, ids = ExternalIds(tmdb = id))

        fun episodeKey(series: Int, season: Int, episode: Int) = WatchKey.tmdbEpisode(series, season, episode)

        fun channel(plugin: String, id: String, name: String, number: Int, category: String, country: String, language: String) = Channel(
            WatchKey.channel(plugin, id), plugin, name, number, categories = listOf(category), countries = listOf(country),
            languages = listOf(language), epgId = id, tvgId = id)
    }
}
