package com.cosmotune.providers

import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.recommendations.DiscoveryFeed
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import com.cosmotune.music.data.recommendations.MusicCandidateService
import com.cosmotune.music.data.recommendations.MusicCandidate
import com.cosmotune.music.data.recommendations.DiscoveryRegion
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/** Both native hosts use the same preference snapshot and cancellation boundary. */
object DiscoveryHome {
    suspend fun load(force: Boolean = false, publish: (List<HomeShelf>) -> Unit) = coroutineScope {
        // Provider startup loads persisted discovery preferences before publishing ready/error.
        withTimeoutOrNull(5_000) { ProviderHub.state.first { !it.loading } }
        val region = DiscoveryPreferences.state.value
        val manager = ProviderHub.state.value.manager
        val enabled = manager?.store?.state?.value?.installed.orEmpty().filter { it.enabled }
        MusicCandidateService.begin(region, enabled.mapTo(HashSet()) { it.manifest.name })
        MusicCandidateService.providerSearch = manager?.let { current ->
            val search: suspend (DiscoveryRegion) -> List<MusicCandidate> = { selected ->
            coroutineScope {
                enabled.filter { "metadata_provider" in it.manifest.type }.take(2).map { installed -> async {
                    try {
                        withTimeoutOrNull(2_500) {
                            val query = if (selected.musicLanguage.isBlank()) "new music ${java.time.LocalDate.now().year}" else "${selected.languageLabel} new releases"
                            val found = current.search(installed.manifest.name, query, 30)
                            val stillEnabled = current.store.state.value.installed.any { it.manifest.name == installed.manifest.name && it.enabled && it.sha256 == installed.sha256 }
                            if (!stillEnabled) emptyList() else found.mapIndexed { rank, track -> ProviderDiscoveryMetadata.candidate(track, rank) }
                        }.orEmpty()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList<MusicCandidate>() }
                } }.awaitAll().flatten()
            }
            }
            search
        }
        launch { ProviderHome.load(region.country, region.musicLanguage.ifBlank { java.util.Locale.getDefault().language },
            force = force, overrideLocale = true, discoveryRegion = region, publish = { shelves ->
                publish(shelves)
                launch { DiscoveryFeed.personalShelves(region).takeIf { it.isNotEmpty() }?.let(publish) }
            }) }
        launch { DiscoveryFeed.load(region, publish) }
    }
}
