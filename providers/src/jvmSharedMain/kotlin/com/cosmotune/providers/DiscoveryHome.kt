package com.cosmotune.providers

import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.recommendations.DiscoveryFeed
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
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
        launch { ProviderHome.load(region.country, region.musicLanguage.ifBlank { java.util.Locale.getDefault().language },
            force = force, overrideLocale = true, publish = publish) }
        launch { DiscoveryFeed.load(region, publish) }
    }
}
