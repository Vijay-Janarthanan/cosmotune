package com.cosmotune.manga.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitCardChip
import com.cosmotune.ui.orbit.OrbitCoverTitleCard
import com.cosmotune.ui.orbit.OrbitPageWash
import com.cosmotune.ui.orbit.WatchBadge
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * A series (board D, the title page): the colour card in the cover's colour (the tall cover, "MANGA · 142 CHAPTERS · ONGOING",
 * the title, the next chapter, the Library and Download chips, Continue in the bite), the description and the genres, then the
 * chapters as Orbit rows with their edition, unread and order filters; the next chapter is the peach row. Hold a chapter for its
 * actions (read state, download, mark the earlier ones read). The page wears the card's colour as a wash at its top.
 */
@Composable
internal fun MangaDetails(scope: MangaScope, book: MangaBook, onRefresh: () -> Unit, onRead: (MangaChapter) -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val list = rememberLazyListState()
    var more by remember { mutableStateOf(false) }
    var chapterActions by remember { mutableStateOf<MangaChapter?>(null) }
    val editions = book.editions()
    var edition by rememberSaveable(book.id) { mutableStateOf("") }
    var newestFirst by rememberSaveable(book.id) { mutableStateOf(false) }
    var unreadOnly by rememberSaveable(book.id) { mutableStateOf(false) }
    if (edition.isNotBlank() && edition !in editions) edition = ""
    val listed = book.listedChapters(edition, newestFirst, unreadOnly, state.readChapters)
    val cover = rememberMangaCover(repository, book)
    val saved = state.books.any { it.id == book.id }
    val next = mangaContinue(book, state)
    val filters = ChapterFilters(editions, edition, { edition = it }, newestFirst, { newestFirst = it }, unreadOnly, { unreadOnly = it })
    val (fill, _) = coverFill(book.colourUrl(), book.id)
    Box(Modifier.fillMaxSize()) {
        OrbitPageWash(fill)
        LazyColumn(Modifier.fillMaxSize().testTag("manga:chapters"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "series:head") {
                ReadBackHead("Series", onBack = { scope.navigation.book = null }, actions = {
                    ReadIconButton(CosmoIcons.Replay, "Refresh chapters", onRefresh)
                    ReadIconButton(CosmoIcons.MoreVertical, "More", { more = true })
                })
            }
            item(key = "series:card") { SeriesCard(scope, book, cover, saved, next, onRead) }
            item(key = "series:about") { SeriesAbout(scope, book) }
            chapterItems(scope, book, listed, filters, next.chapter, onRead) { chapterActions = it }
        }
    }
    if (more) KitSheet(onDismiss = { more = false }, title = book.title) {
        ReadSheetRow(mangaText("Refresh chapters"), CosmoIcons.Replay, { more = false; onRefresh() })
        val website = book.parserData?.publicUrl?.takeIf { it.isNotBlank() } ?: book.url.takeIf { it.startsWith("http") }
        if (website != null) {
            val uri = LocalUriHandler.current
            ReadSheetRow(mangaText("Open website"), Icons.AutoMirrored.Rounded.OpenInNew, { more = false; runCatching { uri.openUri(website) }.onFailure { scope.onError("This website could not be opened") } })
        }
        if (saved) ReadSheetRow(mangaText("Remove from library"), CosmoIcons.Close, enabled = !scope.transfer.active, onClick = {
            more = false; scope.action { repository.removeBook(book.id); scope.navigation.book = null }
        })
        Spacer(Modifier.height(16.dp))
    }
    chapterActions?.let { chapter ->
        val read = chapter.id in state.readChapters
        KitSheet(onDismiss = { chapterActions = null }, title = chapter.title) {
            ReadSheetRow(mangaText(if (read) "Mark unread" else "Mark read"), CosmoIcons.Check, {
                chapterActions = null; scope.action { repository.store.save(book); repository.store.markRead(chapter, !read) }
            })
            val earlier = book.listedChapters(chapter.edition).takeWhile { it.id != chapter.id }.filter { it.id !in state.readChapters }
            if (earlier.isNotEmpty()) ReadSheetRow(mangaText("Mark earlier chapters read"), CosmoIcons.List, {
                chapterActions = null; scope.action { repository.store.save(book); earlier.forEach { repository.store.markRead(it, true) } }
            })
            when (mangaDownloadState(scope, chapter, state)) {
                ReadDownload.AVAILABLE -> ReadSheetRow(mangaText("Download"), CosmoIcons.Download, { chapterActions = null; mangaDownload(scope, book, listOf(chapter)) })
                ReadDownload.DONE -> ReadSheetRow(mangaText("Remove download"), CosmoIcons.Downloaded, { chapterActions = null; scope.action { repository.removeDownload(chapter.id) } })
                else -> {}
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private class ChapterFilters(
    val editions: List<String>, val edition: String, val onEdition: (String) -> Unit,
    val newestFirst: Boolean, val onNewestFirst: (Boolean) -> Unit,
    val unreadOnly: Boolean, val onUnreadOnly: (Boolean) -> Unit,
)

/** The series' colour card: Continue in the bite, the Library chip (the collections once saved) and Download for the unread chapters. */
@Composable
private fun SeriesCard(scope: MangaScope, book: MangaBook, cover: Any?, saved: Boolean, next: MangaContinue, onRead: (MangaChapter) -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val fonts = orbitFonts()
    val sourceName = state.sources.find { it.id == book.sourceId }?.name ?: if (book.sourceId == "local") mangaText("On this device") else book.sourceId
    val target = next.chapter
    OrbitCoverTitleCard(
        cover = cover, colourUrl = book.colourUrl(), key = book.id, label = mangaCardLabel(book), title = book.title,
        line = target?.let { mangaText(if (next.resumes) "Next" else "Start") + ": " + it.title } ?: mangaText(next.label),
        onContinue = { if (target != null) onRead(target) else scope.openBook(book) },
        modifier = Modifier.padding(horizontal = ReadMargin).padding(top = 4.dp).testTag("manga:continue"),
        below = { colors ->
            if (book.authors.isNotEmpty()) Text(book.authors.joinToString(" · "), style = fonts.body, color = colors.ink.copy(alpha = 0.7f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            val where = listOf(sourceName, book.language).filter(String::isNotBlank).joinToString(" · ")
            if (where.isNotBlank()) WatchBadge(where, fonts, colors.ink, Modifier.padding(top = 10.dp))
        },
        chips = { colors ->
            if (!saved) OrbitCardChip(mangaText("Library"), colors, onClick = { scope.action { repository.store.save(book) } }, icon = CosmoIcons.Heart)
            else MangaCollectionAssignment(repository, book.id, colors)
            val pending = if (book.sourceId == "local") emptyList() else book.listedChapters().filter { it.id !in state.readChapters && !state.downloads.containsKey(it.id) }
            if (pending.isNotEmpty() && !scope.transfer.active) OrbitCardChip(mangaText("Download") + if (pending.size > 1) " · ${pending.size}" else "", colors,
                onClick = { mangaDownload(scope, book, pending) }, icon = CosmoIcons.Download)
        },
    )
}

/** Under the card: a running download, the description (a tap shows it all) and the genres. */
@Composable
private fun SeriesAbout(scope: MangaScope, book: MangaBook) {
    val look = orbitLook()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scope.transfer.active) Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalAlignment = Alignment.CenterVertically) {
            Text(scope.transfer.progress, style = ReadType.meta(), color = look.dim, modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp))
            ReadTextButton(mangaText("Cancel download"), { scope.transfer.job?.cancel() })
        }
        if (book.description.isNotBlank()) {
            var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
            val text = remember(book.description) { org.jsoup.Jsoup.parse(book.description).text() }
            Text(text, style = ReadType.body(), color = look.dim, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).clickable(onClickLabel = mangaText(if (expanded) "Show less" else "Show more")) { expanded = !expanded }
                    .padding(horizontal = ReadMargin + 4.dp, vertical = 4.dp))
        }
        if (book.tags.isNotEmpty()) ReadChipRow { book.tags.take(12).forEach { tag -> ReadPill(tag) } }
    }
}

private fun LazyListScope.chapterItems(scope: MangaScope, book: MangaBook, listed: List<MangaChapter>, filters: ChapterFilters, current: MangaChapter?,
    onRead: (MangaChapter) -> Unit, onActions: (MangaChapter) -> Unit) {
    item(key = "chapters:controls") {
        val state by scope.repository.store.state.collectAsState()
        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (book.chapters.isEmpty()) {
                ReadSection("Chapters")
                ReadEmpty("No chapters yet", "Refresh chapters to load this series.")
            } else {
                ReadSection("Chapters", subtitle = "${listed.size} of ${book.chapters.size} chapters · ${book.chapters.count { it.id !in state.readChapters }} unread")
                if (filters.editions.size > 1) ReadChipRow(Modifier.testTag("manga:editions")) {
                    ReadChip(mangaText("All editions"), filters.edition.isBlank(), { filters.onEdition("") })
                    filters.editions.forEach { item -> ReadChip(item, filters.edition == item, { filters.onEdition(item) }) }
                }
                ReadChipRow {
                    ReadChip(mangaText("Unread only"), filters.unreadOnly, { filters.onUnreadOnly(!filters.unreadOnly) })
                    ReadPill(mangaText(if (filters.newestFirst) "Newest first" else "Oldest first"), icon = CosmoIcons.Sort, onClick = { filters.onNewestFirst(!filters.newestFirst) })
                }
                if (listed.isEmpty()) Text(mangaText(if (filters.unreadOnly) "Every chapter here is read." else "No chapters in this edition."),
                    style = ReadType.body(), color = orbitLook().dim, modifier = Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 12.dp))
            }
        }
    }
    items(listed, key = { it.id }) { item ->
        val state by scope.repository.store.state.collectAsState()
        val read = item.id in state.readChapters
        val fresh = item.id in state.newChapters
        val isNext = current?.id == item.id
        val meta = chapterSubtitle(item, state.progress[book.id], read)
        val line = listOfNotNull(mangaText("Next").takeIf { isNext && meta.isBlank() }, mangaText("New").takeIf { fresh }, meta.ifBlank { null }).joinToString(" · ")
        ReadChapterRow(
            title = item.title,
            meta = line,
            read = read,
            fresh = fresh,
            download = mangaDownloadState(scope, item, state),
            onClick = { onRead(item) },
            onLongClick = { onActions(item) },
            onDownload = {
                if (state.downloads.containsKey(item.id)) scope.action { scope.repository.removeDownload(item.id) } else mangaDownload(scope, book, listOf(item))
            },
            next = isNext,
            modifier = Modifier.padding(horizontal = ReadMargin, vertical = 4.dp).testTag("manga:chapter:${item.id}"),
        )
    }
}
