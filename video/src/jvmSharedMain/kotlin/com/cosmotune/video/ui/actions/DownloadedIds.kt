package com.cosmotune.video.ui.actions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.graph.LocalVideoGraph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The ids of the videos that have a finished download (spec 5.0: the downloaded badge on every card, row and Shorts card), from
 * the graph's `downloads.downloads`. Only a change of the set recomposes: the progress of a running download does not.
 */
@Composable
fun rememberDownloadedIds(): State<Set<String>> {
    val downloads = LocalVideoGraph.current.downloads
    val ids = remember(downloads) { downloads.downloads.map(::downloadedIds).distinctUntilChanged() }
    val initial = remember(downloads) { downloadedIds(downloads.downloads.value) }
    return ids.collectAsState(initial)
}

/** The ids of [entries] whose download is done. */
internal fun downloadedIds(entries: List<DownloadEntry>): Set<String> {
    if (entries.isEmpty()) return emptySet()
    val ids = HashSet<String>()
    for (entry in entries) if (entry.state is DownloadState.Done) ids += entry.item.id
    return ids
}
