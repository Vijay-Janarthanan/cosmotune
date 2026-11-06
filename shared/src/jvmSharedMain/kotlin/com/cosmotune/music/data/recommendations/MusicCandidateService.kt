package com.cosmotune.music.data.recommendations

import com.cosmotune.listening.ListeningNetwork
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.*
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Small metadata pools, not a bundled full catalogue. Source identities remain playable provider IDs. */
object MusicCandidateService {
    private data class Pool(val region: DiscoveryRegion, val rows: List<MusicCandidate>, val expires: Long)
    private val pools = LinkedHashMap<String, Pool>()
    private val lock = Any()
    @Volatile private var context = MusicDiscoveryContext()
    @Volatile private var region = DiscoveryRegion()
    @Volatile private var allowedProviders = emptySet<String>()
    @Volatile var providerSearch: (suspend (DiscoveryRegion) -> List<MusicCandidate>)? = null
    fun context(): MusicDiscoveryContext = context
    fun updateContext(value: MusicDiscoveryContext) {
        context = value.copy(library = value.library.asSequence().filter { !it.isVideo && !it.videoId.startsWith("podio:") }.distinctBy { it.videoId }.take(1_200).toList(),
            history = value.history.asSequence().filter { !it.isVideo && !it.videoId.startsWith("podio:") }.distinctBy { it.videoId }.take(80).toList(),
            likedIds = value.likedIds.take(5_000).toSet(), followedArtists = value.followedArtists.distinctBy { it.id }.take(20))
    }
    fun begin(value: DiscoveryRegion, allowedProviders: Set<String>) = synchronized(lock) {
        region = value
        this.allowedProviders = allowedProviders.toSet()
        pools.entries.removeAll { (_, pool) -> pool.region != value || pool.expires < System.currentTimeMillis() }
        pools.keys.filter { it.startsWith("provider:") && it.removePrefix("provider:") !in allowedProviders }.forEach(pools::remove)
    }
    fun ingest(key: String, value: DiscoveryRegion, rows: List<MusicCandidate>) = synchronized(lock) {
        if (value != region) return@synchronized
        pools.remove(key)
        pools[key] = Pool(value, rows.filter { it.song.videoId.isNotBlank() && !it.song.isVideo && !it.song.videoId.startsWith("podio:") }.take(120), System.currentTimeMillis() + 15 * 60_000)
        while (pools.size > 12) pools.remove(pools.keys.first())
    }
    fun snapshot(value: DiscoveryRegion = region): List<MusicCandidate> {
        val local = context
        val sources = buildList {
            add(local.library.mapIndexed { rank, song -> MusicCandidate(song, MusicCandidateOrigin.LIBRARY, rank, explanation = "In your library") })
            add(local.history.mapIndexed { rank, song -> MusicCandidate(song, MusicCandidateOrigin.RECENT, rank, explanation = "Based on your recent listening") })
            synchronized(lock) { pools.values.filter { it.region == value && it.expires >= System.currentTimeMillis() }.forEach { add(it.rows) } }
        }
        val result = ArrayList<MusicCandidate>()
        for (index in 0 until (sources.maxOfOrNull { it.size } ?: 0)) {
            for (source in sources) source.getOrNull(index)?.let(result::add)
            if (result.size >= 2_000) break
        }
        return result.filter { row ->
            val owner = row.provider ?: row.song.videoId.takeIf { it.startsWith("src:sflx-") && row.song.localPath == null && row.song.localUri == null }
                ?.removePrefix("src:sflx-")?.substringBefore("::")
            (owner == null || owner in allowedProviders) && (value.musicLanguage.isBlank() || row.language == null || row.language.equals(value.musicLanguage, ignoreCase = true))
        }.take(2_000)
    }

