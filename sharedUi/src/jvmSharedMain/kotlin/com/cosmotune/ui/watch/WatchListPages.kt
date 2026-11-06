package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitSearchPill
import com.cosmotune.ui.orbit.WatchCard
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchSkeleton
import com.cosmotune.watch.LiveNowItem
import com.cosmotune.watch.SearchResults
import com.cosmotune.watch.model.TitleSummary
import kotlinx.coroutines.delay

/** Search (board: Search in the sidebar): Orbit's search pill, then films and series as posters and channels as live rows. */
@Composable
internal fun WatchSearchPage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame, initialQuery: String = "") {
    val repository = actions.repository
    val clock = LocalWatchClock.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<LiveNowItem>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; searching = false; return@LaunchedEffect }
        searching = true
        delay(300)
        val found = runCatching { repository.search(q) }.getOrNull()
        live = found?.channels.orEmpty().take(8).map { c -> runCatching { repository.nowNext(c) }.getOrDefault(LiveNowItem(c)) }
        results = found
        searching = false
    }
    val side = frame.side
    Box(Modifier.fillMaxSize()) {
        WatchWash(Color(0xFF2E2758), look)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "search:header") { header() } }
            item(key = "search:heading") {
                OrbitPageHeading("Search", if (frame.wide) "Films, series and channels · every plugin that is on" else "Every plugin that is on", fonts, look, Modifier.padding(start = side + 6.dp, end = side, top = if (frame.wide) 24.dp else 18.dp))
            }
            item(key = "search:pill") {
                OrbitSearchPill(query, { query = it }, onSubmit = {}, placeholder = "What do you want to watch?", fonts = fonts, look = look, focusRequester = focus,
                    modifier = Modifier.padding(horizontal = side).padding(top = 20.dp))
            }
            val r = results
            when {
                query.isBlank() -> item(key = "search:hint") {
                    WatchMessage("Search", "Find anything to watch", "A title, a channel or a series. Search asks every plugin that is on, and the free ones are on from the start.",
                        fonts, look, Modifier.padding(horizontal = side).padding(top = 22.dp))
                }
                searching && r == null -> item(key = "search:loading") {
                    LazyRow(Modifier.padding(top = 26.dp), contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(5) { WatchSkeleton(look, Modifier.size(132.dp, 198.dp)) }
                    }
                }
                r != null && r.isEmpty -> item(key = "search:none") {
                    WatchMessage("No results", "Nothing for “${query.trim()}”", "Try another spelling, or switch on more plugins.", fonts, look,
                        Modifier.padding(horizontal = side).padding(top = 22.dp), action = "Open Plugins", onAction = { WatchNav.go(WatchTab.PLUGINS) })
                }
                r != null -> {
                    if (r.films.isNotEmpty()) item(key = "search:films") { PosterShelf("Films", r.films, actions, fonts, look, side) }
                    if (r.series.isNotEmpty()) item(key = "search:series") { PosterShelf("Series", r.series, actions, fonts, look, side) }
                    if (live.isNotEmpty()) item(key = "search:channels") {
                        Column {
                            Section("Channels", null, fonts, look, side)
                            Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                live.forEach { item -> LiveRow(item, fonts, look, clock) { actions.playChannel(item.channel) } }
                            }
                        }
                    }
                }
            }
            item(key = "search:end") { Spacer(Modifier.height(40.dp)) }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun PosterShelf(title: String, items: List<TitleSummary>, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    Column {
        Section(title, null, fonts, look, side)
        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(items, key = { i, it -> it.key.value + i }) { _, s ->
                WatchCard(s.name, posterLine(s), s.posterUrl ?: s.backdropUrl, s.key.value, fonts, look, onClick = { actions.open(s) }, width = 142.dp, onPlay = { actions.play(s) })
            }
        }
    }
}

/** My list: what you kept, newest first, as posters in a grid; a poster opens its page, the moon plays. */
@Composable
internal fun WatchMyListPage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val list by actions.repository.myList.collectAsState()
    val side = frame.side
    Box(Modifier.fillMaxSize()) {
        WatchWash(Color(0xFF1C4C49), look)
        LazyVerticalGrid(
            GridCells.Adaptive(if (frame.wide) 150.dp else 104.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = frame.padding.calculateTopPadding(), bottom = frame.padding.calculateBottomPadding() + 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            frame.header?.let { header -> item(key = "list:header", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(horizontal = 0.dp)) { header() } } }
            item(key = "list:heading", span = { GridItemSpan(maxLineSpan) }) {
                OrbitPageHeading("My list", if (list.size == 1) "1 saved" else "${list.size} saved", fonts, look, Modifier.padding(start = 6.dp, top = if (frame.wide) 24.dp else 18.dp, bottom = 4.dp))
            }
            if (list.isEmpty()) item(key = "list:empty", span = { GridItemSpan(maxLineSpan) }) {
                WatchMessage("My list", "Nothing kept yet", "Use My list on a film or a series to keep it here, on every device you sign in on.", fonts, look,
                    action = "Browse films", onAction = { WatchNav.go(WatchTab.FILMS) })
            }
            itemsIndexed(list, key = { _, e -> e.key.value }) { _, entry ->
                val s = entry.summary
                WatchCard(s.name, posterLine(s), s.posterUrl ?: s.backdropUrl, s.key.value, fonts, look, onClick = { actions.open(s) }, width = null,
                    onPlay = { actions.play(s) })
            }
        }
    }
}

