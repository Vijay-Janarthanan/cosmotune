package com.cosmotune.providers

import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Provider shelves supplement the native Home feed without delaying its other sources. */
object ProviderHome {
    suspend fun load(region: String, language: String, force: Boolean = false,
        manager: ProviderManager? = null, publish: (List<HomeShelf>) -> Unit) {
        val ready = manager ?: withTimeoutOrNull(5_000) { ProviderHub.state.first { !it.loading }.manager } ?: return
        val state = ready.store.state.value
        coroutineScope {
            state.installed.filter { it.enabled && it.manifest.homeFeed }.take(8).forEach { installed -> launch {
                val name = installed.manifest.name
                val settings = state.settings[name]
                val effective = installed.manifest.effectiveSettings(settings.orEmpty())
                val regionKey = (installed.manifest.capabilities["homeFeedRegionSetting"] as? JsonPrimitive)?.contentOrNull
                val languageKey = installed.manifest.settings.firstOrNull { it.key in setOf("lang", "language", "locale") }?.key
                val country = regionKey?.let { (effective[it] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() } ?: region
                val locale = languageKey?.let { (effective[it] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() } ?: language
                try {
                    val sections = ready.home(name, country, locale, force)
                    currentCoroutineContext().ensureActive()
                    val now = ready.store.state.value
                    if (now.installed.find { it.manifest.name == name } == installed && now.settings[name] == settings) {
                        val shelves = shelves(installed.manifest.title, sections)
                        if (shelves.isNotEmpty()) publish(shelves)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* One unavailable provider never removes another Home feed. */ }
            } }
        }
    }

    internal fun shelves(title: String, sections: List<ProviderHomeSection>): List<HomeShelf> = sections.take(20).mapNotNull { section ->
        val tracks = section.tracks.take(50).map { track ->
            val song = track.song()
            ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, song)
        }
        val collections = section.collections.take(50).map { page ->
            ShelfItem(page.title, page.subtitle, page.artwork?.takeIf(CatalogueArtwork::handles), null, page.key.encode())
        }
        (tracks + collections).take(50).takeIf { it.isNotEmpty() }?.let {
            HomeShelf("$title · ${section.title}", it, subtitle = title, sourceId = "sflx-${section.provider}")
        }
    }
}
