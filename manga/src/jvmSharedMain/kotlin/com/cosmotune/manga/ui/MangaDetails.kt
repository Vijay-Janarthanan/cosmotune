@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.cosmotune.manga.ui

import com.cosmotune.ui.orbit.LocalOrbitUndo
import com.cosmotune.ui.orbit.OrbitUndoState
import com.cosmotune.ui.orbit.showThen
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Edit
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
    var migrate by remember(book.id) { mutableStateOf(false) }
    var editing by remember(book.id) { mutableStateOf(false) }
    var noting by remember(book.id) { mutableStateOf(false) }
    val readOrFind: (MangaChapter) -> Unit = { chapter -> if (book.unlinkedSourceName() != null) migrate = true else onRead(chapter) }
    // A hold on a chapter starts choosing several (Mihon's selection): mark read or unread, download, remove downloads.
    var selection by remember(book.id) { mutableStateOf(emptySet<String>()) }
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
        LazyColumn(Modifier.fillMaxSize().readScrollbar(list.scrollIndicatorState, bottomInset = scope.bottom).testTag("manga:chapters"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "series:head") {
                ReadBackHead("Series", onBack = { scope.navigation.book = null }, actions = {
                    ReadIconButton(CosmoIcons.Replay, "Refresh chapters", onRefresh)
                    ReadIconButton(CosmoIcons.MoreVertical, "More", { more = true })
                })
            }
            book.unlinkedSourceName()?.let { from -> item(key = "series:unlinked") {
                ReadInlineBanner("From your Mihon backup ($from). Find it on one of your sources to read it; your read chapters come along.",
                    Modifier.padding(bottom = 8.dp), actionLabel = mangaText("Find it"), onAction = { migrate = true })
            } }
            item(key = "series:card") { SeriesCard(scope, book, cover, saved, next, readOrFind) }
            item(key = "series:about") { SeriesAbout(scope, book) }
            item(key = "series:related") { SeriesRelated(scope, book) }
            chapterItems(scope, book, listed, filters, next.chapter, selection, onRead = { chapter ->
                if (selection.isEmpty()) readOrFind(chapter) else selection = selection.toggle(chapter.id)
            }) { chapter -> selection = selection.toggle(chapter.id) }
        }
        if (selection.isNotEmpty()) SelectionBar(scope, book, listed, selection, { selection = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = scope.bottom))
    }
    MangaBackHandler(selection.isNotEmpty()) { selection = emptySet() }
    if (migrate) MangaMigrateSheet(scope, book) { migrate = false }
    if (noting) KitSheet(onDismiss = { noting = false }) {
        var note by remember { mutableStateOf(state.notes[book.id].orEmpty()) }
        ReadSheetHeader(mangaText("Note"), onDone = { noting = false; scope.action {
            repository.store.update { old -> old.copy(notes = note.trim().take(4_000).let { if (it.isBlank()) old.notes - book.id else old.notes + (book.id to it) }) }
        } }, doneLabel = "Save")
        Column(Modifier.padding(horizontal = 20.dp)) {
            com.cosmotune.ui.orbit.OrbitInputField(note, { note = it.take(4_000) }, mangaText("Your note on this series"), modifier = Modifier.fillMaxWidth().testTag("manga:note:field"))
        }
        Spacer(Modifier.height(16.dp))
    }
    if (editing) KitSheet(onDismiss = { editing = false }) {
        var title by remember { mutableStateOf(state.books.find { it.id == book.id }?.title ?: book.title) }
        ReadSheetHeader(mangaText("Edit title"), onDone = { editing = false; scope.action { repository.store.retitle(book.id, title) } }, doneLabel = "Save")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.cosmotune.ui.orbit.OrbitInputField(title, { title = it.take(300) }, mangaText("Title"), modifier = Modifier.fillMaxWidth().testTag("manga:edit:title"))
            Text(mangaText("Kept when the series refreshes. Clear it to use the source's title again."), style = ReadType.meta(), color = orbitLook().dim)
        }
        Spacer(Modifier.height(16.dp))
    }
    if (more) KitSheet(onDismiss = { more = false }, title = book.title) {
        ReadSheetRow(mangaText("Refresh chapters"), CosmoIcons.Replay, { more = false; onRefresh() })
        val website = book.parserData?.publicUrl?.takeIf { it.isNotBlank() } ?: book.url.takeIf { it.startsWith("http") }
        if (website != null) {
            val uri = LocalUriHandler.current
            ReadSheetRow(mangaText("Open website"), Icons.AutoMirrored.Rounded.OpenInNew, { more = false; runCatching { uri.openUri(website) }.onFailure { scope.onError("This website could not be opened") } })
        }
        if (!saved && book.sourceId != "local") {
            val undo = LocalOrbitUndo.current
            val words = mangaText("Not suggested again")
            ReadSheetRow(mangaText("Not interested"), CosmoIcons.Close, { more = false; scope.action {
                repository.store.update { it.copy(notInterested = (it.notInterested + book.id).let { set -> if (set.size > 5_000) set.drop(set.size - 5_000).toSet() else set }) }
                scope.navigation.book = null
                undo.showThen(words) { repository.store.update { it.copy(notInterested = it.notInterested - book.id) } }
            } }, Modifier.testTag("manga:notinterested"))
        }
        val share = rememberMangaShare { scope.onError(it) }
        if (website != null) ReadSheetRow(mangaText("Share series"), CosmoIcons.Share, { more = false; share.shareLink(book.title, website) }, Modifier.testTag("manga:share"))
        if (saved) ReadSheetRow(mangaText(if (state.notes.containsKey(book.id)) "Edit note" else "Add note"), CosmoIcons.Plus, { more = false; noting = true }, Modifier.testTag("manga:note:edit"))
        if (saved) ReadSheetRow(mangaText("Edit title"), Icons.Rounded.Edit, { more = false; editing = true }, Modifier.testTag("manga:edit"))
        if (saved && state.customCovers.containsKey(book.id)) ReadSheetRow(mangaText("Use the source's cover"), CosmoIcons.Replay, { more = false; scope.action { repository.store.cover(book.id, null) } })
        if (book.sourceId != "local") ReadSheetRow(mangaText("Find on another source"), CosmoIcons.Search, { more = false; migrate = true }, Modifier.testTag("manga:migrate:open"))
        if (saved) ReadSheetRow(mangaText("Remove from library"), CosmoIcons.Close, onClick = {
            more = false; scope.action {
                book.chapters.forEach { repository.downloads.cancel(it.id) }
                repository.removeBook(book.id); scope.navigation.book = null
            }
        })
        Spacer(Modifier.height(16.dp))
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

/**
 * What can be done with the chosen chapters, along the foot of the page: how many, mark read (or unread when all are read), download
 * the ones not on the device, remove the downloaded ones, mark the earlier chapters read (one chosen), choose all, and close.
 */
@Composable
private fun SelectionBar(scope: MangaScope, book: MangaBook, listed: List<MangaChapter>, selection: Set<String>, onSelection: (Set<String>) -> Unit, modifier: Modifier) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val look = orbitLook()
    val chosen = book.chapters.filter { it.id in selection }
    val undo = LocalOrbitUndo.current
    val markWords = if (chosen.size == 1) mangaText(if (chosen.all { it.id in state.readChapters }) "Marked unread" else "Marked read")
        else "${chosen.size} " + mangaText(if (chosen.all { it.id in state.readChapters }) "chapters marked unread" else "chapters marked read")
    val downloadsWord = mangaText("downloads")
    val allRead = chosen.all { it.id in state.readChapters }
    val toDownload = chosen.filter { it.sourceId != "local" && !state.downloads.containsKey(it.id) }
    val downloaded = chosen.filter { state.downloads.containsKey(it.id) }
    Column(modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 8.dp).clip(ReadShape.md).background(look.ground)
        .border(1.dp, look.ink.copy(alpha = 0.10f), ReadShape.md).padding(horizontal = 12.dp, vertical = 10.dp).testTag("manga:selection")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (chosen.size == 1) mangaText("1 chapter chosen") else "${chosen.size} " + mangaText("chapters chosen"), style = ReadType.rowTitle(), color = look.ink,
                modifier = Modifier.weight(1f).padding(start = 6.dp))
            if (selection.size < listed.size) ReadTextButton(mangaText("All"), { onSelection(listed.map { it.id }.toSet()) })
            Spacer(Modifier.width(6.dp))
            ReadIconButton(CosmoIcons.Close, "Stop choosing", { onSelection(emptySet()) })
        }
        ReadChipRow(Modifier.padding(top = 8.dp), inset = 0.dp) {
            ReadPill(mangaText(if (allRead) "Mark unread" else "Mark read"), icon = CosmoIcons.Check, onClick = {
                onSelection(emptySet())
                markWithUndo(scope, undo, book, chosen, !allRead, state, markWords)
            }, modifier = Modifier.testTag("manga:selection:read"))
            if (toDownload.isNotEmpty()) ReadPill(mangaText("Download") + " · ${toDownload.size}", icon = CosmoIcons.Download, onClick = {
                onSelection(emptySet()); mangaDownload(scope, book, toDownload)
            }, modifier = Modifier.testTag("manga:selection:download"))
            if (downloaded.isNotEmpty()) ReadPill(mangaText("Remove downloads") + " · ${downloaded.size}", icon = CosmoIcons.Close, onClick = {
                onSelection(emptySet())
                mangaRemoveDownloads(scope, undo, downloaded, downloaded.singleOrNull()?.title ?: "${downloaded.size} $downloadsWord")
            })
            val allMarked = chosen.all { it.id in state.bookmarks }
            ReadPill(mangaText(if (allMarked) "Remove bookmark" else "Bookmark"), icon = CosmoIcons.Heart, onClick = {
                onSelection(emptySet())
                scope.action { repository.store.update { old -> old.copy(bookmarks = if (allMarked) old.bookmarks - chosen.map { it.id }.toSet() else old.bookmarks + chosen.map { it.id }) } }
            }, modifier = Modifier.testTag("manga:selection:bookmark"))
            chosen.singleOrNull()?.let { only ->
                val earlier = book.listedChapters(only.edition).takeWhile { it.id != only.id }.filter { it.id !in state.readChapters }
                val earlierWords = "${earlier.size} " + mangaText("chapters marked read")
                if (earlier.isNotEmpty()) ReadPill(mangaText("Mark earlier read"), icon = CosmoIcons.List, onClick = {
                    onSelection(emptySet())
                    markWithUndo(scope, undo, book, earlier, true, state, earlierWords)
                })
            }
        }
    }
}

