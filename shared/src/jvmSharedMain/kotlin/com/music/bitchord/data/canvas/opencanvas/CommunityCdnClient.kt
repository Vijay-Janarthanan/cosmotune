package com.opencanvas.core.cdn

import com.opencanvas.core.models.CropTrajectory
import kotlinx.serialization.json.Json

/**
 * Client for fetching and parsing zero-backend precomputed crop paths from global jsDelivr CDN.
 */
object CommunityCdnClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Standard jsDelivr URL for fetching community-curated crop metadata.
     */
    fun cdnUrlFor(videoId: String, cdnRepo: String = "opencanvas-project/crops"): String {
        return "https://cdn.jsdelivr.net/gh/$cdnRepo@main/v1/$videoId.json"
    }

    /**
     * Parses the JSON payload from the CDN into a [CropTrajectory].
     */
    fun parseTrajectory(jsonPayload: String): CropTrajectory? {
        return runCatching { json.decodeFromString<CropTrajectory>(jsonPayload) }.getOrNull()
    }
}
