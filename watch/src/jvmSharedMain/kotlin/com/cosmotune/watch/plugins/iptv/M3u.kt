package com.cosmotune.watch.plugins.iptv

import com.cosmotune.watch.model.Catchup

/** One `#EXTINF` entry of an M3U playlist with its address and the per-entry options (`#EXTVLCOPT`). */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logo: String? = null,
    val number: Int? = null,
    val groups: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val catchup: Catchup? = null,
    val headers: Map<String, String> = emptyMap(),
    val radio: Boolean = false,
    val durationSec: Int = -1,
)

data class M3uPlaylist(val entries: List<M3uEntry>, val guideUrls: List<String>)

/** M3U / M3U8 playlists as IPTV players read them (Kodi pvr.iptvsimple's tags). */
object M3uParser {
    private val attr = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(text: String): M3uPlaylist = parse(text.lineSequence())

    fun parse(lines: Sequence<String>): M3uPlaylist {
        val entries = mutableListOf<M3uEntry>()
        val guides = mutableListOf<String>()
        var pending: M3uEntry? = null
        var headers = mutableMapOf<String, String>()
        var group: String? = null
        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> attrs(line).let { a ->
                    listOfNotNull(a["url-tvg"], a["x-tvg-url"], a["tvg-url"]).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.forEach(guides::add)
                }
                line.startsWith("#EXTINF") -> {
                    val a = attrs(line.substringBeforeLast("\",", line) + "\"")
                    val name = line.substringAfterLast("\",", line.substringAfter(',', "")).let { if (it == line) line.substringAfter(',') else it }.trim()
                    val duration = line.removePrefix("#EXTINF:").takeWhile { it == '-' || it.isDigit() }.toIntOrNull() ?: -1
                    val catchup = a["catchup"]?.let { mode -> Catchup(mode, a["catchup-days"]?.toIntOrNull() ?: 1, a["catchup-source"]) }
                    pending = M3uEntry(
                        name = name.ifBlank { a["tvg-name"] ?: "Channel" }, url = "", tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = a["tvg-name"], logo = a["tvg-logo"]?.takeIf { it.isNotBlank() }, number = a["tvg-chno"]?.toIntOrNull() ?: a["channel-number"]?.toIntOrNull(),
                        groups = a["group-title"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                        languages = a["tvg-language"]?.split(';', ',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty(),
                        countries = a["tvg-country"]?.split(';', ',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty(),
                        catchup = catchup, radio = a["radio"] == "true", durationSec = duration,
                    )
                    a["http-user-agent"]?.let { headers["User-Agent"] = it }
                    a["http-referrer"]?.let { headers["Referer"] = it }
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.removePrefix("#EXTVLCOPT:")
                    val key = opt.substringBefore('=').trim().lowercase(); val value = opt.substringAfter('=', "").trim()
                    when (key) { "http-user-agent" -> headers["User-Agent"] = value; "http-referrer", "http-referer" -> headers["Referer"] = value; "http-origin" -> headers["Origin"] = value }
                }
                line.startsWith("#EXTGRP:") -> group = line.removePrefix("#EXTGRP:").trim()
                line.startsWith("#") -> Unit
                else -> {
                    val entry = pending
                    if (entry != null) {
                        // Some lists put "|User-Agent=..." after the address (Kodi style).
                        val (address, piped) = line.split('|', limit = 2).let { it[0] to it.getOrNull(1) }
                        piped?.split('&')?.forEach { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { headers[it[0]] = java.net.URLDecoder.decode(it[1], "UTF-8") } }
                        entries += entry.copy(url = address.trim(), headers = headers.toMap(), groups = entry.groups.ifEmpty { listOfNotNull(group) })
                    }
                    pending = null; headers = mutableMapOf(); group = null
                }
            }
        }
        return M3uPlaylist(entries, guides.distinct())
    }

    private fun attrs(line: String): Map<String, String> = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** iptv-org channel ids look like `BBCOne.uk` or `BBCOne.uk@HD`. */
    fun isIptvOrgId(id: String?) = id != null && Regex("^[A-Za-z0-9&+!-]+\\.[a-z]{2}(@[A-Za-z0-9]+)?$").matches(id)
}
