package com.cosmotune.manga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitEmptyState
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitPill
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.kit.collapseFraction
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

/**
 * A series (DESIGN_SYSTEM.md 6.4, "Series details"): the cover-washed header (cover, title, authors, status and source pills),
 * the one filled button that continues the series, the library and download actions in one row, the description and the
 * genres, then the chapters as the page's body with their edition, unread and order filters. From 840 dp the header stands in a
 * column beside the chapters. Long-press a chapter for its actions (read state, download, mark the earlier ones read).
 */
@Composable
internal fun MangaDetails(scope: MangaScope, book: MangaBook, onRefresh: () -> Unit, onRead: (MangaChapter) -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val list = rememberLazyListState()
    val threshold = rememberCollapseThreshold()
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 840.dp
        if (twoPane) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(400.dp).fillMaxHeight()) {
                ReadCoverWash(cover)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp, bottom = scope.bottom)) {
                    SeriesHeader(scope, book, cover, saved, next, onRead)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("manga:chapters"), state = list,
                contentPadding = PaddingValues(top = 64.dp, bottom = scope.bottom)) {
                item(key = "spacer") { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars)) }
                chapterItems(scope, book, listed, filters, next.chapter, onRead) { chapterActions = it }
            }
        } else LazyColumn(Modifier.fillMaxSize().testTag("manga:chapters"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "series:header") {
                Box {
                    ReadCoverWash(cover)
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp)) {
                        SeriesHeader(scope, book, cover, saved, next, onRead)
                    }
                }
            }
            chapterItems(scope, book, listed, filters, next.chapter, onRead) { chapterActions = it }
        }
        ReadTopBar(book.title, collapse = { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold * 4f) }, large = false,
            onBack = { scope.navigation.book = null }, backLabel = mangaText("Back"), actions = {
                KitIconButton(CosmoIcons.Replay, mangaText("Refresh chapters"), onRefresh)
                KitIconButton(CosmoIcons.MoreVertical, mangaText("More"), { more = true })
            })
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

