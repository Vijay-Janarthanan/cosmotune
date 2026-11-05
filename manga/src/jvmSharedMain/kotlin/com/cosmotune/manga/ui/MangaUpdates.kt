package com.cosmotune.manga.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitEmptyState
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.collapseFraction
import com.cosmotune.ui.theme.LocalCosmoColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The library's update check, held by the mode so it keeps running while another tab is open. */
internal class MangaUpdateCheck {
    var job by mutableStateOf<kotlinx.coroutines.Job?>(null)
    var report by mutableStateOf<MangaUpdateReport?>(null)
    val running: Boolean get() = job?.isActive == true

    fun start(scope: MangaScope) {
        if (running) return
        job = scope.action { scope.repository.checkForUpdates { report = it } }
    }
}

/** What the last or the running check found, as Updates says it under its title. */
internal fun updateReportLine(report: MangaUpdateReport, running: Boolean): String {
    val found = if (report.newChapters == 1) "1 new chapter" else "${report.newChapters} new chapters"
    return if (running) "Checking ${report.checked} of ${report.total} series · $found"
    else "$found in ${report.updatedSeries} series" + if (report.failures.isEmpty()) "" else " · ${report.failures.size} unavailable"
}

/** Updates (slot 3, after Mihon's): the new chapters of the library's series by day; pull down or the refresh action checks again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaUpdatesPage(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val check = scope.updates
    val list = rememberLazyListState()
    val threshold = rememberCollapseThreshold()
    val online = state.books.any { it.sourceId != "local" }
    val days = mangaUpdateDays(state)
    Column(Modifier.fillMaxSize()) {
        ReadTopBar(mangaText("Updates"), collapse = { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) },
            leading = LocalReadModeSwitcher.current, actions = { MangaRootActions(scope) {
                if (online) {
                    if (check.running) KitIconButton(CosmoIcons.Close, mangaText("Stop"), { check.job?.cancel() })
                    else KitIconButton(CosmoIcons.Replay, mangaText("Check for updates"), { check.start(scope) }, Modifier.testTag("manga:updates:check"))
                }
            } })
        PullToRefreshBox(isRefreshing = check.running, onRefresh = { if (online) check.start(scope) }, modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().testTag("manga:updates"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
                item(key = "status") {
                    val report = check.report
                    val lines = listOfNotNull(
                        state.lastUpdateCompletedMs.takeIf { it > 0 }?.let { "Library last updated " + agoLabel(it) },
                        report?.let { updateReportLine(it, check.running) },
                        if (state.lastUpdateAttemptMs > state.lastUpdateCompletedMs && !check.running && report == null) "The last check did not finish; check again to retry." else null,
                    )
                    if (lines.isNotEmpty()) Column(Modifier.padding(horizontal = ReadMargin, vertical = 4.dp)) {
                        lines.forEach { Text(it, style = ReadType.meta(), color = Color(palette.textSecondary)) }
                    }
                }
                if (days.isEmpty()) item(key = "empty") {
                    if (online) KitEmptyState(mangaText("No new chapters"), mangaText("Pull down, or use Check for updates, to look for new chapters of your series."), icon = Icons.Outlined.NewReleases)
                    else KitEmptyState(mangaText("Nothing to check yet"), mangaText("Updates appear for the series you add to your library from online sources."), icon = Icons.Outlined.NewReleases)
                }
                days.forEach { day ->
                    item(key = "day:${day.label}") { ReadOverline(day.label) }
                    items(day.entries, key = { "update:${it.chapter.id}" }) { entry -> MangaUpdateRow(scope, entry) }
                }
            }
        }
    }
}

/** History (in Library): the last chapter read in each series, by day. Tap goes back to it; the cross takes a series out. */
@Composable
internal fun MangaHistoryPage(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val list = rememberLazyListState()
    val threshold = rememberCollapseThreshold()
    val days = mangaHistory(state)
    val time = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    Column(Modifier.fillMaxSize()) {
        ReadTopBar(mangaText("History"), collapse = { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) },
            onBack = { scope.navigation.page = null }, backLabel = mangaText("Back"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("manga:history:list"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            if (days.isEmpty()) item(key = "empty") {
                KitEmptyState(mangaText("Nothing read yet"), mangaText("The series you read appear here, the latest first."), icon = Icons.Rounded.History)
            }
            days.forEach { day ->
                item(key = "day:${day.label}") { ReadOverline(day.label) }
                items(day.entries, key = { "history:${it.book.id}" }) { entry ->
                    val cover = rememberMangaCover(scope.repository, entry.book)
                    val at = time.format(Instant.ofEpochMilli(entry.progress.updatedAtMs).atZone(ZoneId.systemDefault()))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(role = Role.Button) {
                            val chapter = entry.chapter
                            if (chapter != null) { scope.navigation.book = entry.book; scope.navigation.chapter = chapter } else scope.openBook(entry.book)
                        }.padding(start = ReadMargin, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReadCover(cover, entry.book.title, Modifier.width(40.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.book.title, style = ReadType.rowTitle(), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val position = entry.progress.takeIf { it.pageCount > 0 && !it.finished }?.let { "page ${it.page + 1} of ${it.pageCount}" } ?: if (entry.progress.finished) "finished" else null
                            Text(listOfNotNull(entry.chapter?.title, position, at).joinToString(" · "), style = ReadType.meta(), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        KitIconButton(CosmoIcons.Close, mangaText("Remove from history"), {
                            scope.action { scope.repository.store.update { it.copy(progress = it.progress - entry.book.id) } }
                        })
                    }
                }
            }
        }
    }
}

/** Downloads (in Library): the chapters kept on this device, by series, each removable. */
@Composable
internal fun MangaDownloadsPage(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val list = rememberLazyListState()
    val threshold = rememberCollapseThreshold()
    val groups = state.books.mapNotNull { book -> book.listedChapters().filter { state.downloads.containsKey(it.id) }.takeIf { it.isNotEmpty() }?.let { book to it } }
    Column(Modifier.fillMaxSize()) {
        ReadTopBar(mangaText("Downloads"), collapse = { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) },
            onBack = { scope.navigation.page = null }, backLabel = mangaText("Back"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            if (scope.transfer.active) item(key = "running") {
                Text(scope.transfer.progress, style = ReadType.meta(), color = Color(palette.textSecondary), modifier = Modifier.padding(horizontal = ReadMargin, vertical = 8.dp))
            }
            if (groups.isEmpty()) item(key = "empty") {
                KitEmptyState(mangaText("No downloads"), mangaText("Download chapters from a series page to read them without a connection."), icon = CosmoIcons.Download)
            }
            groups.forEach { (book, chapters) ->
                item(key = "series:${book.id}") { ReadOverline(book.title) }
                items(chapters, key = { "download:${it.id}" }) { chapter ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { scope.navigation.book = book; scope.navigation.chapter = chapter }
                        .padding(start = ReadMargin, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(chapter.title, style = ReadType.rowTitle(), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${state.downloads[chapter.id]?.size ?: 0} pages", style = ReadType.meta(), color = Color(palette.textSecondary))
                        }
                        KitIconButton(CosmoIcons.Downloaded, mangaText("Remove download"), { scope.action { scope.repository.removeDownload(chapter.id) } })
                    }
                }
            }
        }
    }
}
