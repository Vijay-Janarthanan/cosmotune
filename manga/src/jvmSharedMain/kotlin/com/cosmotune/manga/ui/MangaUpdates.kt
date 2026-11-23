package com.cosmotune.manga.ui

import com.cosmotune.ui.orbit.LocalOrbitUndo
import com.cosmotune.ui.orbit.removedThen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * Updates (board frame 12, after Mihon's): the count of new chapters, then the new chapters of the library's series by day, each day
 * with its count and "Mark all read". While a check runs its progress and Stop lead the page, and quiet rows shaped like the
 * chapters hold the place where new ones land. Pull down or the refresh action checks again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaUpdatesPage(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val check = scope.updates
    val list = rememberLazyListState()
    val online = state.books.any { it.sourceId != "local" }
    // A collection chip narrows Updates to its series (Mihon's category filter); By series groups them (Komikku).
    var collection by rememberSaveable { mutableStateOf("") }
    var bySeries by rememberSaveable { mutableStateOf(false) }
    if (collection.isNotBlank() && state.collections.none { it.id == collection }) collection = ""
    val shown = if (collection.isBlank()) state else state.copy(books = state.collectionBooks(collection))
    val days = mangaUpdateDays(shown)
    val series = if (bySeries) mangaUpdatesBySeries(shown) else emptyList()
    val upcoming = remember(shown.books) { mangaUpcoming(shown) }
    val failed = shown.books.filter { it.id in state.updateFailures }
    var failedOpen by rememberSaveable { mutableStateOf(false) }
    val count = days.sumOf { it.entries.size }
    Column(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = check.running, onRefresh = { if (online) check.start(scope) }, modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().testTag("manga:updates"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
                item(key = "heading") {
                    ReadPageHead(mangaText("Updates"), if (count == 1) mangaText("1 new chapter") else "$count " + mangaText("new chapters")) {
                        if (online && !check.running) ReadIconButton(CosmoIcons.Replay, mangaText("Check for updates"), { check.start(scope) }, Modifier.testTag("manga:updates:check"))
                        MangaRootActions(scope)
                    }
                }
                item(key = "status") {
                    val report = check.report
                    if (check.running) Row(Modifier.fillMaxWidth().padding(start = ReadMargin + 4.dp, end = ReadMargin, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(report?.let { "Checking ${it.checked} of ${it.total} series" } ?: mangaText("Checking your series"), style = ReadType.rowTitle(),
                            color = Color(palette.textPrimary), modifier = Modifier.weight(1f).testTag("manga:updates:progress"))
                        ReadTextButton(mangaText("Stop"), { check.job?.cancel() })
                    } else {
                        val lines = listOfNotNull(
                            state.lastUpdateCompletedMs.takeIf { it > 0 }?.let { "Library last updated " + agoLabel(it) },
                            report?.let { updateReportLine(it, false) },
                            if (state.lastUpdateAttemptMs > state.lastUpdateCompletedMs && report == null) "The last check did not finish; check again to retry." else null,
                        )
                        if (lines.isNotEmpty()) Column(Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 4.dp)) {
                            lines.forEach { Text(it, style = ReadType.meta(), color = Color(palette.textSecondary)) }
                        }
                    }
                }
                item(key = "next-check") {
                    var open by rememberSaveable { mutableStateOf(false) }
                    val queue = remember(state.books, state.sources, state.readChapters, state.updateSkipFinished) {
                        mangaSeriesToUpdate(state, state.sources.filter { it.enabled }.map { it.id }.toSet())
                    }
                    if (queue.isNotEmpty() && !check.running) Column(Modifier.fillMaxWidth().testTag("manga:updates:next")) {
                        Row(Modifier.fillMaxWidth().padding(end = ReadMargin - 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReadOverline(mangaText("Next check") + " · ${queue.size} " + mangaText("series"), Modifier.weight(1f))
                            ReadTextButton(mangaText(if (open) "Hide" else "Show"), { open = !open }, Modifier.padding(top = 16.dp))
                        }
                        if (open) queue.take(100).forEach { book ->
                            val source = state.sources.find { it.id == book.sourceId }?.name.orEmpty()
                            ReadCoverRow(book.title, source, rememberMangaCover(scope.repository, book), key = book.id, modifier = Modifier.padding(horizontal = ReadMargin),
                                onClick = { scope.openBook(book) })
                        }
                    }
                }
                item(key = "view") {
                    ReadChipRow(Modifier.padding(top = 4.dp, bottom = 4.dp).testTag("manga:updates:view")) {
                        ReadChip(mangaText("By day"), !bySeries, { bySeries = false })
                        ReadChip(mangaText("By series"), bySeries, { bySeries = true })
                        if (state.collections.isNotEmpty()) {
                            ReadChip(mangaText("All series"), collection.isBlank(), { collection = "" })
                            state.collections.forEach { item -> ReadChip(item.name, collection == item.id, { collection = item.id }) }
                        }
                    }
                }
                if (failed.isNotEmpty() && !check.running) item(key = "failed") {
                    Column(Modifier.fillMaxWidth().testTag("manga:updates:failed")) {
                        Row(Modifier.fillMaxWidth().padding(end = ReadMargin - 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReadOverline(mangaText("Not updated") + " · ${failed.size}", Modifier.weight(1f))
                            ReadTextButton(mangaText(if (failedOpen) "Hide" else "Show"), { failedOpen = !failedOpen }, Modifier.padding(top = 16.dp))
                        }
                        if (failedOpen) failed.forEach { book ->
                            val cover = rememberMangaCover(scope.repository, book)
                            ReadCoverRow(book.title, state.updateFailures[book.id].orEmpty(), cover, key = book.id, modifier = Modifier.padding(horizontal = ReadMargin),
                                onClick = { scope.openBook(book) })
                        }
                    }
                }
                // Where new chapters will land while the check runs: rows shaped like them, not status text.
                if (check.running) item(key = "placeholders") { ReadSkeletonRows(3, Modifier.testTag("manga:updates:placeholders")) }
                if (days.isEmpty() && !check.running) item(key = "empty") {
                    if (online) ReadEmpty(mangaText("No new chapters"), mangaText("Pull down, or use Check for updates, to look for new chapters of your series."))
                    else ReadEmpty(mangaText("Nothing to check yet"), mangaText("Updates appear for the series you add to your library from online sources."))
                }
                if (bySeries) series.forEach { (book, entries) ->
                    item(key = "series:${book.id}") {
                        Row(Modifier.fillMaxWidth().padding(end = ReadMargin - 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReadOverline(book.title + " · ${entries.size} " + mangaText("new"), Modifier.weight(1f))
                            ReadTextButton(mangaText("Mark all read"), {
                                scope.action { entries.forEach { entry -> scope.repository.store.markRead(entry.chapter, true) } }
                            }, Modifier.padding(top = 16.dp))
                        }
                    }
                    items(entries, key = { "update:${it.chapter.id}" }) { entry -> MangaUpdateRow(scope, entry) }
                }
                if (!bySeries) days.forEach { day ->
                    item(key = "day:${day.label}") {
                        Row(Modifier.fillMaxWidth().padding(end = ReadMargin - 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReadOverline(mangaText(day.label) + " · ${day.entries.size} " + mangaText("new"), Modifier.weight(1f))
                            ReadTextButton(mangaText("Mark all read"), {
                                scope.action { day.entries.forEach { entry -> scope.repository.store.markRead(entry.chapter, true) } }
                            }, Modifier.padding(top = 16.dp).testTag("manga:updates:read:${day.label}"))
                        }
                    }
                    items(day.entries, key = { "update:${it.chapter.id}" }) { entry -> MangaUpdateRow(scope, entry) }
                }
                if (upcoming.isNotEmpty()) {
                    item(key = "upcoming") { ReadOverline(mangaText("Upcoming"), Modifier.padding(top = 8.dp)) }
                    items(upcoming, key = { "upcoming:${it.first.id}" }) { (book, at) ->
                        val cover = rememberMangaCover(scope.repository, book)
                        ReadCoverRow(book.title, expectedLabel(at), cover, key = book.id, modifier = Modifier.padding(horizontal = ReadMargin).testTag("manga:upcoming:${book.id}"),
                            onClick = { scope.openBook(book) })
                    }
                }
            }
        }
    }
}

/** History (in Library): the last chapter read in each series, by day. Tap goes back to it; the cross takes a series out, with Undo. */
@Composable
internal fun MangaHistoryPage(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val list = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    val days = mangaHistory(state).mapNotNull { day -> day.copy(entries = day.entries.filter { mangaHistoryMatches(it, query) }).takeIf { it.entries.isNotEmpty() } }
    val undo = LocalOrbitUndo.current
    val time = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("manga:history:list"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "heading") {
                ReadPageHead(mangaText("History"), mangaText("The last chapter of each series you read")) { MangaRootActions(scope) }
            }
            if (state.progress.isNotEmpty()) item(key = "filter") {
                ReadSearchField(query, { query = it.take(200) }, mangaText("Filter history"), Modifier.padding(horizontal = ReadMargin).testTag("manga:history:filter"))
            }
            if (days.isEmpty()) item(key = "empty") {
                if (query.isNotBlank()) ReadEmpty(mangaText("No match"), mangaText("No series in your history matches this."))
                else ReadEmpty(mangaText("Nothing read yet"), mangaText("The series you read appear here, the latest first."))
            }
            days.forEach { day ->
                item(key = "day:${day.label}") { ReadOverline(day.label) }
                items(day.entries, key = { "history:${it.book.id}" }) { entry ->
                    val cover = rememberMangaCover(scope.repository, entry.book)
                    val at = time.format(Instant.ofEpochMilli(entry.progress.updatedAtMs).atZone(ZoneId.systemDefault()))
                    val position = entry.progress.takeIf { it.pageCount > 0 && !it.finished }?.let { "page ${it.page + 1} of ${it.pageCount}" } ?: if (entry.progress.finished) "finished" else null
                    ReadCoverRow(entry.book.title, listOfNotNull(entry.chapter?.title, position, at).joinToString(" · "), cover, key = entry.book.id,
                        modifier = Modifier.padding(horizontal = ReadMargin),
                        onClick = {
                            val chapter = entry.chapter
                            if (chapter != null) { scope.navigation.book = entry.book; scope.navigation.chapter = chapter } else scope.openBook(entry.book)
                        },
                        trailing = {
                            // Gone at once, with "Removed · Undo" putting the place back.
                            ReadIconButton(CosmoIcons.Close, mangaText("Remove from history"), {
                                val id = entry.book.id
                                val place = entry.progress
                                scope.action {
                                    scope.repository.store.update { it.copy(progress = it.progress - id) }
                                    undo.removedThen(entry.book.title) {
                                        scope.repository.store.update { old -> if (id in old.progress || old.books.none { it.id == id }) old else old.copy(progress = old.progress + (id to place)) }
                                    }
                                }
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
    val undo = LocalOrbitUndo.current
    val queue by scope.repository.downloads.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ReadBackHead("Library", onBack = { scope.navigation.page = null })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "heading") {
                val count = groups.sumOf { it.second.size }
                ReadPageHead(mangaText("Downloads"), if (count == 1) mangaText("1 chapter on this device") else "$count " + mangaText("chapters on this device"))
            }
            if (queue.items.isNotEmpty()) {
                item(key = "queue:head") { Column {
                    ReadSection(mangaText("Queue"), subtitle = mangaQueueLine(queue.items, queue))
                    ReadChipRow(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        ReadPill(mangaText(if (queue.paused) "Resume" else "Pause"), icon = CosmoIcons.Download, onClick = {
                            if (queue.paused) scope.repository.downloads.resume() else scope.repository.downloads.pause()
                        }, modifier = Modifier.testTag("manga:queue:pause"))
                        ReadPill(mangaText("Cancel all"), icon = CosmoIcons.Close, onClick = { scope.repository.downloads.cancelAll() })
                    }
                } }
                items(queue.items, key = { "queue:${it.chapter.id}" }) { entry ->
                    val line = entry.error ?: if (entry.chapter.id == queue.running && entry.total > 0) "${entry.done} of ${entry.total} pages" else if (entry.chapter.id == queue.running) mangaText("Starting…") else mangaText("Waiting")
                    ReadChapterRow(
                        title = entry.book.title + " · " + entry.chapter.title, meta = line,
                        read = false, fresh = false, download = if (entry.error != null) ReadDownload.AVAILABLE else ReadDownload.RUNNING,
                        onClick = { scope.openBook(entry.book) }, onLongClick = { scope.openBook(entry.book) },
                        onDownload = { if (entry.error != null) scope.repository.downloads.retry(entry.chapter.id) else scope.repository.downloads.cancel(entry.chapter.id) },
                        modifier = Modifier.padding(horizontal = ReadMargin).testTag("manga:queue:${entry.chapter.id}"),
                    )
                }
                item(key = "queue:gap") { Spacer(Modifier.height(16.dp)) }
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
                        onDownload = { mangaRemoveDownloads(scope, undo, listOf(chapter), chapter.title) },
                        modifier = Modifier.padding(horizontal = ReadMargin),
                    )
                }
            }
        }
    }
}