@Composable
private fun SeriesHeader(scope: MangaScope, book: MangaBook, cover: Any?, saved: Boolean, next: MangaContinue, onRead: (MangaChapter) -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val sourceName = state.sources.find { it.id == book.sourceId }?.name ?: if (book.sourceId == "local") mangaText("On this device") else book.sourceId
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 8.dp)) {
            ReadCover(cover, book.title, Modifier.width(120.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(book.title, style = type.forText(ReadType.headline(), book.title), color = Color(palette.textPrimary), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                if (book.authors.isNotEmpty()) Text(book.authors.joinToString(" · "), style = ReadType.body(), color = Color(palette.textSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    book.parserData?.state?.takeIf { it.isNotBlank() }?.let { status -> ReadMetaPill(status.lowercase().replaceFirstChar(Char::uppercase)) }
                    ReadMetaPill(listOf(sourceName, book.language).filter(String::isNotBlank).joinToString(" · "))
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ReadMargin, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val target = next.chapter
            KitButton(mangaText(next.label), onClick = { if (target != null) onRead(target) else (scope.openBook)(book) }, icon = CosmoIcons.Play,
                modifier = Modifier.testTag("manga:continue"))
            if (!saved) KitButton(mangaText("Add to library"), { scope.action { repository.store.save(book) } }, kind = KitButtonKind.TONAL, icon = CosmoIcons.Heart)
            else MangaCollectionAssignment(repository, book.id)
            val pending = if (book.sourceId == "local") emptyList() else book.listedChapters().filter { it.id !in state.readChapters && !state.downloads.containsKey(it.id) }
            if (pending.isNotEmpty()) KitButton(if (pending.size == 1) "Download 1 unread chapter" else "Download ${pending.size} unread chapters",
                { mangaDownload(scope, book, pending) }, kind = KitButtonKind.TONAL, icon = CosmoIcons.Download, enabled = !scope.transfer.active)
        }
        if (scope.transfer.active) Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalAlignment = Alignment.CenterVertically) {
            Text(scope.transfer.progress, style = ReadType.meta(), color = Color(palette.textSecondary), modifier = Modifier.weight(1f))
            KitTextButton(mangaText("Cancel download"), { scope.transfer.job?.cancel() })
        }
        if (book.description.isNotBlank()) {
            var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
            val text = remember(book.description) { org.jsoup.Jsoup.parse(book.description).text() }
            Text(text, style = ReadType.body(), color = Color(palette.textSecondary), maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = mangaText(if (expanded) "Show less" else "Show more")) { expanded = !expanded }.padding(horizontal = ReadMargin, vertical = 8.dp))
        }
        if (book.tags.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ReadMargin, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            book.tags.take(12).forEach { tag -> KitPill(tag) }
        }
    }
}

/** A neutral metadata pill (DESIGN_SYSTEM.md 4.11): 20 dp, `surface2`, caption text. */
@Composable
internal fun ReadMetaPill(text: String) {
    if (text.isBlank()) return
    val palette = LocalCosmoColors.current.palette
    Text(text, style = ReadType.caption().copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.height(22.dp).clip(ReadShape.full).background(Color(palette.surfaceVariant)).padding(horizontal = 8.dp, vertical = 3.dp))
}

private fun LazyListScope.chapterItems(scope: MangaScope, book: MangaBook, listed: List<MangaChapter>, filters: ChapterFilters, current: MangaChapter?,
    onRead: (MangaChapter) -> Unit, onActions: (MangaChapter) -> Unit) {
    item(key = "chapters:controls") {
        val state by scope.repository.store.state.collectAsState()
        val palette = LocalCosmoColors.current.palette
        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (book.chapters.isEmpty()) KitEmptyState(mangaText("No chapters yet"), mangaText("Refresh chapters to load this series."), icon = CosmoIcons.List)
            else {
                Text("${listed.size} of ${book.chapters.size} chapters · ${book.chapters.count { it.id !in state.readChapters }} unread",
                    style = ReadType.meta(), color = Color(palette.textSecondary), modifier = Modifier.padding(horizontal = ReadMargin))
                if (filters.editions.size > 1) ReadChipRow(Modifier.testTag("manga:editions")) {
                    ReadChip(mangaText("All editions"), filters.edition.isBlank(), { filters.onEdition("") })
                    filters.editions.forEach { item -> ReadChip(item, filters.edition == item, { filters.onEdition(item) }) }
                }
                ReadChipRow {
                    ReadChip(mangaText("Unread only"), filters.unreadOnly, { filters.onUnreadOnly(!filters.unreadOnly) })
                    KitPill(mangaText(if (filters.newestFirst) "Newest first" else "Oldest first"), icon = CosmoIcons.Sort, onClick = { filters.onNewestFirst(!filters.newestFirst) })
                }
                if (listed.isEmpty()) Text(mangaText(if (filters.unreadOnly) "Every chapter here is read." else "No chapters in this edition."),
                    style = ReadType.body(), color = Color(palette.textSecondary), modifier = Modifier.padding(horizontal = ReadMargin, vertical = 12.dp))
            }
        }
    }
    items(listed, key = { it.id }) { item ->
        val state by scope.repository.store.state.collectAsState()
        val read = item.id in state.readChapters
        val fresh = item.id in state.newChapters
        val meta = chapterSubtitle(item, state.progress[book.id], read)
        ReadChapterRow(
            title = item.title,
            meta = if (fresh) listOf(mangaText("New"), meta).filter(String::isNotBlank).joinToString(" · ") else meta,
            read = read,
            fresh = fresh,
            download = mangaDownloadState(scope, item, state),
            onClick = { onRead(item) },
            onLongClick = { onActions(item) },
            onDownload = {
                if (state.downloads.containsKey(item.id)) scope.action { scope.repository.removeDownload(item.id) } else mangaDownload(scope, book, listOf(item))
            },
            current = current?.id == item.id,
            modifier = Modifier.testTag("manga:chapter:${item.id}"),
        )
    }
}
