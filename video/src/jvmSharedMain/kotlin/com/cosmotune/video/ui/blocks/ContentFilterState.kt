package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.select.ContentFilter
import com.cosmotune.video.select.contentFilter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The listener's hidden words and channels (Settings, Content, Filters) as a filter; it changes only when they do. */
@Composable
internal fun rememberContentFilter(): ContentFilter {
    val settings = LocalVideoGraph.current.settings
    val filter by remember(settings) { settings.settings.map { it.contentFilter() }.distinctUntilChanged { a, b -> a.sameAs(b) } }
        .collectAsState(remember(settings) { settings.settings.value.contentFilter() })
    return filter
}
