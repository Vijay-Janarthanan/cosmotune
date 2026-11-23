package com.cosmotune.reading.ui

import com.cosmotune.cache.CacheCodec
import com.cosmotune.cache.CachedLoad
import com.cosmotune.cache.PageCache
import com.cosmotune.cache.SLOW_FRESH_MS
import com.cosmotune.ui.states.collectOnPage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitCardChip
import com.cosmotune.ui.orbit.LocalOrbitUndo
import com.cosmotune.ui.orbit.OrbitCoverTitleCard
import com.cosmotune.ui.orbit.removeAfterUndo
import com.cosmotune.ui.orbit.removedThen
import com.cosmotune.ui.orbit.OrbitPageWash
import com.cosmotune.ui.orbit.OrbitRowCover
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.rememberUiCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import java.io.File

/**
 * The Read mode (books; Canvas repo, design/cosmotune/batch-be/board.html, section D): Library, Browse and Bookmarks as the page's
 * chips on a phone (the desktop's sidebar when [showNavigation] is false), a book's page and a catalogue entry's page pushed over
 * them, and the reader over everything. [repository] defaults to the reading library next to Manga's storage. Music keeps playing
 * underneath.
 */
@Composable
fun ReadApp(
    repository: ReadingRepository? = null,
    contentPadding: PaddingValues = PaddingValues(),
    onMusic: () -> Unit = {},
    showLeave: Boolean = false,
    onReaderVisibility: (Boolean) -> Unit = {},
    showNavigation: Boolean = true,
    showAccount: Boolean = true,
) {
    // A repository handed in (a test's) keeps its pages in memory of its own; the app's are kept for the process and on the disk.
    if (repository != null) return ReadContent(repository, remember(repository) { PageCache.of(repository, persistent = false) },
        contentPadding, onMusic.takeIf { showLeave }, onReaderVisibility, showNavigation, showAccount)
    var retry by remember { mutableIntStateOf(0) }
    val books = key(retry) { rememberReadingRepository() }
    val ready = books.getOrNull()
    if (ready == null) Column(Modifier.fillMaxSize().padding(contentPadding)) {
        ReadPageHead("Read", "Storage")
        ReadError(books.exceptionOrNull()?.message ?: "Check storage access and try again.", onRetry = { retry++ }, title = "Your reading library could not be opened")
    } else ReadContent(ready, PageCache.shared, contentPadding, onMusic.takeIf { showLeave }, onReaderVisibility, showNavigation, showAccount)
}

/** The reading library beside Manga's storage: one for the process (Read itself and Settings › Read share it). */
internal object ReadingLibrary {
    private var instance: ReadingRepository? = null
    @Synchronized fun of(manga: com.cosmotune.manga.data.MangaRepository): ReadingRepository = instance ?: ReadingRepository(
        File(manga.store.root.parentFile, "reading"), protection = manga.store.protection).also { instance = it }
}

@Composable
internal fun rememberReadingRepository(): Result<ReadingRepository> {
    val manga = rememberMangaRepository()
    return remember(manga) { runCatching { ReadingLibrary.of(manga.getOrThrow()) } }
}

/** Where Read is: its tab and what is pushed over it. */
internal class ReadNavigation(tab: String) {
    var tab by mutableStateOf(tab)
    var book by mutableStateOf<ReadingBook?>(null)
    var publication by mutableStateOf<ReadingPublication?>(null)
    var search by mutableStateOf(false)
    /** What Search's box holds (kept while a result's page stands over it), and its hold on the box. */
    var query by mutableStateOf("")
    val searchFocus = com.cosmotune.ui.search.CosmoSearchFocus()
    val atRoot: Boolean get() = book == null && publication == null && !search

    /** The round Search button: opens Search, or, on it, clears the box and focuses it. */
    fun tapSearch() {
        if (search && book == null && publication == null) query = "" else { book = null; publication = null; search = true }
        searchFocus.request()
    }

    fun select(next: String) { tab = next; book = null; publication = null; search = false }

    fun back(): Boolean = when {
        publication != null -> { publication = null; true }
        book != null -> { book = null; true }
        search -> { search = false; true }
        tab != BookTabs.LIBRARY -> { tab = BookTabs.LIBRARY; true }
        else -> false
    }
}

/** The pieces every page of Read is handed. */
internal class ReadScope(
    val repository: ReadingRepository,
    /** Where Read's catalogue pages are kept between visits (and runs): see [readTrending]. */
    val pages: PageCache,
    val navigation: ReadNavigation,
    val onMusic: (() -> Unit)?,
    val showAccount: Boolean,
    val bottom: Dp,
    val action: (suspend () -> Unit) -> Job,
    val open: (ReadingBook) -> Unit,
    val onError: (String?) -> Unit,
    val busy: Boolean,
    val importBooks: () -> Unit,
)

