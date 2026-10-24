package com.cosmotune.music.data.sources

import com.cosmotune.providers.*
import kotlinx.serialization.json.JsonObject

/** Player links and library cards use the same source-owned pages as the Sources browser. */
object SourceCatalogue {
    suspend fun load(key: ProviderBrowseKey, manager: ProviderManager? = ProviderHub.state.value.manager): SourceCataloguePage {
        try { return fetch(key, manager) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { return ProviderHub.state.value.library?.savedPage(key.encode()) ?: throw error }
    }
    private suspend fun fetch(key: ProviderBrowseKey, manager: ProviderManager?): SourceCataloguePage {
        if (key.namespace == "provider") {
            val page = requireNotNull(manager) { "Provider storage is unavailable" }.catalogue(key)
            return SourceCataloguePage(page, page.tracks.map { ProviderHub.remember(it.song()) })
        }
        val config = requireNotNull(SourceRegistry.config(key.provider)) { "This source is no longer configured" }
        check(config.enabled) { "Enable this source first" }
        val source = SourceRegistry.instance(key.provider) as? AddonSource ?: error("This is not an HTTP add-on")
        val response = source.catalogue(key.kind, key.id)
        val raw = response[key.kind] as? JsonObject ?: response
        val parsed = requireNotNull(ProviderCatalogue.collection(key.provider, key.kind, raw, key.id, "addon")) { "Incomplete source catalogue page" }
        val page = ProviderHub.artwork?.decorate("addon:${key.provider}", parsed) ?: parsed
        return SourceCataloguePage(page, page.tracks.map(source::catalogueSong))
    }
}
