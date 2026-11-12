package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/** The page's ink and its dim (Orbit), so this window's words read on a light page as on a dark one. */
private val inkColor: Color @Composable get() = com.cosmotune.ui.orbit.orbitLook().ink
private val dimColor: Color @Composable get() = com.cosmotune.ui.orbit.orbitLook().dim
private val groundColor: Color @Composable get() = com.cosmotune.ui.orbit.orbitLook().ground

/**
 * What the download queue is doing, and the two things that can be done to it.
 *
 * Android's `DownloadManagerSheet`: newest ask last, which is the order the queue reaches them in.
 */
@Composable
internal fun DesktopDownloadManagerDialog(onDismiss: () -> Unit) {
    val active by DesktopDownloadQueue.active.collectAsState()
    val storageError by DesktopDownloadQueue.storageError.collectAsState()
    val items = remember(active) { active.values.sortedBy(DesktopDownloadItem::sequence) }
    val failed = items.count { it.state is DesktopDownloadState.Failed }
    val busy = items.any { it.state !is DesktopDownloadState.Failed }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 520) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(DesktopStrings["downloads", "Downloads"], style = MaterialTheme.typography.titleLarge, color = inkColor)
                Text(
                    summary(items.size, failed, busy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed > 0 && !busy) DesktopDestructive else dimColor,
                )
            }
            if (busy) {
                OrbitPillButton(DesktopStrings["cancel_all", "Cancel all"], DesktopDownloadQueue::cancelAll, orbitFonts(), orbitLook(), height = 36.dp)
            } else if (items.isNotEmpty()) {
                OrbitPillButton(DesktopStrings["clear", "Clear"], DesktopDownloadQueue::clearFinished, orbitFonts(), orbitLook(), height = 36.dp)
            }
        }

        storageError?.let { Text(it, color = DesktopAccent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Text(DesktopStrings["nothing_downloading", "Nothing downloading."], color = dimColor)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.song.videoId }) { item -> OrbitDownloadRow(item) }
            }
        }
        Spacer(Modifier.height(12.dp))
        DesktopDialogButtons(null, {}, DesktopStrings["done", "Done"], onDismiss)
    }
}

/**
 * A download with the new look, as the kit's song row: the cover as a disc whose orbit is the download's progress (its moon circling
 * while the length is not yet known), what it is doing under the title, and retry and cancel as round buttons at its end.
 */
@Composable
private fun OrbitDownloadRow(item: DesktopDownloadItem) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val state = item.state
    val line = when (state) {
        is DesktopDownloadState.Queued -> DesktopStrings["queued", "Queued"]
        is DesktopDownloadState.Running -> if (state.fraction > 0f) "Downloading · ${(state.fraction * 100).toInt()}%" else "Downloading"
        is DesktopDownloadState.Failed -> state.reason
    }
    OrbitSongRow(
        title = item.song.title,
        subtitle = line,
        cover = item.song.thumbnailUrl,
        fonts = fonts,
        look = look,
        onClick = null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        height = 64.dp,
        endPadding = 8.dp,
        discContent = {
            Disc(item.song.thumbnailUrl, 44.dp)
            if (state is DesktopDownloadState.Running) {
                OrbitRing(52.dp, look.ink, progress = if (state.fraction > 0f) ({ state.fraction }) else null, spinning = true, stroke = 1.5.dp, moon = 3.dp)
            }
        },
        subtitleLeading = if (state is DesktopDownloadState.Failed) {
            { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp)) }
        } else null,
    ) {
        if (state is DesktopDownloadState.Failed) {
            OrbitRoundButton(Icons.Rounded.Refresh, DesktopStrings["retry", "Retry"], look.ink, { DesktopDownloadQueue.retry(item.song.videoId) }, size = 36.dp)
            Spacer(Modifier.width(6.dp))
        }
        OrbitRoundButton(CosmoIcons.Close, DesktopStrings["cancel", "Cancel"], look.ink, { DesktopDownloadQueue.cancel(item.song.videoId) }, size = 36.dp)
    }
}

internal fun summary(total: Int, failed: Int, busy: Boolean): String = when {
    total == 0 -> "Nothing downloading"
    failed > 0 && !busy -> if (failed == 1) "1 download failed" else "$failed downloads failed"
    total == 1 -> "1 track"
    else -> "$total tracks"
}