@Composable
private fun ReadContent(repository: ReadingRepository, pages: PageCache, contentPadding: PaddingValues, onMusic: (() -> Unit)?, onReaderVisibility: (Boolean) -> Unit,
    showNavigation: Boolean, showAccount: Boolean) {
    val scope = rememberUiCoroutineScope()
    var savedTab by rememberSaveable { mutableStateOf(BookTabs.LIBRARY) }
    val navigation = remember { ReadNavigation(savedTab) }
    LaunchedEffect(navigation.tab) { savedTab = navigation.tab }
    var document by remember { mutableStateOf<ReadingDocument?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<Job?>(null) }
    fun action(block: suspend () -> Unit): Job = scope.launch {
        error = null
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This action could not be completed." }
    }
    fun open(book: ReadingBook) {
        loading?.cancel()
        loading = action {
            busy = true
            try { document = repository.openDocument(book.id) }
            finally { busy = false }
        }
    }
    // The page states where it starts before it listens to the desktop sidebar's rows.
    LaunchedEffect(Unit) {
        ModeNav.mangaTab.value = navigation.tab
        ModeNav.mangaTab.collect { name -> val next = BookTabs.of(name); if (next != navigation.tab) navigation.select(next) }
    }
    LaunchedEffect(navigation.tab) { ModeNav.mangaTab.value = navigation.tab }
    // The desktop sidebar's Search row: lit while Search is open; its tap opens it (or clears and focuses it); another row closes it.
    LaunchedEffect(navigation.search) { ModeNav.mangaSearch.open.value = navigation.search }
    LaunchedEffect(Unit) {
        val seen = ModeNav.mangaSearch.taps.value
        launch { ModeNav.mangaSearch.taps.collect { if (it != seen) navigation.tapSearch() } }
        ModeNav.mangaSearch.open.drop(1).collect { open -> if (!open && navigation.search) navigation.search = false }
    }
    LaunchedEffect(document != null) { onReaderVisibility(document != null) }
    DisposableEffect(Unit) { onDispose { onReaderVisibility(false) } }
    val pick = rememberReadingImport { name, stream ->
        action {
            busy = true
            try { val book = repository.importFile(name, stream); navigation.select(BookTabs.LIBRARY); open(book) }
            finally { busy = false }
        }
    }
    // Pages keep their shelves, filters and scroll while a book, a search or the reader stands over them.
    val holder = rememberSaveableStateHolder()
    val current = document
    if (current != null) {
        BookReader(repository, current, onBack = { document = null })
        return
    }
    MangaBackHandler(!navigation.atRoot || navigation.tab != BookTabs.LIBRARY) { navigation.back() }
    ReadModeFrame(BookTabs.all, navigation.tab, { navigation.select(it) }, showNavigation, showNavigation && navigation.atRoot, onMusic, showAccount,
        modifier = Modifier.testTag("books:root").padding(top = contentPadding.calculateTopPadding()),
        search = com.cosmotune.ui.shell.CosmoNavSearch("Search", selected = navigation.search, onClick = navigation::tapSearch)) { chrome ->
        val bottom = chrome.calculateBottomPadding() + contentPadding.calculateBottomPadding()
        val page = ReadScope(repository, pages, navigation, onMusic, showAccount, bottom, ::action, ::open, { error = it }, busy, pick)
        Column(Modifier.fillMaxSize()) {
            error?.let { message -> ReadInlineBanner(message, Modifier.padding(top = 8.dp), onDismiss = { error = null }) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = orbitLook().accent)
            Box(Modifier.weight(1f)) {
                val publication = navigation.publication
                val book = navigation.book
                when {
                    publication != null -> BookDetails(page, publication)
                    book != null -> BookLocalDetails(page, book)
                    navigation.search -> BookSearchPage(page)
                    navigation.tab == BookTabs.BROWSE -> holder.SaveableStateProvider("tab:browse") { BookDiscover(page) }
                    navigation.tab == BookTabs.BOOKMARKS -> holder.SaveableStateProvider("tab:bookmarks") { BookBookmarks(page) }
                    else -> holder.SaveableStateProvider("tab:library") { BookLibrary(page) }
                }
            }
        }
    }
}

/** How far into [book] the reader is, 0..1, or null before it is opened. */
internal fun ReadingState.fraction(book: ReadingBook): Float? = progress[book.id]?.locator?.progression?.toFloat()?.coerceIn(0f, 1f)

/** A book's line on a card: its author, then where the reader is ("35 %", "Finished"). */
internal fun ReadingState.bookLine(book: ReadingBook): String = listOfNotNull(
    book.authors.firstOrNull(),
    if (book.id in finished) "Finished" else progress[book.id]?.let { "${(it.locator.progression * 100).toInt()} %" },
).joinToString(" · ")

