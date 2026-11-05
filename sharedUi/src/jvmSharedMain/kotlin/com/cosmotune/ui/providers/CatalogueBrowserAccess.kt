package com.cosmotune.ui.providers

import com.cosmotune.music.data.model.Song
import com.cosmotune.providers.*
import kotlinx.serialization.json.JsonObject

data class CatalogueSearchPage(val tracks: List<ProviderTrack>, val collections: List<ProviderCollection> = emptyList())

/** A native source supplies data and recording identity; the existing browser owns navigation and presentation. */
interface CatalogueBrowserAccess {
    val identity: String
    val title: String
    val revision: String
    val enabled: Boolean
    val settings: JsonObject
    val regionKey: String? get() = null
    val languageKey: String? get() = null
    val hasHome: Boolean get() = false
    val supportedKinds: Set<String> get() = setOf("album", "artist", "playlist")
    suspend fun search(query: String, force: Boolean): CatalogueSearchPage
    suspend fun catalogue(key: ProviderBrowseKey, force: Boolean): ProviderCollection
    suspend fun home(region: String, language: String, force: Boolean): List<ProviderHomeSection> = emptyList()
    fun song(track: ProviderTrack): Song
}