/**
 * Marks [chapters] read (or unread, [read] false) with "3 chapters marked read · Undo": Undo puts back each one's read mark, whether
 * it was new, and the series' place as they were.
 */
private fun markWithUndo(scope: MangaScope, undo: OrbitUndoState?, book: MangaBook, chapters: List<MangaChapter>, read: Boolean, state: MangaLibraryState, words: String) {
    if (chapters.isEmpty()) return
    val ids = chapters.map { it.id }.toSet()
    val wasRead = ids.filter { it in state.readChapters }.toSet()
    val wasNew = ids.filter { it in state.newChapters }.toSet()
    val place = state.progress[book.id]
    val store = scope.repository.store
    scope.action {
        store.save(book)
        chapters.forEach { store.markRead(it, read) }
        undo.showThen(words) {
            store.update { old ->
                old.copy(readChapters = old.readChapters - ids + wasRead, newChapters = old.newChapters + wasNew,
                    progress = if (place != null) old.progress + (book.id to place) else old.progress)
            }
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
            if (book.sourceId != "local") MangaTrackChip(scope, book, colors)
            val queue by repository.downloads.state.collectAsState()
            val pending = if (book.sourceId == "local") emptyList() else book.listedChapters().filter { it.id !in state.readChapters && !state.downloads.containsKey(it.id) && !queue.has(it.id) }
            if (pending.isNotEmpty()) OrbitCardChip(mangaText("Download") + if (pending.size > 1) " · ${pending.size}" else "", colors,
                onClick = { mangaDownload(scope, book, pending) }, icon = CosmoIcons.Download)
        },
    )
}