/** The shelves of the Library tab, in chip order. */
internal enum class BookShelf(val label: String) { ALL("All"), READING("Reading"), UNREAD("Unread"), FINISHED("Finished"), DOWNLOADED("Downloaded"), SAVED("Saved") }

/** The books on [shelf] that match [query] (title or author), the latest read or added first. Saved holds catalogue entries, not books. */
internal fun ReadingState.shelf(shelf: BookShelf, query: String): List<ReadingBook> = books.filter { book ->
    (query.isBlank() || (book.title + " " + book.authors.joinToString(" ")).contains(query.trim(), true)) && when (shelf) {
        BookShelf.READING -> progress.containsKey(book.id) && book.id !in finished
        BookShelf.UNREAD -> !progress.containsKey(book.id) && book.id !in finished
        BookShelf.FINISHED -> book.id in finished
        BookShelf.SAVED -> false
        BookShelf.ALL, BookShelf.DOWNLOADED -> true
    }
}.sortedByDescending { progress[it.id]?.updatedAtMs ?: it.addedAtMs }

/** "BOOK · EPUB · 35 %": the mono line of a book's card. */
internal fun ReadingState.bookLabel(book: ReadingBook): String = listOfNotNull(
    "Book", book.format.name, if (book.id in finished) "finished" else fraction(book)?.let { "${(it * 100).toInt()} %" },
).joinToString(" · ")

