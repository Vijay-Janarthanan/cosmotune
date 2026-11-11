package com.cosmotune.watch.meta

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.WatchSkip
import kotlinx.serialization.json.*
import kotlin.math.abs
import okhttp3.HttpUrl.Companion.toHttpUrl

object AniSkip {
    suspend fun fetch(http: WatchHttp, malId: Int, episode: Int, durationMs: Long): List<WatchSkip> {
        if (malId <= 0 || episode <= 0 || durationMs <= 0) return emptyList()
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode".toHttpUrl().newBuilder()
            .addQueryParameter("types[]", "op").addQueryParameter("types[]", "ed").addQueryParameter("types[]", "recap")
            .addQueryParameter("episodeLength", (durationMs / 1000.0).toString()).build()
        return parse(http.getJson(url.toString()).jsonObject, durationMs)
    }
    fun parse(data: JsonObject, durationMs: Long): List<WatchSkip> = (data["results"] as? JsonArray).orEmpty().mapNotNull { element ->
        val value = element as? JsonObject ?: return@mapNotNull null
        val interval = value["interval"] as? JsonObject ?: return@mapNotNull null
        val start = (interval["startTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val end = (interval["endTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val length = (value["episodeLength"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        if (!start.isFinite() || !end.isFinite() || !length.isFinite() || start < 0 || end <= start ||
            end * 1000 > durationMs || end - start > 300 || abs(length * 1000 - durationMs) > 2500) return@mapNotNull null
        val label = when ((value["skipType"] as? JsonPrimitive)?.contentOrNull) { "op" -> "Skip opening"; "ed" -> "Skip ending"; "recap" -> "Skip recap"; else -> return@mapNotNull null }
        WatchSkip((start * 1000).toLong(), (end * 1000).toLong(), label)
    }.distinctBy { it.startMs to it.endMs }.sortedBy { it.startMs }
}
