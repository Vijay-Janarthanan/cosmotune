package com.cosmotune.video.cast.lounge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** One message of the lounge channel: its number, its name, its named fields and its plain values. */
data class LoungeMessage(val id: Int, val name: String, val args: Map<String, String> = emptyMap(), val values: List<String> = emptyList())

/** A phone linked to this screen. */
data class LoungeRemote(val id: String, val name: String)

/** What a message asks of the screen (the YouTube app's "Link with TV code" casting, as SmartTube answers it). */
sealed interface LoungeCommand {
    data class Session(val sid: String) : LoungeCommand
    data class GSession(val id: String) : LoungeCommand
    /** Play [videoId] from [startMs]; [videoIds] is the phone's queue and [index] where [videoId] is in it. */
    data class SetPlaylist(val videoId: String, val startMs: Long, val videoIds: List<String>, val index: Int) : LoungeCommand
    data object Play : LoungeCommand
    data object Pause : LoungeCommand
    data object Next : LoungeCommand
    data object Previous : LoungeCommand
    data object Stop : LoungeCommand
    data class SeekTo(val ms: Long) : LoungeCommand
    data class SetVolume(val volume: Int, val muted: Boolean) : LoungeCommand
    data object GetNowPlaying : LoungeCommand
    data object GetVolume : LoungeCommand
    data class Remotes(val remotes: List<LoungeRemote>) : LoungeCommand
    data class RemoteConnected(val remote: LoungeRemote) : LoungeCommand
    data class RemoteDisconnected(val id: String) : LoungeCommand
    data class Other(val name: String) : LoungeCommand
}

/** The lounge's state numbers for the screen's player. */
enum class LoungeState(val code: Int) { STOPPED(-1), ENDED(0), PLAYING(1), PAUSED(2), BUFFERING(3) }

/**
 * The YouTube lounge's wire format (the Browser Channel the YouTube app's "Link with TV code" uses): the answers come as
 * "<length>\n<JSON array>" chunks of `[number, [name, fields…]]` messages, and the screen answers with a form of numbered
 * events (`count`, `ofs`, `req0__sc=name`, `req0_field=value`).
 */
object LoungeProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /** The whole arrays at the start of [buffer] and what is left of it (an array still arriving). */
    fun split(buffer: String): Pair<List<String>, String> {
        val out = ArrayList<String>()
        var at = 0
        while (true) {
            while (at < buffer.length && buffer[at].isWhitespace()) at++
            val newline = buffer.indexOf('\n', at)
            if (newline < 0) break
            val length = buffer.substring(at, newline).trim()
            if (length.isEmpty() || !length.all { it.isDigit() }) {
                // not a length line: skip it so one stray line cannot stall the channel
                at = newline + 1
                continue
            }
            val start = newline + 1
            val end = arrayEnd(buffer, start)
            if (end < 0) break
            out += buffer.substring(start, end)
            at = end
        }
        return out to buffer.substring(at)
    }

    /** The index after the JSON array starting at [from] (skipping blanks), or -1 when it has not all arrived. */
    private fun arrayEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '[') return -1
        var depth = 0
        var quoted = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return -1
    }

    /** The messages of one array. */
    fun messages(array: String): List<LoungeMessage> = json.parseToJsonElement(array).jsonArray.mapNotNull { entry ->
        val pair = entry as? JsonArray ?: return@mapNotNull null
        val id = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
        val body = pair.getOrNull(1) as? JsonArray ?: return@mapNotNull null
        val name = (body.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val rest = body.drop(1)
        val args = rest.filterIsInstance<JsonObject>().firstOrNull()?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }.orEmpty()
        LoungeMessage(id, name, args, rest.filterIsInstance<JsonPrimitive>().map { it.contentOrNull.orEmpty() })
    }

    fun command(m: LoungeMessage): LoungeCommand = when (m.name) {
        "c" -> LoungeCommand.Session(m.values.firstOrNull().orEmpty())
        "S" -> LoungeCommand.GSession(m.values.firstOrNull().orEmpty())
        "setPlaylist", "setVideo" -> {
            val id = m.args["videoId"].orEmpty()
            val ids = m.args["videoIds"]?.split(',')?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOf(id) }
            if (id.isBlank()) LoungeCommand.Other(m.name)
            else LoungeCommand.SetPlaylist(id, seconds(m.args["currentTime"]), ids, m.args["currentIndex"]?.toIntOrNull() ?: ids.indexOf(id).coerceAtLeast(0))
        }
        "play" -> LoungeCommand.Play
        "pause" -> LoungeCommand.Pause
        "next" -> LoungeCommand.Next
        "previous" -> LoungeCommand.Previous
        "stopVideo" -> LoungeCommand.Stop
        "seekTo" -> LoungeCommand.SeekTo(seconds(m.args["newTime"]))
        "setVolume" -> LoungeCommand.SetVolume(m.args["volume"]?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: 100, m.args["muted"] == "true")
        "getNowPlaying" -> LoungeCommand.GetNowPlaying
        "getVolume" -> LoungeCommand.GetVolume
        "loungeStatus" -> LoungeCommand.Remotes(remotes(m.args["devices"]))
        "remoteConnected" -> LoungeCommand.RemoteConnected(LoungeRemote(remoteId(m), m.args["name"].orEmpty()))
        "remoteDisconnected" -> LoungeCommand.RemoteDisconnected(remoteId(m))
        else -> LoungeCommand.Other(m.name)
    }

    /** A connected device's id: the one in its `device` description (the top-level id may be another one). */
    private fun remoteId(m: LoungeMessage): String = m.args["device"]?.let { text ->
        runCatching { json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    } ?: m.args["id"].orEmpty()

    private fun remotes(devices: String?): List<LoungeRemote> = devices?.let { text ->
        runCatching {
            json.parseToJsonElement(text).jsonArray.mapNotNull { device ->
                val o = device.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull != "REMOTE_CONTROL") null
                else LoungeRemote(o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }.getOrNull()
    }.orEmpty()

    private fun seconds(text: String?): Long = ((text?.toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0)

    /** The form that sends [events] (name and fields) numbered from [ofs]. */
    fun form(ofs: Int, events: List<Pair<String, Map<String, String>>>): Map<String, String> = buildMap {
        put("count", events.size.toString())
        put("ofs", ofs.toString())
        events.forEachIndexed { i, (name, fields) ->
            put("req${i}__sc", name)
            fields.forEach { (key, value) -> put("req${i}_$key", value) }
        }
    }

    /** The fields of `nowPlaying` and `onStateChange` for the video playing. */
    fun stateFields(videoId: String?, positionMs: Long, durationMs: Long, state: LoungeState, index: Int?): Map<String, String> = buildMap {
        if (videoId != null) {
            put("videoId", videoId)
            index?.let { put("currentIndex", it.toString()) }
        }
        put("currentTime", secondsText(positionMs))
        put("state", state.code.toString())
        put("duration", secondsText(durationMs))
        put("loadedTime", "0")
        put("seekableStartTime", "0")
        put("seekableEndTime", secondsText(durationMs))
        put("cpn", "cosmotune")
    }

    private fun secondsText(ms: Long): String = (ms.coerceAtLeast(0) / 1000.0).toString()

    /** A new twelve-digit code for "Link with TV code". */
    fun newCode(random: Random = Random.Default): String = (1..12).map { random.nextInt(10) }.joinToString("")

    /** The code as the TV shows it: "123 456 789 012". */
    fun spaced(code: String): String = code.chunked(3).joinToString(" ")
}