/** A book of the library as a tall cover: the reading line under it, and the "continue" moon off the corner once it is started. */
@Composable
private fun BookCard(scope: ReadScope, book: ReadingBook, width: Dp? = null) {
    val state by scope.repository.state.collectAsUiState()
    val fraction = state.fraction(book).takeIf { book.id !in state.finished }
    Box {
        ReadCoverCard(book.title, book.authors.firstOrNull(), book.coverPath?.let(::File), onClick = { scope.navigation.book = book }, width = width, key = book.id,
            progress = fraction, onContinue = if (fraction != null) ({ scope.open(book) }) else null)
        val badge = if (book.id in state.finished) "Finished" else fraction?.let { "${(it * 100).toInt()} %" }
        if (badge != null) Text(badge, style = orbitFonts().label.copy(fontSize = 11.sp), color = orbitLook().ground, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(ReadShape.sm).background(orbitLook().ink.copy(alpha = 0.82f)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
internal fun PublicationCard(scope: ReadScope, publication: ReadingPublication, width: Dp? = null) {
    ReadCoverCard(publication.title, publication.authors.firstOrNull(), publication.coverUrl, onClick = { scope.navigation.publication = publication }, width = width, key = publication.id)
}

/** The order of Read's library: the latest read or added first, the title, or when it was added. */
internal enum class BookOrder(val label: String) { RECENT("Last read"), TITLE("Title"), ADDED("Added") }

internal fun List<ReadingBook>.ordered(order: BookOrder, state: ReadingState): List<ReadingBook> = when (order) {
    BookOrder.RECENT -> this
    BookOrder.TITLE -> sortedBy { it.title.lowercase() }
    BookOrder.ADDED -> sortedByDescending { it.addedAtMs }
}

/**
 * Library (board frame 7): the book you are reading as the one colour card; quick tiles with counts (Bookmarks, Downloaded, Saved
 * for later, Finished); then Your books with the shelves as chips with their counts, the order and the list view, over the grid of
 * covers, each with its progress. Import and Search are the heading's actions; Trending lives in Browse.
 */
@Composable
private fun BookLibrary(scope: ReadScope) {
    val state by scope.repository.state.collectAsUiState()
    val grid = rememberLazyGridState()
    var shelf by rememberSaveable { mutableStateOf(BookShelf.ALL) }
    var order by rememberSaveable { mutableStateOf(BookOrder.RECENT) }
    var rows by rememberSaveable { mutableStateOf(false) }
    val books = state.shelf(shelf, "").ordered(order, state)
    val saved = if (shelf == BookShelf.SAVED) state.saved else emptyList()
    val reading = state.books.filter { it.id in state.progress && it.id !in state.finished }.sortedByDescending { state.progress[it.id]?.updatedAtMs ?: 0L }
    val hero = reading.firstOrNull()
    LazyVerticalGrid(if (rows) GridCells.Fixed(1) else GridCells.Adaptive(ReadPosterMin), Modifier.fillMaxSize().testTag("books:library"), state = grid,
        contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
        horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(if (rows) 0.dp else 18.dp)) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            val count = state.books.size
            ReadPageHead("Library", if (count == 1) "1 book · ${reading.size} reading" else "$count books · ${reading.size} reading", Modifier.readBleed()) {
                ReadIconButton(CosmoIcons.Plus, "Import EPUB or PDF", { if (!scope.busy) scope.importBooks() }, Modifier.testTag("books:import"))
            }
        }
        if (hero != null) item(key = "hero:${hero.id}", span = { GridItemSpan(maxLineSpan) }) { BookHero(scope, hero, state) }
        if (state.books.isNotEmpty() || state.saved.isNotEmpty()) item(key = "tiles", span = { GridItemSpan(maxLineSpan) }) {
            BookTiles(state, Modifier.padding(top = 4.dp), onBookmarks = { scope.navigation.select(BookTabs.BOOKMARKS) }, onShelf = { shelf = it })
        }
        // The shelves only once there is something on them: an empty library shows its welcome, not a row of noughts.
        if (state.books.isNotEmpty() || state.saved.isNotEmpty()) item(key = "shelves", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.readBleed(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadSection("Your books")
                ReadChipRow(Modifier.padding(vertical = 4.dp)) {
                    listOf(BookShelf.ALL, BookShelf.READING, BookShelf.UNREAD, BookShelf.FINISHED).forEach { item ->
                        ReadChip(item.label + " ${state.shelf(item, "").size}", shelf == item, { shelf = item }, Modifier.testTag("books:shelf:${item.label}"))
                    }
                    ReadChip(BookShelf.SAVED.label + " ${state.saved.size}", shelf == BookShelf.SAVED, { shelf = BookShelf.SAVED }, Modifier.testTag("books:shelf:${BookShelf.SAVED.label}"))
                    ReadPill(order.label, icon = CosmoIcons.Sort, onClick = { order = BookOrder.entries[(order.ordinal + 1) % BookOrder.entries.size] },
                        modifier = Modifier.testTag("books:order"))
                    ReadIconButton(if (rows) CosmoIcons.Grid else CosmoIcons.List, if (rows) "Show as a grid" else "Show as a list", { rows = !rows }, Modifier.testTag("books:layout"))
                }
            }
        }
        if (shelf == BookShelf.SAVED) {
            if (saved.isEmpty()) item(key = "saved:empty", span = { GridItemSpan(maxLineSpan) }) {
                ReadEmpty("Save something for later", "Books you save from Browse appear here. Import an EPUB or PDF to read your own copy.", Modifier.readBleed())
            }
            items(saved, key = { "saved:${it.id}" }) { publication ->
                if (rows) ReadCoverRow(publication.title, publication.authors.joinToString(" · "), publication.coverUrl, onClick = { scope.navigation.publication = publication },
                    key = publication.id)
                else PublicationCard(scope, publication)
            }
        } else {
            if (books.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                ReadEmpty(
                    when (shelf) {
                        BookShelf.READING -> "Start your next chapter"; BookShelf.FINISHED -> "A shelf of stories completed"; BookShelf.UNREAD -> "Everything here is started"
                        else -> "Your next read starts here"
                    },
                    "Import an EPUB or PDF, or find books from public libraries and public-domain catalogues.",
                    Modifier.readBleed(),
                    actionLabel = if (state.books.isEmpty()) "Import books" else null, onAction = scope.importBooks,
                )
            }
            items(books, key = { it.id }) { book -> if (rows) BookRow(scope, book) else BookCard(scope, book) }
        }
    }
}

/**
 * The library's quick tiles in two columns, each with its count: Bookmarks (opens the tab), Downloaded, Saved for later and
 * Finished (each chooses its shelf).
 */
@Composable
private fun BookTiles(state: ReadingState, modifier: Modifier, onBookmarks: () -> Unit, onShelf: (BookShelf) -> Unit) {
    val tiles: List<Triple<String, Int, () -> Unit>> = listOf(
        Triple("Bookmarks", state.bookmarks.size, onBookmarks),
        Triple("Downloaded", state.books.size, { onShelf(BookShelf.DOWNLOADED) }),
        Triple("Saved for later", state.saved.size, { onShelf(BookShelf.SAVED) }),
        Triple("Finished", state.finished.count { id -> state.books.any { it.id == id } }, { onShelf(BookShelf.FINISHED) }),
    )
    val look = orbitLook()
    val fonts = orbitFonts()
    Column(modifier.fillMaxWidth().testTag("books:tiles"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, count, open) ->
                    Row(Modifier.weight(1f).heightIn(min = 56.dp).clip(ReadShape.md).background(look.row)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = open).padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("books:tile:$label"), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = ReadType.rowTitle(), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(count.toString(), style = fonts.label, color = look.dim)
                    }
                }
            }
        }
    }
}

