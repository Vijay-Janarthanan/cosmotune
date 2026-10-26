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
import kotlinx.coroutines.withTimeoutOrNull

/** Independent, bounded shelves: a missing chart never prevents ordinary Home from loading. */
object DiscoveryFeed {
    suspend fun load(region: DiscoveryRegion, publish: (List<HomeShelf>) -> Unit) = coroutineScope {
        val charts = async {
            try {
                withTimeoutOrNull(15_000) {
                    val response = Innertube.discoveryCharts(region.country)
                    InnertubeParser.parseHome(response).take(8).map { shelf ->
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
        charts.await().takeIf { it.isNotEmpty() }?.let(publish)
        language.await().takeIf { it.isNotEmpty() }?.let(publish)
    }
}
