package com.opencanvas.core.heatmap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Data point representing viewer replay intensity at a specific interval.
 */
data class HeatmapPoint(
    val startMs: Long,
    val durationMs: Long,
    val intensity: Float,
)

object HeatmapParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses the YouTube "Most Replayed" markers from an InnerTube player response.
     */
    fun parseHeatmap(playerResponseJson: String): List<HeatmapPoint> {
        val root = runCatching { json.parseToJsonElement(playerResponseJson).jsonObject }.getOrNull() ?: return emptyList()
        val points = mutableListOf<HeatmapPoint>()

        // 1. Framework updates (standard modern Innertube structure)
        val mutations = root["frameworkUpdates"]?.jsonObject
            ?.get("entityBatchUpdate")?.jsonObject
            ?.get("mutations")?.jsonArray

        mutations?.forEach { mutation ->
            val payload = (mutation as? JsonObject)?.get("payload")?.jsonObject ?: return@forEach
            val markersEntity = payload["macroMarkersListEntity"]?.jsonObject ?: return@forEach
            val markerList = markersEntity["markersList"]?.jsonObject?.get("markers")?.jsonArray ?: return@forEach

            markerList.forEach { marker ->
                val mObj = marker as? JsonObject ?: return@forEach
                val startMs = mObj["startMillis"]?.jsonPrimitive?.longOrNull
                    ?: mObj["startMillis"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
                val durationMs = mObj["durationMillis"]?.jsonPrimitive?.longOrNull
                    ?: mObj["durationMillis"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1000L
                val intensity = mObj["intensityScoreNormalized"]?.jsonPrimitive?.doubleOrNull?.toFloat()
                    ?: mObj["intensityScoreNormalized"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f

                points.add(HeatmapPoint(startMs, durationMs, intensity))
            }
        }

        return points
    }

    /**
     * Finds the peak engagement loop window [startMs, endMs] (usually the chorus/visual drop).
     * If heatmap is unavailable, defaults to the ~40% mark (typical first chorus).
     */
    fun findPeakLoopWindow(
        playerResponseJson: String,
        videoDurationMs: Long,
        preferredLoopDurationMs: Long = 10000L,
    ): Pair<Long, Long> {
        val points = parseHeatmap(playerResponseJson)

        if (points.isNotEmpty()) {
            // Find marker with maximum engagement intensity
            val peak = points.maxByOrNull { it.intensity }
            if (peak != null) {
                // Center the loop around the peak if possible
                val halfLoop = preferredLoopDurationMs / 2
                val start = (peak.startMs - halfLoop).coerceIn(0L, (videoDurationMs - preferredLoopDurationMs).coerceAtLeast(0L))
                val end = (start + preferredLoopDurationMs).coerceAtMost(videoDurationMs)
                return start to end
            }
        }

        // Fallback: 38%–45% of total video duration
        val fallbackStart = (videoDurationMs * 0.38).toLong().coerceIn(0L, (videoDurationMs - preferredLoopDurationMs).coerceAtLeast(0L))
        val fallbackEnd = (fallbackStart + preferredLoopDurationMs).coerceAtMost(videoDurationMs)
        return fallbackStart to fallbackEnd
    }
}