    /** Independent budgets; a provider failure never erases useful local/chart/other-provider candidates. */
    suspend fun refresh(value: DiscoveryRegion, onPool: suspend () -> Unit = {}): Set<String> = supervisorScope {
        val failures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val slots = Semaphore(2)
        suspend fun pool(key: String, fetch: suspend () -> List<MusicCandidate>) {
            try {
                val found = slots.withPermit { withTimeoutOrNull(6_000) { fetch() } }
                if (found == null) failures += key else if (found.isNotEmpty()) { ingest(key, value, found); onPool() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failures += key }
        }
        val work = ArrayList<Deferred<Unit>>()
        providerSearch?.let { source -> work += async { pool("catalogue:providers") { source(value) } } }
        if (value.musicLanguage.isNotBlank()) {
            val year = LocalDate.now().year
            listOf("${value.languageLabel} new releases $year", "${value.languageLabel} independent music").forEachIndexed { index, query ->
                work += async { pool("language:$index") {
                    YtMusicRepository.search(query, SearchFilter.SONGS).getOrThrow().mapNotNull { (it as? SearchResult.Track)?.song }.take(30)
                        .mapIndexed { rank, song -> MusicCandidate(song, MusicCandidateOrigin.LANGUAGE_CATALOGUE, rank,
                            explanation = "Catalogue search for ${value.languageLabel}; language and release date not supplied") }
                } }
            }
        }
        context.history.take(2).forEach { seed -> work += async { pool("radio:${seed.videoId}") {
            YtMusicRepository.radio(seed.videoId, seed).getOrThrow().take(40).mapIndexed { rank, song ->
                MusicCandidate(song, MusicCandidateOrigin.RADIO, rank, explanation = "Radio based on ${seed.title}")
            }
        } } }
        context.followedArtists.take(2).forEach { artist -> work += async { pool("artist:${artist.id}") {
            val catalogue = ListeningNetwork.releases(artist)
            val unseen = catalogue.filter { it.id !in artist.known }
            val releases = unseen.ifEmpty { catalogue }.take(2)
            releases.flatMap { release ->
                YtMusicRepository.browseSongs(release.id).getOrThrow().songs.take(20).mapIndexed { rank, song ->
                    MusicCandidate(song, MusicCandidateOrigin.FOLLOWED_ARTIST, rank,
                        explanation = (if (release.id !in artist.known) "New to your followed-artist watch: " else "From your followed artist: ") + "${artist.name}; release date not supplied")
                }
            }
        } } }
        work.awaitAll()
        failures.toSet()
    }

    suspend fun preview(kind: MusicPlaylistKind, seed: Song?, count: Int, includeNetwork: Boolean, value: DiscoveryRegion): MusicPlaylistPreview {
        val failed = if (includeNetwork && kind != MusicPlaylistKind.REDISCOVER) refresh(value) else emptySet()
        return withContext(Dispatchers.Default) {
            val current = context
            val candidates = snapshot(value).filter { includeNetwork || it.origin == MusicCandidateOrigin.LIBRARY || it.origin == MusicCandidateOrigin.RECENT }
            val chosenSeed = seed ?: current.history.firstOrNull() ?: current.library.firstOrNull()
            val songs = candidates.map { it.song }
            val learned = LocalRecommendations.localScores(chosenSeed, songs)
            val scores = songs.indices.associate { songs[it].videoId to learned[it] }
            var selected = MusicDiscoveryRanker.select(candidates, current, kind, chosenSeed, count,
                adjustment = { song -> scores[song.videoId].orZero() + if (LocalPreferenceLearning.explicitlyLiked(song)) .4f else 0f },
                hidden = LocalPreferenceLearning::lessLikeThis)
            if (kind == MusicPlaylistKind.SEED && chosenSeed != null && !LocalPreferenceLearning.lessLikeThis(chosenSeed)) {
                val rows = selected.filterNot { it.song.videoId == chosenSeed.videoId }
                val first = MusicDiscoveryRanker.deduplicate(listOf(MusicCandidate(chosenSeed, MusicCandidateOrigin.LIBRARY, explanation = "Your selected starting song"))).singleOrNull()
                selected = (listOfNotNull(first) + rows).take(count.coerceIn(1, 50))
            }
            val details = when (kind) {
                MusicPlaylistKind.DAILY_MIX -> "Daily order mixes your library, listening preferences and available independent catalogue pools."
                MusicPlaylistKind.REDISCOVER -> "Library recordings outside your latest 80 distinct listens. No network search is required."
                MusicPlaylistKind.FOLLOWED_RELEASES -> "Catalogue selections from your artist watch; new-to-watch releases lead when available. Unknown release dates stay unknown."
                MusicPlaylistKind.REGION -> "${value.countryLabel} charts and available catalogue matches" + if (value.musicLanguage.isNotBlank()) " for ${value.languageLabel}. Search matches are not verified language labels." else "."
                MusicPlaylistKind.SEED -> "Based on ${chosenSeed?.title ?: "your library"}, using the existing tiny model and local preferences."
            }
            MusicPlaylistPreview(kind.title, selected.map { it.song }, selected, details, failed)
        }
    }
    private fun Float?.orZero(): Float = this ?: 0f

    /** Home can publish immediately from whichever pools are ready; no waiting on all discovery requests. */
    suspend fun shelves(value: DiscoveryRegion): List<HomeShelf> {
        val rows = snapshot(value)
        val current = context
        val songs = rows.map { it.song }
        val learned = LocalRecommendations.localScores(current.history.firstOrNull() ?: current.library.firstOrNull(), songs)
        val scores = songs.indices.associate { songs[it].videoId to learned[it] }
        // A song shows on one of these shelves only: Rediscover used to repeat Daily mix's songs. Each shelf asks for more than it
        // shows and leaves out what an earlier one already holds.
        val shown = HashSet<String>()
        return listOf(MusicPlaylistKind.DAILY_MIX, MusicPlaylistKind.REDISCOVER, MusicPlaylistKind.FOLLOWED_RELEASES).mapNotNull { kind ->
            val chosen = MusicDiscoveryRanker.select(rows, current, kind, limit = 48, hidden = LocalPreferenceLearning::lessLikeThis,
                adjustment = { song -> scores[song.videoId].orZero() + if (LocalPreferenceLearning.explicitlyLiked(song)) .4f else 0f })
                .filter { MusicRecordingIdentity.key(it.song) !in shown && it.song.videoId !in shown }
                .take(24)
            chosen.forEach { shown += MusicRecordingIdentity.key(it.song); shown += it.song.videoId }
            if (chosen.isEmpty()) null else HomeShelf(kind.title, chosen.map { row ->
                val song = row.song; ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, song)
            }, subtitle = when (kind) {
                MusicPlaylistKind.REDISCOVER -> "Outside your recent listening"
                MusicPlaylistKind.FOLLOWED_RELEASES -> "From your artist watch · dates may be unknown"
                else -> "Your library and available discovery pools"
            }, sourceId = "discovery:mix:${kind.name.lowercase()}")
        }
    }
}