/** A book of the library as a row (the list view): its cover, the title, "Author · 35 %". */
@Composable
private fun BookRow(scope: ReadScope, book: ReadingBook) {
    val state by scope.repository.state.collectAsUiState()
    ReadCoverRow(book.title, state.bookLine(book), book.coverPath?.let(::File), onClick = { scope.navigation.book = book }, key = book.id,
        modifier = Modifier.testTag("books:row:${book.id}"))
}

/** The book being read: its colour card, "Chapter 4 · 35 %", and Continue in the bite. */
@Composable
private fun BookHero(scope: ReadScope, book: ReadingBook, state: ReadingState) {
    val fraction = state.fraction(book) ?: 0f
    OrbitCoverTitleCard(
        cover = book.coverPath?.let(::File), colourUrl = null, key = book.id, label = state.bookLabel(book), title = book.title,
        line = book.authors.joinToString(" · ").ifBlank { null }, onContinue = { scope.open(book) }, onClick = { scope.navigation.book = book },
        modifier = Modifier.padding(top = 4.dp).testTag("books:hero"),
        below = { colors -> OrbitProgressLine({ fraction }, colors.ink, Modifier.padding(top = 10.dp, end = 8.dp), moon = 4.dp) },
        chips = { colors ->
            val marks = state.bookmarks.count { it.bookId == book.id }
            if (marks > 0) OrbitCardChip("Bookmarks $marks", colors, onClick = { scope.navigation.book = book }, icon = Icons.Outlined.BookmarkBorder)
        },
    )
}

/** Bookmarks: every bookmark by book; tap opens the reader there. */
@Composable
private fun BookBookmarks(scope: ReadScope) {
    val state by scope.repository.state.collectAsUiState()
    val list = rememberLazyListState()
    val groups = state.bookmarks.groupBy { it.bookId }.mapNotNull { (id, marks) -> state.books.find { it.id == id }?.let { it to marks.sortedByDescending { mark -> mark.createdAtMs } } }
    LazyColumn(Modifier.fillMaxSize().testTag("books:bookmarks:list"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
        item(key = "heading") {
            val count = state.bookmarks.size
            ReadPageHead("Bookmarks", if (count == 1) "1 passage kept" else "$count passages kept")
        }
        if (groups.isEmpty()) item(key = "empty") {
            ReadEmpty("No bookmarks yet", "Bookmark a passage from the reader's tools to come back to it here.")
        }
        groups.forEach { (book, marks) ->
            item(key = "book:${book.id}") {
                ReadCoverRow(book.title, book.authors.joinToString(" · ").ifBlank { if (marks.size == 1) "1 bookmark" else "${marks.size} bookmarks" }, book.coverPath?.let(::File),
                    key = book.id, onClick = { scope.navigation.book = book }, modifier = Modifier.padding(start = ReadMargin, end = ReadMargin, top = 16.dp))
            }
            items(marks, key = { "mark:${it.id}" }) { mark -> BookmarkRow(scope, book, mark) }
        }
    }
}

/**
 * A bookmark as a flat Orbit row: the mark, its name and when it was made, the cross that removes it. Tap opens the reader there.
 * [nested] (the Bookmarks tab) steps it 24 dp in under its book's row; on the book's own page it stands on the page's margin, the
 * start edge of the Details rows under it.
 */
@Composable
internal fun BookmarkRow(scope: ReadScope, book: ReadingBook, mark: ReadingBookmark, nested: Boolean = true) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val undo = LocalOrbitUndo.current
    OrbitSongRow(
        title = mark.name, subtitle = agoLabel(mark.createdAtMs).replaceFirstChar(Char::uppercase), cover = null, fonts = fonts, look = look,
        onClick = { scope.action { scope.repository.saveLocator(book.id, mark.locator); scope.open(book) } },
        modifier = Modifier.fillMaxWidth().padding(start = ReadMargin + if (nested) 24.dp else 0.dp, end = ReadMargin), icon = Icons.Outlined.BookmarkBorder,
        trailing = { ReadIconButton(CosmoIcons.Close, "Remove bookmark", {
            // Gone at once, with "Removed · Undo" to put it back.
            scope.action {
                scope.repository.removeBookmark(mark.id)
                undo.removedThen(mark.name) { scope.repository.restoreBookmark(mark) }
            }
        }) },
    )
}

/**
 * A book on this device (board D's title page): the colour card (the cover, "BOOK · EPUB · 35 %", the title, the authors, the
 * reading line, Mark finished and More (Remove from this device, with Undo), Continue or Read in the bite), the description, its
 * bookmarks and its details.
 */
