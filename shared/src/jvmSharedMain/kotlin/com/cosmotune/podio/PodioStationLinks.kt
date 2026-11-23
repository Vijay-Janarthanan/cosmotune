package com.cosmotune.podio

import java.util.Locale

/**
 * Stations the listener adds by hand (Transistor's and RadioDroid's "add station"): a stream address, or a playlist file (M3U, PLS)
 * that names one, with a name and a picture of their choosing.
 */
object PodioStationLinks {
    const val CUSTOM_PREFIX = "custom-"

    fun isCustom(station: RadioStation): Boolean = station.id.startsWith(CUSTOM_PREFIX)

    /** Whether [url] or its [contentType] is a playlist that names the stream rather than the stream itself. */
    fun isPlaylist(url: String, contentType: String? = null): Boolean {
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (type in setOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "application/pls+xml", "application/x-mpegurl")) return true
        val path = runCatching { java.net.URI(url.trim()).path.orEmpty() }.getOrDefault("").lowercase(Locale.ROOT)
        return path.endsWith(".m3u") || path.endsWith(".pls")
    }

    /**
     * The stream addresses a playlist names, in its order: M3U lines that are addresses, or PLS "FileN=" entries. An HLS playlist
     * (its "#EXT-X-" lines) is a stream in itself and names nothing here.
     */
    fun streams(playlist: String, base: String): List<String> {
        val text = playlist.removePrefix("﻿")
        if (text.contains("#EXT-X-", ignoreCase = true)) return emptyList()
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val raw = if (lines.firstOrNull()?.equals("[playlist]", ignoreCase = true) == true || lines.any { it.matches(Regex("(?i)file\\d+=.*")) })
            lines.mapNotNull { line -> Regex("(?i)^file(\\d+)=(.+)$").find(line)?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() } }.sortedBy { it.first }.map { it.second }
        else lines.filterNot { it.startsWith("#") }
        return raw.mapNotNull { entry -> runCatching { java.net.URI(base).resolve(entry).toString() }.getOrNull()?.let(PodioCatalogue::httpUrl) }.distinct().take(20)
    }

    /** The station as it is saved: its own id from its address, so adding it twice keeps one. */
    fun station(name: String, url: String, artwork: String = "", country: String = "", tags: String = ""): RadioStation? {
        val stream = PodioCatalogue.httpUrl(url) ?: return null
        val picture = PodioCatalogue.httpUrl(artwork).orEmpty()
        return RadioStation(CUSTOM_PREFIX + PodioCatalogue.identity(stream), name.trim().take(120).ifBlank { hostName(stream) }, stream,
            country = country.trim().take(80), tags = tags.trim().take(200), artwork = picture)
    }

    /** [saved] with [station] edited in place (name, picture, address), or added when it is not saved yet. */
    fun put(saved: PodioSaved, station: RadioStation, replacing: String? = null): PodioSaved {
        val id = replacing ?: station.id
        val at = saved.stations.indexOfFirst { it.id == id }
        return saved.copy(stations = if (at >= 0) saved.stations.toMutableList().also { it[at] = station } else saved.stations.filterNot { it.id == station.id } + station)
    }

    /** The saved stations as an M3U playlist another radio app can open. */
    fun m3u(stations: List<RadioStation>): String = buildString {
        append("#EXTM3U\n")
        stations.forEach { append("#EXTINF:-1,").append(it.name.replace('\n', ' ')).append('\n').append(it.url).append('\n') }
    }

    private fun hostName(url: String): String = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrNull().orEmpty().ifBlank { "My station" }

    /** The name a station's playlist file carries: PLS "Title1=", the first M3U "#EXTINF:-1,Name", else empty. */
    fun nameIn(playlist: String): String = playlist.lineSequence().map(String::trim).firstNotNullOfOrNull { line ->
        Regex("(?i)^title1=(.+)$").find(line)?.groupValues?.get(1) ?: Regex("(?i)^#EXTINF:[^,]*,(.+)$").find(line)?.groupValues?.get(1)
    }?.trim()?.take(120).orEmpty()
}

/**
 * A station link that reached the app from outside (a tapped M3U or PLS link in the browser, a playlist file opened from the file
 * manager or the desktop, a stream address shared to Cosmotune): it waits in [pending] while Podio opens its Add a station dialog.
 */
object PodioLinks {
    data class Pending(val name: String, val address: String)

    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<Pending?>(null)
    val pending: kotlinx.coroutines.flow.StateFlow<Pending?> = _pending

    /** Whether [link] is a station playlist address or file this app takes: an M3U or PLS by its name. */
    fun accepts(link: String): Boolean {
        val path = link.trim().substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".pls") || path.endsWith(".m3u")
    }

    /** A station's address (a stream or a playlist URL) to add; false when it is not a web address. */
    fun offer(address: String, name: String = ""): Boolean {
        val url = PodioCatalogue.httpUrl(address) ?: return false
        _pending.value = Pending(name.trim().take(120), url)
        return true
    }

    /** A playlist file's text: its first stream (and the name it gives) to add; false when it names none. */
    fun offerPlaylist(text: String, base: String = "https://localhost/", fallbackName: String = ""): Boolean {
        val stream = PodioStationLinks.streams(text, base).firstOrNull() ?: return false
        return offer(stream, PodioStationLinks.nameIn(text).ifBlank { fallbackName })
    }

    /**
     * A link the desktop was opened with: a web playlist or stream address, or a playlist file on the disk (a path or a file: URI),
     * which is read here. Returns whether it was a station link.
     */
    fun offerDesktop(link: String): Boolean {
        if (!accepts(link)) return false
        if (link.startsWith("http://", true) || link.startsWith("https://", true)) return offer(link)
        val file = runCatching { if (link.startsWith("file:", true)) java.io.File(java.net.URI(link)) else java.io.File(link) }.getOrNull() ?: return false
        if (!file.isFile || file.length() > 256 * 1024) return false
        return offerPlaylist(file.readText(), fallbackName = file.nameWithoutExtension)
    }

    /** Podio took the link (its dialog is open). */
    fun handled() { _pending.value = null }
}
