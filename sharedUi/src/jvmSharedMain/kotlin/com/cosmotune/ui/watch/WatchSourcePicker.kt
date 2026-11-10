package com.cosmotune.ui.watch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.watch.WatchPlayerState
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.WatchStreamFilter
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.watchMetadata

@Composable
internal fun WatchSourceFilters(
    streams: List<Stream>, filter: WatchStreamFilter, update: (WatchStreamFilter) -> Unit,
    fonts: OrbitFonts, look: OrbitLook,
) {
    val qualities = WatchStreamFilter.qualities(streams)
    val providers = streams.map { it.source }.distinctBy { it.pluginId }
    val showQuality = qualities.size > 1 || filter.quality != null
    val showProvider = providers.size > 1 || filter.providerId != null
    if (!showQuality && !showProvider) return
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showQuality) {
            StreamFilterChips("Quality", qualities.map { it to it }, filter.quality, fonts, look) {
                update(filter.copy(quality = it))
            }
        }
        if (showProvider) {
            StreamFilterChips("Provider", providers.map { it.pluginId to it.pluginName }, filter.providerId, fonts, look) {
                update(filter.copy(providerId = it))
            }
        }
    }
}

@Composable
private fun StreamFilterChips(
    title: String, options: List<Pair<String, String>>, selected: String?,
    fonts: OrbitFonts, look: OrbitLook, select: (String?) -> Unit,
) {
    OrbitLabel(title, fonts, look.dim, Modifier.padding(start = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OrbitSelectChip("All", selected == null, fonts, look, onClick = { select(null) }, haptic = false)
        options.forEach { (key, label) ->
            OrbitSelectChip(label, selected == key, fonts, look, onClick = { select(key) }, haptic = false)
        }
    }
}

@Composable
internal fun WatchSourcesMenu(
    open: Boolean, dismiss: () -> Unit, state: WatchPlayerState,
    fonts: OrbitFonts, look: OrbitLook, switch: (Stream) -> Unit,
) {
    if (!open) return
    var filter by remember(state.target?.key) { mutableStateOf(WatchStreamFilter()) }
    val matching = filter.applyTo(state.streams)
    val runtime = state.summary?.takeIf { it.type == TitleType.FILM }?.runtimeMin
    OrbitMenu(
        open, dismiss, fonts = fonts, look = look, header = "Play from · best first", width = 320.dp,
        filters = { WatchSourceFilters(state.streams, filter, { filter = it }, fonts, look) },
        items = if (matching.isEmpty()) {
            listOf(OrbitMenuItem("No streams match", "Reset filters", keepOpen = true) { filter = WatchStreamFilter() })
        } else matching.map { stream ->
            val selected = stream == state.stream
            val detail = stream.watchMetadata(state.durationMs.takeIf { selected }, runtime)
            OrbitMenuItem(
                "${stream.source.pluginName} · ${stream.label}",
                listOfNotNull(detail, stream.description?.lineSequence()?.firstOrNull { it.isNotBlank() }).joinToString("\n"),
                selected = selected, enabled = stream.isPlayable,
            ) { if (!selected || state.status == WatchPlayerStatus.ERROR) switch(stream) }
        },
    )
}