@Composable
private fun BookLocalDetails(scope: ReadScope, initial: ReadingBook) {
    val state by scope.repository.state.collectAsUiState()
    val book = state.books.find { it.id == initial.id } ?: initial
    val look = orbitLook()
    val palette = LocalCosmoColors.current.palette
    val list = rememberLazyListState()
    val cover = book.coverPath?.let(::File)
    val fraction = state.fraction(book)
    val finished = book.id in state.finished
    var more by remember { mutableStateOf(false) }
    val undo = LocalOrbitUndo.current
    // Remove takes the book off the library at once, with "Removed · Undo"; its files are deleted only once Undo has run out.
    val remove: () -> Unit = {
        scope.action {
            val taken = scope.repository.takeOutBook(book.id) ?: return@action
            scope.navigation.book = null
            undo.removeAfterUndo(book.title, hide = {}, restore = { scope.repository.putBackBook(taken) }) { scope.repository.deleteTakenBook(taken) }
        }
    }
    val (fill, _) = coverFill(null, book.id)
    Box(Modifier.fillMaxSize()) {
        OrbitPageWash(fill)
        LazyColumn(Modifier.fillMaxSize().testTag("books:book"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "head") { ReadBackHead("Book", onBack = { scope.navigation.book = null }) }
            item(key = "card") {
                OrbitCoverTitleCard(
                    cover = cover, colourUrl = null, key = book.id, label = state.bookLabel(book).removePrefix("Book · ") + " · on this device", title = book.title,
                    line = book.authors.joinToString(" · ").ifBlank { null },
                    onContinue = { scope.open(book) },
                    modifier = Modifier.padding(horizontal = ReadMargin).padding(top = 4.dp).testTag("books:read:${book.id}"),
                    below = { colors -> if (fraction != null && !finished) OrbitProgressLine({ fraction }, colors.ink, Modifier.padding(top = 10.dp, end = 8.dp), moon = 4.dp) },
                    chips = { colors ->
                        OrbitCardChip(if (finished) "Mark unfinished" else "Mark finished", colors, onClick = { scope.action { scope.repository.markFinished(book.id, !finished) } }, icon = CosmoIcons.Check)
                        Box {
                            com.cosmotune.ui.orbit.OrbitCardButton(CosmoIcons.MoreVertical, "More", colors, { more = true }, Modifier.testTag("books:book:more"))
                            com.cosmotune.ui.orbit.OrbitMenu(more, { more = false }, listOf(
                                com.cosmotune.ui.orbit.OrbitMenuItem("Remove from this device", icon = CosmoIcons.Close) { remove() },
                            ), orbitFonts(), look, header = book.title)
                        }
                    },
                )
            }
            if (book.description.isNotBlank()) item(key = "about") {
                var open by rememberSaveable(book.id) { mutableStateOf(false) }
                Text(remember(book.description) { org.jsoup.Jsoup.parse(book.description).text() }, style = ReadType.body(), color = look.dim,
                    maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Show less" else "Show more") { open = !open }
                        .padding(horizontal = ReadMargin + 4.dp, vertical = 14.dp))
            }
            val marks = state.bookmarks.filter { it.bookId == book.id }
            if (marks.isNotEmpty()) {
                item(key = "marks:header") { ReadSection("Bookmarks", subtitle = marks.size.toString()) }
                items(marks, key = { "mark:${it.id}" }) { mark -> BookmarkRow(scope, book, mark, nested = false) }
            }
            item(key = "facts") {
                ReadSection("Details")
                // A flat list of two columns: the name in the dim ink, the value beside it.
                Column(Modifier.fillMaxWidth().padding(horizontal = ReadMargin + 4.dp).testTag("books:facts"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    bookFacts(book).forEach { (name, value) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(name, style = ReadType.meta(), color = look.dim, modifier = Modifier.width(110.dp))
                            Text(value, style = ReadType.body(), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** A book's details as its page lists them: the format, the language, when it was added and where it came from. */
internal fun bookFacts(book: ReadingBook, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Pair<String, String>> = listOfNotNull(
    "Format" to book.format.name,
    book.language.takeIf { it.isNotBlank() }?.let { "Language" to it },
    "Added" to java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH).format(java.time.Instant.ofEpochMilli(book.addedAtMs).atZone(zone)),
    "Source" to (book.sourceUrl ?: "On this device"),
)

/** What Read's search asks: books (their titles and authors) or authors alone. */
internal enum class BookSearchKind(val id: String, val label: String) { BOOKS("books", "Books"), AUTHORS("authors", "Authors") }

/** Where Read's search looks: everywhere it can, the books on this device, Open Library, or Project Gutenberg. */
internal enum class BookSearchSource(val id: String, val label: String) {
    ALL("all", "All sources"), MINE("mine", "Your books"), OPEN_LIBRARY("openlibrary", "Open Library"), GUTENBERG("gutenberg", "Project Gutenberg");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: ALL }
}

/**
 * The languages Read's search can ask for: Open Library's own code (its `language` filter) and the two-letter code a book on this
 * device carries in its metadata.
 */
internal val BOOK_SEARCH_LANGUAGES = listOf(
    Triple("eng", "en", "English"), Triple("tam", "ta", "Tamil"), Triple("hin", "hi", "Hindi"), Triple("tel", "te", "Telugu"),
    Triple("mal", "ml", "Malayalam"), Triple("kan", "kn", "Kannada"), Triple("ben", "bn", "Bengali"), Triple("mar", "mr", "Marathi"),
    Triple("spa", "es", "Spanish"), Triple("fre", "fr", "French"), Triple("ger", "de", "German"), Triple("ita", "it", "Italian"),
    Triple("por", "pt", "Portuguese"), Triple("rus", "ru", "Russian"), Triple("jpn", "ja", "Japanese"), Triple("chi", "zh", "Chinese"),
    Triple("ara", "ar", "Arabic"),
)

/** Whether a book on this device is in the language Open Library calls [code] (its own metadata's tag, two or three letters). */
internal fun ReadingBook.inLanguage(code: String): Boolean {
    val tag = language.trim().lowercase().substringBefore('-')
    val two = BOOK_SEARCH_LANGUAGES.firstOrNull { it.first == code }?.second
    return tag.isNotEmpty() && (tag == code || tag == two)
}

/** The books on this device that match [query] as [kind] asks (the title and the authors, or the authors alone), in [language]. */
internal fun ReadingState.searchBooks(query: String, kind: BookSearchKind, language: String): List<ReadingBook> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    return books.filter { book ->
        val haystack = if (kind == BookSearchKind.AUTHORS) book.authors.joinToString(" ") else book.title + " " + book.authors.joinToString(" ")
        haystack.contains(term, ignoreCase = true) && (language.isEmpty() || book.inLanguage(language))
    }.sortedByDescending { progress[it.id]?.updatedAtMs ?: it.addedAtMs }
}

/** The key of Read's recent searches. */
private const val READ_RECENT = "read"

/**
 * Search (the round Search button), on the shared page ([com.cosmotune.ui.search.CosmoSearchPage]): Books or Authors; the language
 * (Open Library's own filter, and each book's metadata on this device) and the source (your books, Open Library, Project
 * Gutenberg). Project Gutenberg's search matches titles and authors together, so it is offered for Books only, and has no language
 * filter. Before anything is typed: the recent searches and what is trending on Open Library. A result opens its own page.
 */
@Composable
private fun BookSearchPage(scope: ReadScope) {
    val state by scope.repository.state.collectAsUiState()
    val navigation = scope.navigation
    val query = navigation.query
    var kindId by rememberSaveable { mutableStateOf(BookSearchKind.BOOKS.id) }
    var sourceId by rememberSaveable { mutableStateOf(BookSearchSource.ALL.id) }
    var language by rememberSaveable { mutableStateOf("") }
    val kind = BookSearchKind.entries.first { it.id == kindId }
    val sources = if (kind == BookSearchKind.AUTHORS) BookSearchSource.entries - BookSearchSource.GUTENBERG else BookSearchSource.entries
    val source = BookSearchSource.of(sourceId).takeIf { it in sources } ?: BookSearchSource.ALL
    val languageOffered = source != BookSearchSource.GUTENBERG
    val shownLanguage = if (languageOffered) language else ""
    var results by remember { mutableStateOf(emptyList<ReadingPublication>()) }
    var searching by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val term = query.trim()
    LaunchedEffect(term, kind, source, shownLanguage, retry) { withContext(Dispatchers.Main.immediate) {
        results = emptyList(); failure = null
        if (term.length < 2 || source == BookSearchSource.MINE) { searching = false; return@withContext }
        searching = true
        try {
            kotlinx.coroutines.delay(400)
            val openLibrary = source == BookSearchSource.ALL || source == BookSearchSource.OPEN_LIBRARY
            results = if (openLibrary) scope.repository.search(term, 0, author = kind == BookSearchKind.AUTHORS, language = shownLanguage.ifBlank { null }).publications
                else scope.repository.gutenberg(term).publications
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "The catalogue could not be searched." }
        finally { searching = false }
    } }
    val mine = if (source == BookSearchSource.ALL || source == BookSearchSource.MINE) state.searchBooks(term, kind, shownLanguage) else emptyList()
    val found = results.distinctBy { it.id }
    val recent by com.cosmotune.ui.search.CosmoRecentSearches.of(READ_RECENT).collectAsState()
    val trending = remember(scope.pages, scope.repository) { readTrending(scope.pages, scope.repository) }
    LaunchedEffect(trending) { trending.restore(); if (!trending.isFresh) runCatching { trending.fetchNow() } }
    val trendingPage by trending.state.collectAsState()
    fun keep(text: String) { if (text.isNotBlank()) com.cosmotune.ui.search.CosmoRecentSearches.add(READ_RECENT, text) }
    val status = when {
        term.length < 2 -> com.cosmotune.ui.search.CosmoSearchStatus.Idle
        searching && mine.isEmpty() -> com.cosmotune.ui.search.CosmoSearchStatus.Searching
        failure != null && mine.isEmpty() -> com.cosmotune.ui.search.CosmoSearchStatus.Failed(failure.orEmpty()) { retry++ }
        searching -> com.cosmotune.ui.search.CosmoSearchStatus.Searching
        mine.isEmpty() && found.isEmpty() -> com.cosmotune.ui.search.CosmoSearchStatus.Empty()
        else -> com.cosmotune.ui.search.CosmoSearchStatus.Results(mine.size + found.size, com.cosmotune.ui.search.searchCountLabel(mine.size + found.size, "book", "books"))
    }
    val filters = buildList {
        if (languageOffered) add(com.cosmotune.ui.search.CosmoSearchFilter("language", "Language",
            listOf(com.cosmotune.ui.search.CosmoSearchChoice("", "Any language")) + BOOK_SEARCH_LANGUAGES.map { com.cosmotune.ui.search.CosmoSearchChoice(it.first, it.third) },
            shownLanguage) { language = it })
        add(com.cosmotune.ui.search.CosmoSearchFilter("source", "Source", sources.map { com.cosmotune.ui.search.CosmoSearchChoice(it.id, it.label) }, source.id) { sourceId = it })
    }
    com.cosmotune.ui.search.CosmoSearchPage(
        eyebrow = "Read",
        query = query,
        onQueryChange = { navigation.query = it.take(500) },
        onSubmit = { keep(query) },
        placeholder = if (kind == BookSearchKind.AUTHORS) "Search authors" else "Search books and authors",
        status = status,
        modifier = Modifier.testTag("books:search"),
        focus = navigation.searchFocus,
        contentPadding = PaddingValues(top = 4.dp, bottom = scope.bottom),
        kinds = BookSearchKind.entries.map { com.cosmotune.ui.search.CosmoSearchChoice(it.id, it.label) },
        kind = kind.id,
        onKind = { kindId = it },
        filters = filters,
        onClearFilters = if (shownLanguage.isNotEmpty() || source != BookSearchSource.ALL) ({ language = ""; sourceId = BookSearchSource.ALL.id }) else null,
        recents = com.cosmotune.ui.search.CosmoSearchRecents(
            recent.map { com.cosmotune.ui.search.CosmoRecent(it, it) },
            onPick = { navigation.query = it.title; keep(it.title) },
            onRemove = { com.cosmotune.ui.search.CosmoRecentSearches.remove(READ_RECENT, it.title) },
            onClear = { com.cosmotune.ui.search.CosmoRecentSearches.clear(READ_RECENT) },
        ),
        browseTitle = "Trending on Open Library",
        browse = trendingPage.data?.publications?.takeIf { it.isNotEmpty() }?.let { books -> { side ->
            item(key = "browse:trending") {
                LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                    items(books.take(15), key = { "trending:${it.id}" }) { publication -> PublicationCard(scope, publication, width = 112.dp) }
                }
            }
        } },
    ) { listModifier, side ->
        LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), listModifier,
            contentPadding = PaddingValues(start = side, end = side, bottom = scope.bottom),
            horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (mine.isNotEmpty()) {
                item(key = "mine", span = { GridItemSpan(maxLineSpan) }) { ReadOverline("Your books", Modifier.readBleed(side), inset = side) }
                items(mine, key = { "mine:${it.id}" }) { book -> BookCard(scope, book) }
            }
            if (found.isNotEmpty()) {
                item(key = "catalogue", span = { GridItemSpan(maxLineSpan) }) {
                    ReadOverline(if (source == BookSearchSource.GUTENBERG) "Project Gutenberg" else "Open Library", Modifier.readBleed(side), inset = side)
                }
                items(found, key = { "result:${it.id}" }) { publication -> PublicationCard(scope, publication) }
            }
        }
    }
}

/**
 * The first page of trending books on Open Library, which Read's Library and Browse both show: kept by [pages] for the process
 * (and on the disk, for the app's), shown at once and fetched again after [SLOW_FRESH_MS].
 */
internal fun readTrending(pages: PageCache, repository: ReadingRepository): CachedLoad<com.cosmotune.reading.data.ReadingSearchPage> =
    pages.entry("read:trending", SLOW_FRESH_MS, CacheCodec.json(com.cosmotune.reading.data.ReadingSearchPage.serializer())) { repository.trending(0) }