/** Under the card: a running download, the description (a tap shows it all) and the genres. */
@Composable
private fun SeriesAbout(scope: MangaScope, book: MangaBook) {
    val look = orbitLook()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val queue by scope.repository.downloads.state.collectAsState()
        val mine = queue.items.filter { it.book.id == book.id }
        if (mine.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalAlignment = Alignment.CenterVertically) {
            Text(mangaQueueLine(mine, queue), style = ReadType.meta(), color = look.dim, modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp))
            ReadTextButton(mangaText("Cancel download"), { mine.forEach { scope.repository.downloads.cancel(it.chapter.id) } })
        }
        if (book.description.isNotBlank()) {
            var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
            val text = remember(book.description) { org.jsoup.Jsoup.parse(book.description).text() }
            Text(text, style = ReadType.body(), color = look.dim, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).clickable(onClickLabel = mangaText(if (expanded) "Show less" else "Show more")) { expanded = !expanded }
                    .padding(horizontal = ReadMargin + 4.dp, vertical = 4.dp))
        }
        if (book.tags.isNotEmpty()) ReadChipRow { book.tags.take(12).forEach { tag -> ReadPill(tag) } }
        val state by scope.repository.store.state.collectAsState()
        state.notes[book.id]?.let { note ->
            Column(Modifier.fillMaxWidth().padding(horizontal = ReadMargin + 4.dp).testTag("manga:note"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mangaText("Your note"), style = ReadType.rowTitle(), color = look.ink)
                Text(note, style = ReadType.body(), color = look.dim)
            }
        }
    }
}

