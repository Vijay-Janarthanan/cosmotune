package com.cosmotune.watch.plugin

/**
 * The plugins that ship inside the app, in the Plugins page order, with their default switch. This list is the whole
 * built-in set: no torrent, debrid or site-scraper plugin is bundled or pre-configured, and none may be added here.
 */
object BuiltInPlugins {
    data class Def(val id: String, val kind: PluginKind, val name: String, val description: String, val badge: String,
        val defaultEnabled: Boolean, val capabilities: Set<Capability>)

    const val IPTV_ORG = "iptv"
    const val FREE_TV = "free-tv"
    const val PLUTO = "pluto"
    const val SAMSUNG = "samsung"
    const val PLEX = "plex"
    const val ARCHIVE = "ia"
    const val YOUTUBE = "youtube"
    const val PUBLIC_DOMAIN = "pdm"
    const val OPENSUBTITLES = "opensubtitles"
    const val WATCHHUB = "watchhub"
    /** Prasar Bharati's own Doordarshan channels. */
    const val PRASAR_BHARATI = "dd"
    /** Official broadcasters' 24x7 YouTube live channels. */
    const val YOUTUBE_LIVE = "ytlive"

    /**
     * The Stremio-protocol add-ons the built-ins use (read-only HTTP JSON). Public Domain Movies' own streams are torrents,
     * which Watch drops: it contributes its catalogue, and the Internet Archive plays the films.
     */
    const val PUBLIC_DOMAIN_URL = "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json"
    const val OPENSUBTITLES_URL = "https://opensubtitles-v3.strem.io/manifest.json"
    const val WATCHHUB_URL = "https://watchhub.strem.io/manifest.json"

    val all: List<Def> = listOf(
        Def(IPTV_ORG, PluginKind.IPTV_ORG, "iptv-org free channels", "Live TV · public channel lists · guide where available", "IPTV", true,
            setOf(Capability.LIVE, Capability.EPG, Capability.SEARCH)),
        Def(FREE_TV, PluginKind.M3U, "Free-TV/IPTV", "Live TV · worldwide free-to-air and free online channels", "FREE TV", true,
            setOf(Capability.LIVE, Capability.SEARCH, Capability.STREAMS)),
        Def(PLUTO, PluginKind.PLUTO_TV, "Pluto TV", "Live TV and free films and series", "PLUTO", true,
            setOf(Capability.LIVE, Capability.EPG, Capability.CATALOG, Capability.SEARCH, Capability.STREAMS, Capability.META)),
        Def(SAMSUNG, PluginKind.SAMSUNG_TV_PLUS, "Samsung TV Plus", "Live TV · free", "TV+", true,
            setOf(Capability.LIVE, Capability.EPG, Capability.SEARCH)),
        Def(PRASAR_BHARATI, PluginKind.PRASAR_BHARATI, "Prasar Bharati (DD)", "Live TV · DD National, DD News, DD Sports, DD Tamil and the regional DD channels, from their official YouTube", "DD", true,
            setOf(Capability.LIVE, Capability.EPG, Capability.STREAMS)),
        Def(YOUTUBE_LIVE, PluginKind.YOUTUBE_LIVE, "Live on YouTube", "Live TV · broadcasters' own 24x7 news streams: Tamil, Telugu, Malayalam, Kannada, Hindi, English", "YT LIVE", true,
            setOf(Capability.LIVE, Capability.EPG, Capability.STREAMS)),
        Def(PLEX, PluginKind.PLEX_FREE, "Plex free", "Free films and series, live TV", "PLEX", true,
            setOf(Capability.CATALOG, Capability.SEARCH, Capability.STREAMS, Capability.LIVE, Capability.EPG, Capability.META)),
        Def(ARCHIVE, PluginKind.INTERNET_ARCHIVE, "Internet Archive", "Classic and public-domain films", "ARC", true,
            setOf(Capability.CATALOG, Capability.SEARCH, Capability.STREAMS, Capability.META)),
        Def(YOUTUBE, PluginKind.YOUTUBE_FREE, "Free on YouTube", "Full films and episodes from official channels", "YT", true,
            setOf(Capability.CATALOG, Capability.STREAMS)),
        Def(PUBLIC_DOMAIN, PluginKind.STREMIO_ADDON, "Public Domain Movies (Stremio)", "Free classic films", "PD", false,
            setOf(Capability.CATALOG, Capability.STREAMS, Capability.META)),
        Def(OPENSUBTITLES, PluginKind.STREMIO_ADDON, "OpenSubtitles (Stremio)", "Subtitles in 60+ languages", "SUB", true,
            setOf(Capability.SUBTITLES)),
        Def(WATCHHUB, PluginKind.STREMIO_ADDON, "WatchHub (Stremio)", "Where a title streams: Netflix, Prime, Hotstar…", "HUB", false,
            setOf(Capability.WHERE_TO_WATCH)),
    )

    fun def(id: String): Def? = all.firstOrNull { it.id == id }

    fun info(def: Def, enabled: Boolean = def.defaultEnabled, status: String? = null) = PluginInfo(
        id = def.id, kind = def.kind, name = def.name, description = def.description, badge = def.badge, builtIn = true,
        enabled = enabled, defaultEnabled = def.defaultEnabled, capabilities = def.capabilities, status = status,
    )
}
