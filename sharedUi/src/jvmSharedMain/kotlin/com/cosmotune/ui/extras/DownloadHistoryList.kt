package com.cosmotune.ui.extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.listening.DownloadHistory
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * The kept download history inside a download list (the phone's download sheet and Settings, the desktop's Downloads
 * window): "Earlier downloads" with Clear history, then each day's songs, newest first, saved or with why they failed.
 * Nothing when the history is empty.
 */
fun LazyListScope.downloadHistoryItems(
    entries: List<DownloadHistory.Entry>,
    nowMs: Long,
    onClear: () -> Unit,
    horizontal: androidx.compose.ui.unit.Dp = 16.dp,
    title: String = "Earlier downloads",
) {
    if (entries.isEmpty()) return
    item(key = "history-head") {
        val fonts = orbitFonts()
        val look = orbitLook()
        Row(
            Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = fonts.title.copy(fontSize = 16.sp), color = look.ink, modifier = Modifier.weight(1f))
            OrbitPillButton("Clear history", onClick = onClear, fonts = fonts, look = look, height = 36.dp)
        }
    }
    DownloadHistory.byDay(entries, nowMs).forEach { (day, rows) ->
        item(key = "history-day-$day") {
            OrbitLabel(day, orbitFonts(), orbitLook().dim, Modifier.padding(start = horizontal, end = horizontal, top = 8.dp, bottom = 4.dp))
        }
        items(rows, key = { "history-${it.trackId}" }) { entry ->
            val fonts = orbitFonts()
            val look = orbitLook()
            Column(Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, style = fonts.body, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOf(entry.artist, entry.failed?.let { "Failed: $it" } ?: "Saved").filter { it.isNotBlank() }.joinToString(" · ")
                Text(
                    line, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                    color = if (entry.failed != null) MaterialTheme.colorScheme.error else look.dim,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
