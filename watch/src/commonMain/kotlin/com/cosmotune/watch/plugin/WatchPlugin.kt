package com.cosmotune.watch.plugin

import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProvider
import kotlinx.serialization.Serializable

/** What a plugin can answer. The repository only asks a plugin for what it declares. */
enum class Capability { CATALOG, SEARCH, META, STREAMS, SUBTITLES, LIVE, EPG, WHERE_TO_WATCH }

/** The protocol a plugin speaks; built-in plugins have their own kind, user sources the protocol's. */
enum class PluginKind {
    IPTV_ORG, PLUTO_TV, SAMSUNG_TV_PLUS, PRASAR_BHARATI, PLEX_FREE, INTERNET_ARCHIVE, YOUTUBE_FREE,
    STREMIO_ADDON, JELLYFIN, EMBY, PLEX_SERVER, M3U, XTREAM, LOCAL_FOLDER, TMDB, CINEMETA,
}

/** A row the plugin offers for Home or the Films/Series/Live pages. [type] null means mixed. */
@Serializable
data class CatalogDef(val pluginId: String, val id: String, val name: String, val type: TitleType?, val pageSize: Int = 20, val home: Boolean = true)

/**
 * One source of things to watch. Built-in plugins ship inside the app and are switched on or off; user sources
 * (Stremio-protocol add-ons by manifest URL, Jellyfin/Emby/Plex servers, M3U and Xtream playlists, local folders) are
 * plugins too. Every method has an empty default, so a plugin implements only what its [capabilities] say.
 *
 * Plugins speak read-only HTTP JSON, M3U or XMLTV; none downloads or runs code. Streams that are not playable by the app
 * itself (torrents, usenet, debrid) are dropped, never shown.
 */
interface WatchPlugin {
    /** Stable per configured instance, never holding a colon: `pluto`, `iptv`, `st-<hash>`, `jf-<hash>`. Used in keys. */
    val id: String
    val kind: PluginKind
    val displayName: String
    /** The short badge text ("PLUTO", "IPTV", "JF"). */
    val badge: String
    val capabilities: Set<Capability>

    suspend fun catalogs(): List<CatalogDef> = emptyList()
    suspend fun catalogPage(catalog: CatalogDef, skip: Int = 0): List<TitleSummary> = emptyList()
    suspend fun search(query: String, type: TitleType? = null): List<TitleSummary> = emptyList()

    /** The page for [key], when this plugin is a metadata authority for it (TMDB, Cinemeta, a server's own items). */
    suspend fun title(key: WatchKey): Title? = null
    suspend fun episodes(series: WatchKey, season: Int): List<Episode> = emptyList()

    /** Every copy this plugin can play of [target]. Torrents and usenet never appear. */
    suspend fun streams(target: PlayTarget): List<Stream> = emptyList()
    suspend fun subtitles(target: PlayTarget, stream: Stream?): List<Subtitle> = emptyList()
    suspend fun whereToWatch(target: PlayTarget): List<WatchProvider> = emptyList()

    suspend fun channels(): List<Channel> = emptyList()
    /** The guide of [channels] between [fromMs] and [toMs], keyed by [Channel.epgId]. */
    suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): List<EpgProgramme> = emptyList()

    /** Checks the plugin can be reached (and is signed in); a message for the Plugins page, or null when healthy. */
    suspend fun health(): String? = null
}

/** A plugin as the Plugins page shows it. */
@Serializable
data class PluginInfo(
    val id: String,
    val kind: PluginKind,
    val name: String,
    /** "Live TV · 2,000+ channels · guide". */
    val description: String,
    val badge: String,
    val builtIn: Boolean,
    val enabled: Boolean,
    val defaultEnabled: Boolean,
    val capabilities: Set<Capability>,
    /** "signed in", "340 channels", or the last error. */
    val status: String? = null,
    val error: Boolean = false,
)

/** What the user adds on the Plugins page ("Yours"). Secrets are sealed by the store, never kept in plain settings. */
@Serializable
sealed interface UserSourceSpec {
    val label: String

    /** Any Stremio- or Nuvio-protocol add-on by its manifest URL (`https://.../manifest.json` or `stremio://...`). */
    @Serializable data class StremioAddon(val manifestUrl: String, override val label: String = "") : UserSourceSpec
    @Serializable data class Jellyfin(val server: String, val user: String, val password: String, override val label: String = "") : UserSourceSpec
    @Serializable data class Emby(val server: String, val user: String, val password: String, override val label: String = "") : UserSourceSpec
    /** A Plex Media Server; [token] from plex.tv sign-in (PIN link) or pasted. */
    @Serializable data class Plex(val server: String, val token: String, override val label: String = "") : UserSourceSpec
    @Serializable data class M3u(val url: String, val epgUrl: String? = null, override val label: String = "") : UserSourceSpec
    @Serializable data class Xtream(val server: String, val user: String, val password: String, override val label: String = "") : UserSourceSpec
    @Serializable data class LocalFolder(val path: String, override val label: String = "") : UserSourceSpec
}

sealed interface AddSourceResult {
    data class Added(val plugin: PluginInfo) : AddSourceResult
    data class Failed(val message: String) : AddSourceResult
}