/** What can be downloaded, said on the Downloads page and wherever a copy cannot be. */
internal const val WATCH_DOWNLOAD_RULE = "Direct files only: MP4, MKV and WebM from your servers, your add-ons and the free libraries. Live channels and HLS or DASH streams can't be downloaded."

/**
 * Downloads: the films and episodes kept on this device, newest first, with how far each has come; a finished one plays from
 * the file (no network), a stopped one starts again, and any can be removed with its file.
 */
@Composable
internal fun WatchDownloadsPage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val side = frame.side
    val downloads = actions.downloads
    val list = downloads?.items?.collectAsState()?.value.orEmpty()
    Box(Modifier.fillMaxSize()) {
        WatchWash(Color(0xFF4E3C12), look)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "dl:header") { header() } }
            item(key = "dl:heading") {
                val done = list.count { it.status == com.cosmotune.watch.WatchDownload.Status.DONE }
                OrbitPageHeading("Downloads", if (list.isEmpty()) "On this device" else "On this device · $done kept", fonts, look, Modifier.padding(start = side + 6.dp, end = side, top = if (frame.wide) 24.dp else 18.dp))
            }
            if (downloads == null || list.isEmpty()) item(key = "dl:empty") {
                WatchMessage("Downloads", "Nothing downloaded",
                    if (downloads == null) "Downloading is not available on this device." else "Use Download on a film's player or its page to keep it here and watch it offline. $WATCH_DOWNLOAD_RULE",
                    fonts, look, Modifier.padding(horizontal = side).padding(top = 22.dp))
            } else {
                item(key = "dl:rule") {
                    androidx.compose.material3.Text(WATCH_DOWNLOAD_RULE, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim,
                        modifier = Modifier.padding(horizontal = side + 6.dp).padding(top = 14.dp))
                }
                itemsIndexed(list, key = { _, d -> "dl:" + d.id }) { _, d ->
                    DownloadRow(d, downloads, actions, fonts, look, Modifier.padding(horizontal = side).padding(top = 10.dp))
                }
            }
            item(key = "dl:end") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun DownloadRow(d: com.cosmotune.watch.WatchDownload, downloads: com.cosmotune.watch.WatchDownloads, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val done = d.status == com.cosmotune.watch.WatchDownload.Status.DONE
    val play = { d.target?.let { t -> WatchNav.open(WatchRoute.Play(t, d.summary, downloads.streamOf(d))) }; Unit }
    com.cosmotune.ui.orbit.WatchRowFrame(look, modifier, height = 84.dp, onClick = if (done) play else null) {
        com.cosmotune.ui.orbit.WatchPicture(d.summary?.backdropUrl ?: d.summary?.posterUrl, d.key.value, Modifier.padding(start = 4.dp).size(96.dp, 56.dp), radius = 14.dp)
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Text(d.title, style = fonts.title, color = look.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val status = when (d.status) {
                com.cosmotune.watch.WatchDownload.Status.DONE -> listOfNotNull(d.line, sizeText(d.totalBytes ?: d.bytes)).joinToString(" · ")
                com.cosmotune.watch.WatchDownload.Status.DOWNLOADING -> "Downloading · " + (d.fraction?.let { "${(it * 100).toInt()}%" } ?: sizeText(d.bytes))
                com.cosmotune.watch.WatchDownload.Status.FAILED -> d.error ?: "Stopped"
            }
            androidx.compose.material3.Text(status, style = fonts.body, color = look.dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (d.status == com.cosmotune.watch.WatchDownload.Status.DOWNLOADING) {
                com.cosmotune.ui.orbit.OrbitProgressLine({ d.fraction ?: 0f }, look.accent, Modifier.padding(top = 6.dp, end = 12.dp))
            }
        }
        when (d.status) {
            com.cosmotune.watch.WatchDownload.Status.DONE -> com.cosmotune.ui.orbit.WatchCardChip("Play", look.ink, fonts, onClick = play, icon = com.cosmotune.ui.components.CosmoIcons.Play)
            com.cosmotune.watch.WatchDownload.Status.FAILED -> com.cosmotune.ui.orbit.WatchCardChip("Try again", look.ink, fonts, onClick = {
                if (!downloads.retry(d.id)) d.summary?.let { actions.open(it) }
            })
            else -> Unit
        }
        com.cosmotune.ui.orbit.WatchRoundButton(com.cosmotune.ui.components.CosmoIcons.Close, if (done) "Remove ${d.title}" else "Stop ${d.title}", look, onClick = { downloads.remove(d.id) }, size = 36.dp)
    }
}

/** "1.4 GB", "820 MB". */
internal fun sizeText(bytes: Long?): String? = when {
    bytes == null || bytes <= 0 -> null
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    else -> "${(bytes / 1_000_000).coerceAtLeast(1)} MB"
}
