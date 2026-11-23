package com.cosmotune.video.alerts

import com.cosmotune.video.feed.UploadAlerts
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.CancellationException

/**
 * One check for new videos of the channels whose bell is on: the feed is read (a refresh within its own 10 minutes is skipped by
 * the feed), the new uploads since the last check are worked out and the mark is moved past them. The phone's background job and
 * the desktop's timer call this and show what it answers as a notification. Nothing is read while no bell is on.
 */
suspend fun checkNewUploads(graph: VideoGraph, nowMs: Long = System.currentTimeMillis()): List<VideoItem> {
    val settings = graph.settings.settings.value
    if (settings.bellChannels.isEmpty()) return emptyList()
    try {
        graph.feed.refresh(force = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return emptyList()
    }
    val due = UploadAlerts.due(graph.feed.state.value.items, settings.bellChannels, settings.uploadAlertsSeenMs, nowMs)
    graph.settings.update { it.copy(uploadAlertsSeenMs = due.seenUntilMs) }
    return due.items
}

/** The address a tapped alert opens: the video itself, which Cosmotune opens in Video mode. */
fun alertLink(item: VideoItem): String = "https://www.youtube.com/watch?v=${item.id}"
