package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.InnertubeParser
import com.cosmotune.music.data.model.*
import kotlinx.coroutines.*

/** Independent bounded discovery pools; unavailable network sources never clear useful local results. */
object DiscoveryFeed {
    fun order(shelves: List<HomeShelf>): List<HomeShelf> = shelves.sortedBy {
        when {
            it.sourceId?.startsWith("discovery:language:") == true -> 0
            it.sourceId?.startsWith("discovery:charts:") == true -> 1
            it.sourceId?.startsWith("discovery:mix:") == true -> 2
            it.sourceId?.startsWith("sflx-") == true -> 3
            else -> 4
        }
    }

    suspend fun personalShelves(region: DiscoveryRegion): List<HomeShelf> = withContext(Dispatchers.Default) {
        val all = MusicCandidateService.snapshot(region)
        val language = if (region.musicLanguage.isBlank()) emptyList() else {
            val verified = all.filter { it.language?.equals(region.musicLanguage, true) == true }
            val search = all.filter { it.language == null && it.origin == MusicCandidateOrigin.LANGUAGE_CATALOGUE }
            listOf(verified to true, search to false).mapNotNull { (pool, confirmed) ->
                val selected = MusicDiscoveryRanker.select(pool, MusicCandidateService.context(), MusicPlaylistKind.REGION, limit = 30,
                    hidden = LocalPreferenceLearning::lessLikeThis)
                if (selected.isEmpty()) null else HomeShelf(
                    if (confirmed) "Discover ${region.languageLabel}" else "Matches for ${region.languageLabel}",
                    selected.map { row -> val song = row.song; ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, song) },
                    subtitle = if (confirmed) "Language stated by catalogue providers" else "Catalogue search matches · language and release dates not supplied",
                    sourceId = "discovery:language:${region.musicLanguage}:${if (confirmed) "stated" else "search"}")
            }
        }
        language + MusicCandidateService.shelves(region)
    }

    suspend fun load(region: DiscoveryRegion, publish: (List<HomeShelf>) -> Unit) = coroutineScope {
        personalShelves(region).takeIf { it.isNotEmpty() }?.let(publish)
        launch {
            try {
                val shelves = withTimeoutOrNull(10_000) {
                    InnertubeParser.parseDiscoveryCharts(Innertube.discoveryCharts(region.country)).take(8).map { shelf ->
                        shelf.copy(title = "${region.countryLabel} charts · ${shelf.title}", sourceId = "discovery:charts:${region.country}")
                    }
                }.orEmpty()
                if (shelves.isNotEmpty()) publish(shelves)
                val direct = shelves.flatMap { it.items }.mapNotNull { it.song }
                val songs = if (direct.isNotEmpty()) direct else shelves.flatMap { it.items }.mapNotNull { it.browseId }.distinct().take(2).map { id -> async {
                    try { withTimeoutOrNull(4_000) { YtMusicRepository.browseSongs(id).getOrThrow().songs.take(30) }.orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                } }.awaitAll().flatten()
                MusicCandidateService.ingest("charts:${region.country}", region, songs.take(100).mapIndexed { rank, song ->
                    MusicCandidate(song, MusicCandidateOrigin.REGION_CHART, rank, explanation = "${region.countryLabel} music charts; language not supplied")
                })
                personalShelves(region).takeIf { it.isNotEmpty() }?.let(publish)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Cached and independent pools remain usable. */ }
        }
        launch { MusicCandidateService.refresh(region) { personalShelves(region).takeIf { it.isNotEmpty() }?.let(publish) } }
    }
}
