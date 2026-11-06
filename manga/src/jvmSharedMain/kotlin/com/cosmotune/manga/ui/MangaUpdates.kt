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
    val online = state.books.any { it.sourceId != "local" }
    val days = mangaUpdateDays(state)
    val count = days.sumOf { it.entries.size }
    Column(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = check.running, onRefresh = { if (online) check.start(scope) }, modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().testTag("manga:updates"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
                item(key = "heading") {
                    ReadPageHead(mangaText("Updates"), if (count == 1) mangaText("1 new chapter") else "$count " + mangaText("new chapters")) {
                        if (online) {
                            if (check.running) ReadIconButton(CosmoIcons.Close, mangaText("Stop"), { check.job?.cancel() })
                            else ReadIconButton(CosmoIcons.Replay, mangaText("Check for updates"), { check.start(scope) }, Modifier.testTag("manga:updates:check"))
                        }
                        MangaRootActions(scope)
                    }
                }
                item(key = "status") {
                    val report = check.report
                    val lines = listOfNotNull(
                        state.lastUpdateCompletedMs.takeIf { it > 0 }?.let { "Library last updated " + agoLabel(it) },
                        report?.let { updateReportLine(it, check.running) },
                        if (state.lastUpdateAttemptMs > state.lastUpdateCompletedMs && !check.running && report == null) "The last check did not finish; check again to retry." else null,
                    )
                    if (lines.isNotEmpty()) Column(Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 4.dp)) {
                        lines.forEach { Text(it, style = ReadType.meta(), color = Color(palette.textSecondary)) }
                    }
                }
                if (days.isEmpty()) item(key = "empty") {
                    if (online) ReadEmpty(mangaText("No new chapters"), mangaText("Pull down, or use Check for updates, to look for new chapters of your series."))
                    else ReadEmpty(mangaText("Nothing to check yet"), mangaText("Updates appear for the series you add to your library from online sources."))
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
    val days = mangaHistory(state)
    val time = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("manga:history:list"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "heading") {
                ReadPageHead(mangaText("History"), mangaText("The last chapter of each series you read")) { MangaRootActions(scope) }
            }
            if (days.isEmpty()) item(key = "empty") {
                ReadEmpty(mangaText("Nothing read yet"), mangaText("The series you read appear here, the latest first."))
            }
            days.forEach { day ->
                item(key = "day:${day.label}") { ReadOverline(day.label) }
                items(day.entries, key = { "history:${it.book.id}" }) { entry ->
                    val cover = rememberMangaCover(scope.repository, entry.book)
                    val at = time.format(Instant.ofEpochMilli(entry.progress.updatedAtMs).atZone(ZoneId.systemDefault()))
                    val position = entry.progress.takeIf { it.pageCount > 0 && !it.finished }?.let { "page ${it.page + 1} of ${it.pageCount}" } ?: if (entry.progress.finished) "finished" else null
                    ReadCoverRow(entry.book.title, listOfNotNull(entry.chapter?.title, position, at).joinToString(" · "), cover, key = entry.book.id,
                        modifier = Modifier.padding(horizontal = ReadMargin, vertical = 4.dp),
                        onClick = {
                            val chapter = entry.chapter
                            if (chapter != null) { scope.navigation.book = entry.book; scope.navigation.chapter = chapter } else scope.openBook(entry.book)
                        },
                        trailing = {
                            ReadIconButton(CosmoIcons.Close, mangaText("Remove from history"), {
                                scope.action { scope.repository.store.update { it.copy(progress = it.progress - entry.book.id) } }
                            })
                        })
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
    val groups = state.books.mapNotNull { book -> book.listedChapters().filter { state.downloads.containsKey(it.id) }.takeIf { it.isNotEmpty() }?.let { book to it } }
    Column(Modifier.fillMaxSize()) {
        ReadBackHead("Library", onBack = { scope.navigation.page = null })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "heading") {
                val count = groups.sumOf { it.second.size }
                ReadPageHead(mangaText("Downloads"), if (count == 1) mangaText("1 chapter on this device") else "$count " + mangaText("chapters on this device"))
            }
            if (scope.transfer.active) item(key = "running") {
                Text(scope.transfer.progress, style = ReadType.meta(), color = Color(palette.textSecondary), modifier = Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 8.dp))
            }
            if (groups.isEmpty()) item(key = "empty") {
                ReadEmpty(mangaText("No downloads"), mangaText("Download chapters from a series page to read them without a connection."))
            }
            groups.forEach { (book, chapters) ->
                item(key = "series:${book.id}") { ReadOverline(book.title) }
                items(chapters, key = { "download:${it.id}" }) { chapter ->
                    ReadChapterRow(
                        title = chapter.title, meta = "${state.downloads[chapter.id]?.size ?: 0} " + mangaText("pages"),
                        read = chapter.id in state.readChapters, fresh = chapter.id in state.newChapters, download = ReadDownload.DONE,
                        onClick = { scope.navigation.book = book; scope.navigation.chapter = chapter },
                        onLongClick = { scope.openBook(book) },
                        onDownload = { scope.action { scope.repository.removeDownload(chapter.id) } },
                        modifier = Modifier.padding(horizontal = ReadMargin, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