private fun LazyListScope.chapterItems(scope: MangaScope, book: MangaBook, listed: List<MangaChapter>, filters: ChapterFilters, current: MangaChapter?,
    selection: Set<String>, onRead: (MangaChapter) -> Unit, onSelect: (MangaChapter) -> Unit) {
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
    itemsIndexed(listed, key = { _, chapter -> chapter.id }) { index, item ->
        val state by scope.repository.store.state.collectAsState()
        val queue by scope.repository.downloads.state.collectAsState()
        val undo = LocalOrbitUndo.current
        val read = item.id in state.readChapters
        val fresh = item.id in state.newChapters
        val isNext = current?.id == item.id
        val meta = chapterSubtitle(item, state.progress[book.id], read)
        val line = listOfNotNull(mangaText("Next").takeIf { isNext && meta.isBlank() }, mangaText("New").takeIf { fresh }, mangaText("Bookmarked").takeIf { item.id in state.bookmarks },
            meta.ifBlank { null }).joinToString(" · ")
        ReadChapterRow(
            title = item.title,
            meta = line,
            read = read,
            fresh = fresh,
            download = mangaDownloadState(item, state, queue),
            onClick = { onRead(item) },
            onLongClick = { onSelect(item) },
            onDownload = {
                if (state.downloads.containsKey(item.id)) mangaRemoveDownloads(scope, undo, listOf(item), item.title) else mangaDownload(scope, book, listOf(item))
            },
            next = isNext,
            number = chapterNumber(item, index + 1),
            selected = if (selection.isEmpty()) null else item.id in selection,
            longClickLabel = "Choose chapters",
            modifier = Modifier.padding(horizontal = ReadMargin).testTag("manga:chapter:${item.id}"),
        )
    }
}

/** "Related": the series the source suggests beside this one (Komikku's suggestions), as a shelf of covers; nothing when it has none. */
@Composable
private fun SeriesRelated(scope: MangaScope, book: MangaBook) {
    var related by remember(book.id) { mutableStateOf(emptyList<MangaBook>()) }
    LaunchedEffect(book.id) {
        related = try { scope.repository.related(book) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
    }
    if (related.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("manga:related")) {
        ReadSection(mangaText("Related"))
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
            items(related, key = { "related:${it.id}" }) { item -> MangaBookCard(scope, item, null, width = 112.dp) }
        }
    }
}
