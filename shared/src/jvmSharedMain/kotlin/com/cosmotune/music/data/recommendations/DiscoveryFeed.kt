package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.InnertubeParser
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.ShelfItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Independent, bounded shelves: a missing chart never prevents ordinary Home from loading. */
object DiscoveryFeed {
    /** Explicit discovery choices lead Home; core/personal shelves remain available below them. */
    fun order(shelves: List<HomeShelf>): List<HomeShelf> = shelves.sortedBy {
        when {
            it.sourceId?.startsWith("discovery:language:") == true -> 0
            it.sourceId?.startsWith("discovery:charts:") == true -> 1
            it.sourceId?.startsWith("sflx-") == true -> 2
            else -> 3
        }
    }

    suspend fun load(region: DiscoveryRegion, publish: (List<HomeShelf>) -> Unit) = coroutineScope {
        val charts = async {
            try {
                withTimeoutOrNull(15_000) {
                    val response = Innertube.discoveryCharts(region.country)
                    InnertubeParser.parseDiscoveryCharts(response).take(8).map { shelf ->
                        shelf.copy(title = "${region.countryLabel} charts · ${shelf.title}",
                            sourceId = "discovery:charts:${region.country}")
                    }
                }.orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }
        val language = async {
            if (region.musicLanguage.isEmpty()) return@async emptyList()
            try {
                withTimeoutOrNull(10_000) {
                    val songs = YtMusicRepository.search("${region.languageLabel} songs", SearchFilter.SONGS)
                        .getOrDefault(emptyList()).mapNotNull { (it as? SearchResult.Track)?.song }.take(20)
                    if (songs.isEmpty()) emptyList() else listOf(HomeShelf(
                        title = "Discover ${region.languageLabel}",
                        items = songs.map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null, it) },
                        sourceId = "discovery:language:${region.musicLanguage}"))
                }.orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }
        // A slow/unavailable chart must not delay the selected language's results.
        launch { charts.await().takeIf { it.isNotEmpty() }?.let(publish) }
        launch { language.await().takeIf { it.isNotEmpty() }?.let(publish) }
    }
}
