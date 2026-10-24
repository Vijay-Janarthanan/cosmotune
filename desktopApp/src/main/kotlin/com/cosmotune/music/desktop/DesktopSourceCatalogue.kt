package com.cosmotune.music.desktop

import com.cosmotune.providers.*
import kotlinx.serialization.json.JsonObject

internal object DesktopSourceCatalogue {
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
        val config = requireNotNull(DesktopPersistence().sourceConfigs().find { it.id == key.provider && it.kind == DesktopSourceKind.ADDON }) {
            "This source is no longer configured"
        }
        check(config.enabled) { "Enable this source first" }
        val response = DesktopAddonSource.catalogue(config, key.kind, key.id)
        val raw = response[key.kind] as? JsonObject ?: response
        val parsed = requireNotNull(ProviderCatalogue.collection(key.provider, key.kind, raw, key.id, "addon")) { "Incomplete source catalogue page" }
        val page = ProviderHub.artwork?.decorate("addon:${key.provider}", parsed) ?: parsed
        return SourceCataloguePage(page, page.tracks.map { DesktopAddonSource.catalogueSong(config, it) })
    }
}

@androidx.compose.runtime.Composable
internal fun DesktopSourceLibraryButton(collection: DesktopCollection) {
    val key = ProviderBrowseKey.parse(collection.browseId) ?: return
    com.cosmotune.ui.providers.SourceLibraryButton(SourceCataloguePage(
        ProviderCollection(key, collection.title, collection.subtitle.removeSuffix(" · Saved copy"), collection.thumbnailUrl), collection.songs))
}
