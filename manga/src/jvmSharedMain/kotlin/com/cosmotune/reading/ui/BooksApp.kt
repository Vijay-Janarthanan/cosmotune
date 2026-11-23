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
    val atRoot: Boolean get() = book == null && publication == null && !search

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
    /** Adds a folder of books (then reads it), and checks the folders for new books. */
    val addFolder: () -> Unit = {},
    val scanFolders: () -> Unit = {},
)

@Composable
private fun ReadContent(repository: ReadingRepository, pages: PageCache, contentPadding: PaddingValues, onMusic: (() -> Unit)?, onReaderVisibility: (Boolean) -> Unit,
    showNavigation: Boolean, showAccount: Boolean) {
    val scope = rememberUiCoroutineScope()
    var savedTab by rememberSaveable { mutableStateOf(BookTabs.LIBRARY) }
    val navigation = remember { ReadNavigation(savedTab) }
    LaunchedEffect(navigation.tab) { savedTab = navigation.tab }
    var document by remember { mutableStateOf<ReadingDocument?>(null) }
    var beside by remember { mutableStateOf<ReadingDocument?>(null) }
    var choosingBeside by remember { mutableStateOf(false) }
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
    LaunchedEffect(document != null) { onReaderVisibility(document != null) }
    // With a sync folder connected, Read syncs when it opens and each time a book is closed (quietly: Settings shows how it went).
    LaunchedEffect(document == null) { if (document == null && repository.state.value.webDav != null) runCatching { repository.syncWebDav() } }
    DisposableEffect(Unit) { onDispose { onReaderVisibility(false) } }
    val pick = rememberReadingImport { name, stream ->
        action {
            busy = true
            try { val book = repository.importFile(name, stream); navigation.select(BookTabs.LIBRARY); open(book) }
            finally { busy = false }
        }
    }
    // Folders of books: read when Read opens, when one is added and when asked; new files become books.
    val folderAccess = rememberReadingFolders()
    var scanning by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    fun scanFolders(quiet: Boolean) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            var added = 0
            try {
                for (folder in repository.state.value.folders) {
                    try {
                        val files = folderAccess.list(folder)
                        added += repository.scanFolder(files) { at, of -> scanning = "Adding books from ${folder.name} · $at of $of" }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (!quiet) error = failure.message ?: "The folder “${folder.name}” could not be read." }
                }
                if (!quiet && added == 0) error = "No new books in your folders."
            } finally { scanning = null }
        }
    }
    LaunchedEffect(Unit) { if (repository.state.value.folders.isNotEmpty()) scanFolders(quiet = true) }
    // Audiobooks keep their place while the app's player has them, whichever page is showing.
    ReadingAudioTracker(repository)
    val addFolder = { folderAccess.pick { folder -> action { repository.addFolder(folder); scanFolders(quiet = false) } } }
    // Pages keep their shelves, filters and scroll while a book, a search or the reader stands over them.
    val holder = rememberSaveableStateHolder()
    val current = document
    if (current != null) {
        // Two books side by side where the screen is wide enough: the second opens from the first's tools and closes on its own.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val second = beside?.takeIf { wide && it.book.id != current.book.id }
            if (second == null) BookReader(repository, current, onBack = { document = null; beside = null }, onBeside = if (wide) ({ choosingBeside = true }) else null)
            else Row(Modifier.fillMaxSize()) {
                BookReader(repository, current, onBack = { document = second; beside = null }, Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().width(1.dp).background(orbitLook().dim.copy(alpha = 0.3f)))
                BookReader(repository, second, onBack = { beside = null }, Modifier.weight(1f))
            }
        }
        if (choosingBeside) BesideSheet(repository.state.collectAsUiState().value.books.filter { it.id != current.book.id }, onPick = { book ->
            choosingBeside = false
            action { busy = true; try { beside = repository.openDocument(book.id) } finally { busy = false } }
        }, onDismiss = { choosingBeside = false })
        return
    }
    MangaBackHandler(!navigation.atRoot || navigation.tab != BookTabs.LIBRARY) { navigation.back() }
    ReadModeFrame(BookTabs.all, navigation.tab, { navigation.select(it) }, showNavigation, showNavigation && navigation.atRoot, onMusic, showAccount,
        modifier = Modifier.testTag("books:root").padding(top = contentPadding.calculateTopPadding())) { chrome ->
        val bottom = chrome.calculateBottomPadding() + contentPadding.calculateBottomPadding()
        val page = ReadScope(repository, pages, navigation, onMusic, showAccount, bottom, ::action, ::open, { error = it }, busy, pick, addFolder, { scanFolders(quiet = false) })
        Column(Modifier.fillMaxSize()) {
            error?.let { message -> ReadInlineBanner(message, Modifier.padding(top = 8.dp), onDismiss = { error = null }) }
            scanning?.let { line -> ReadInlineBanner(line, Modifier.padding(top = 8.dp).testTag("books:scanning")) }
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
                    navigation.tab == BookTabs.LISTEN -> holder.SaveableStateProvider("tab:listen") { BookListen(page) }
                    else -> holder.SaveableStateProvider("tab:library") { BookLibrary(page) }
                }
            }
        }
    }
}

/** The round actions of a tab's heading: its own, then Search. */
@Composable
internal fun RowScope.ReadRootActions(scope: ReadScope, page: @Composable () -> Unit = {}) {
    page()
    ReadIconButton(CosmoIcons.Search, "Search", { scope.navigation.search = true })
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
    "Book", book.originalFormat.ifBlank { book.format.name }, if (book.id in finished) "finished" else fraction(book)?.let { "${(it * 100).toInt()} %" },
).joinToString(" · ")

/** A book of the library as a tall cover: the reading line under it, and the "continue" moon off the corner once it is started. */
@Composable
private fun BookCard(scope: ReadScope, book: ReadingBook, width: Dp? = null) {
    val state by scope.repository.state.collectAsUiState()
    val fraction = state.fraction(book).takeIf { book.id !in state.finished }
    Box {
        ReadCoverCard(book.title, book.authors.firstOrNull(), book.coverPath?.let(::File), onClick = { scope.navigation.book = book }, width = width, key = book.id,
            progress = fraction, onContinue = if (fraction != null) ({ scope.open(book) }) else null)
        val missing = remember(book.filePath) { !File(book.filePath).isFile }
        val badge = if (missing) "File missing" else if (book.id in state.finished) "Finished" else fraction?.let { "${(it * 100).toInt()} %" }
        if (badge != null) Text(badge, style = orbitFonts().label.copy(fontSize = 11.sp), color = orbitLook().ground, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(ReadShape.sm).background(orbitLook().ink.copy(alpha = 0.82f)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
internal fun PublicationCard(scope: ReadScope, publication: ReadingPublication, width: Dp? = null) {
    ReadCoverCard(publication.title, publication.authors.firstOrNull(), publication.coverUrl, onClick = { scope.navigation.publication = publication }, width = width, key = publication.id)
}

/** The order of Read's library: the latest read or added first, the title, or when it was added. */
internal enum class BookOrder(val label: String) { RECENT("Last read"), TITLE("Title"), AUTHOR("Author"), ADDED("Added"), PROGRESS("Progress") }

/** How the library can be grouped: by author, series or subject, with a heading over each group. */
internal enum class BookGroup(val label: String) { NONE("No groups"), AUTHOR("By author"), SERIES("By series"), SUBJECT("By subject") }

/** [this] in [group]'s groups (each a heading and its books, the headings in order, "Other" last), or one group without a heading. */
internal fun List<ReadingBook>.grouped(group: BookGroup): List<Pair<String?, List<ReadingBook>>> = when (group) {
    BookGroup.NONE -> listOf(null to this)
    else -> {
        val keyOf: (ReadingBook) -> List<String> = when (group) {
            BookGroup.AUTHOR -> { book -> listOf(book.authors.firstOrNull()?.trim().orEmpty()) }
            BookGroup.SERIES -> { book -> listOf(book.series.trim()) }
            else -> { book -> book.subjects.ifEmpty { listOf("") } }
        }
        val groups = LinkedHashMap<String, MutableList<ReadingBook>>()
        forEach { book -> keyOf(book).forEach { groups.getOrPut(it) { ArrayList() } += book } }
        groups.entries.sortedWith(compareBy({ it.key.isEmpty() }, { it.key.lowercase() })).map { (key, books) ->
            key.ifEmpty { "Other" } to if (group == BookGroup.SERIES && key.isNotEmpty()) books.sortedBy { it.seriesIndex ?: Double.MAX_VALUE } else books
        }
    }
}

internal fun List<ReadingBook>.ordered(order: BookOrder, state: ReadingState): List<ReadingBook> = when (order) {
    BookOrder.RECENT -> this
    BookOrder.TITLE -> sortedBy { it.title.lowercase() }
    BookOrder.AUTHOR -> sortedWith(compareBy({ it.authors.firstOrNull()?.substringAfterLast(' ')?.lowercase() ?: "\uffff" }, { it.title.lowercase() }))
    BookOrder.ADDED -> sortedByDescending { it.addedAtMs }
    BookOrder.PROGRESS -> sortedByDescending { if (it.id in state.finished) 2.0 else state.progress[it.id]?.locator?.progression ?: -1.0 }
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
    var group by rememberSaveable { mutableStateOf(BookGroup.NONE) }
    var clipping by remember { mutableStateOf<String?>(null) }
    clipping?.let { kind -> BookClipSheet(kind == "page", onSave = { first, second ->
        scope.action { val book = if (kind == "page") scope.repository.importWebPage(first) else scope.repository.importText(first, second); scope.open(book) }
    }, onDismiss = { clipping = null }) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    val tags = state.books.flatMap { it.tags }.distinct().sortedBy { it.lowercase() }
    val books = state.shelf(shelf, "").filter { tag == null || tag in it.tags }.ordered(order, state)
    // Choosing several books at once, to tag them or mark them finished together; the list shows a tick on each row.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var bulkTagging by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    if (history) BookHistorySheet(scope, onDismiss = { history = false })
    if (browsing) BookFilesSheet(scope, onDismiss = { browsing = false })
    // Nearby: the books to send (none when it was opened to receive), or null while it is closed.
    var nearby by remember { mutableStateOf<List<ReadingBook>?>(null) }
    nearby?.let { BookNearbySheet(scope, it, onDismiss = { nearby = null }) }
    var fromCalibre by remember { mutableStateOf(false) }
    if (fromCalibre) BookCalibreSheet(scope, onDismiss = { fromCalibre = false })
    if (bulkTagging) BulkTagsSheet(state.books.filter { it.id in chosen }, tags, onSave = { add, remove ->
        scope.action { scope.repository.editTags(chosen, add, remove) }
    }, onDismiss = { bulkTagging = false })
    val saved = if (shelf == BookShelf.SAVED) state.saved else emptyList()
    val reading = state.books.filter { it.id in state.progress && it.id !in state.finished }.sortedByDescending { state.progress[it.id]?.updatedAtMs ?: 0L }
    val hero = reading.firstOrNull()
    LazyVerticalGrid(if (rows) GridCells.Fixed(1) else GridCells.Adaptive(ReadPosterMin), Modifier.fillMaxSize().testTag("books:library"), state = grid,
        contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
        horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(if (rows) 0.dp else 18.dp)) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            val count = state.books.size
            ReadPageHead("Library", if (count == 1) "1 book · ${reading.size} reading" else "$count books · ${reading.size} reading", Modifier.readBleed()) {
                Box {
                    var adding by remember { mutableStateOf(false) }
                    ReadIconButton(CosmoIcons.Plus, "Add books", { if (!scope.busy) adding = true }, Modifier.testTag("books:import"))
                    com.cosmotune.ui.orbit.OrbitMenu(adding, { adding = false }, listOfNotNull(
                        com.cosmotune.ui.orbit.OrbitMenuItem("Import a book file", detail = "EPUB, PDF, Kindle, FB2, Word, text, CBZ or CHM help", icon = CosmoIcons.Plus) { scope.importBooks() },
                        com.cosmotune.ui.orbit.OrbitMenuItem("Add a folder of books", detail = "New books in it are added each time Read opens", icon = CosmoIcons.Library) { scope.addFolder() },
                        com.cosmotune.ui.orbit.OrbitMenuItem("Check folders for new books", icon = CosmoIcons.Repeat) { scope.scanFolders() }.takeIf { state.folders.isNotEmpty() },
                        com.cosmotune.ui.orbit.OrbitMenuItem("Save a web page", detail = "An article's text and pictures, to read later", icon = CosmoIcons.Explore) { clipping = "page" },
                        com.cosmotune.ui.orbit.OrbitMenuItem("Paste text", detail = "Text you copied becomes a book", icon = CosmoIcons.List) { clipping = "text" },
                        com.cosmotune.ui.orbit.OrbitMenuItem("Send or receive nearby", detail = "Between your devices on the same Wi-Fi, or LocalSend", icon = CosmoIcons.Devices) { nearby = emptyList() },
                        com.cosmotune.ui.orbit.OrbitMenuItem("Receive from calibre", detail = "calibre's wireless device connection", icon = CosmoIcons.Download) { fromCalibre = true },
                    ), orbitFonts(), orbitLook(), header = "Add books")
                }
                ReadRootActions(scope)
            }
        }
        if (hero != null) item(key = "hero:${hero.id}", span = { GridItemSpan(maxLineSpan) }) { BookHero(scope, hero, state) }
        if (state.books.isNotEmpty() || state.saved.isNotEmpty()) item(key = "tiles", span = { GridItemSpan(maxLineSpan) }) {
            BookTiles(state, Modifier.padding(top = 4.dp), onBookmarks = { scope.navigation.select(BookTabs.BOOKMARKS) }, onShelf = { shelf = it },
                onHistory = { history = true }, onFiles = { browsing = true })
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
                    ReadPill(group.label, icon = CosmoIcons.Library, onClick = { group = BookGroup.entries[(group.ordinal + 1) % BookGroup.entries.size] },
                        modifier = Modifier.testTag("books:group"))
                    ReadIconButton(if (rows) CosmoIcons.Grid else CosmoIcons.List, if (rows) "Show as a grid" else "Show as a list", { rows = !rows }, Modifier.testTag("books:layout"))
                    if (shelf != BookShelf.SAVED && books.isNotEmpty())
                        ReadIconButton(CosmoIcons.Check, if (selecting) "Stop choosing books" else "Choose several books", { selecting = !selecting; chosen = emptySet() },
                            Modifier.testTag("books:select"), on = selecting)
                }
                // The count over its own row of actions, so no action is squeezed out on a narrow phone.
                if (selecting && shelf != BookShelf.SAVED) Column(Modifier.fillMaxWidth().clip(ReadShape.md).background(orbitLook().row).padding(top = 10.dp, bottom = 8.dp)
                    .testTag("books:selection-bar"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (chosen.isEmpty()) "Tap books to choose them" else "${chosen.size} chosen", style = ReadType.rowTitle(), color = orbitLook().ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    val all = books.map { it.id }.toSet()
                    ReadChipRow(inset = 16.dp) {
                        ReadPill(if (chosen.containsAll(all)) "None" else "All", onClick = { chosen = if (chosen.containsAll(all)) emptySet() else all }, modifier = Modifier.testTag("books:select-all"))
                        if (chosen.isNotEmpty()) {
                            ReadPill("Tags", icon = CosmoIcons.Pin, onClick = { bulkTagging = true }, modifier = Modifier.testTag("books:bulk-tags"))
                            ReadPill("Finished", icon = CosmoIcons.Check, onClick = { val ids = chosen; scope.action { ids.forEach { scope.repository.markFinished(it) } } },
                                modifier = Modifier.testTag("books:bulk-finished"))
                            ReadPill("Send nearby", icon = CosmoIcons.Devices, onClick = { nearby = state.books.filter { it.id in chosen } }, modifier = Modifier.testTag("books:bulk-nearby"))
                        }
                    }
                }
                if (tags.isNotEmpty()) ReadChipRow(Modifier.padding(bottom = 4.dp)) {
                    tags.forEach { name -> ReadChip(name, tag == name, { tag = if (tag == name) null else name }, Modifier.testTag("books:tag:$name")) }
                }
            }
        }
        if (shelf == BookShelf.SAVED) {
            if (saved.isEmpty()) item(key = "saved:empty", span = { GridItemSpan(maxLineSpan) }) {
                ReadEmpty("Save something for later", "Books you save from Browse appear here. Import a book file to read your own copy.", Modifier.readBleed())
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
                    "Import EPUB, PDF, Kindle, FB2, Word, text or comic files, or find books from public libraries and public-domain catalogues.",
                    Modifier.readBleed(),
                    actionLabel = if (state.books.isEmpty()) "Import books" else null, onAction = scope.importBooks,
                )
            }
            books.grouped(group).forEach { (heading, members) ->
                if (heading != null) item(key = "group:$heading", span = { GridItemSpan(maxLineSpan) }) {
                    ReadSection(heading, Modifier.readBleed(), subtitle = members.size.toString())
                }
                items(members, key = { "${heading.orEmpty()}:${it.id}" }, span = if (selecting) ({ GridItemSpan(maxLineSpan) }) else null) { book ->
                    if (selecting) {
                        val on = book.id in chosen
                        val toggle = { chosen = if (on) chosen - book.id else chosen + book.id }
                        ReadCoverRow(book.title, state.bookLine(book), book.coverPath?.let(::File), onClick = toggle, key = book.id, highlighted = on,
                            modifier = Modifier.testTag("books:choose:${book.id}")) {
                            ReadIconButton(CosmoIcons.Check, if (on) "Chosen" else "Choose", toggle, on = on)
                        }
                    } else if (rows) BookRow(scope, book) else BookCard(scope, book)
                }
            }
        }
    }
}

/**
 * The library's quick tiles in two columns, each with its count: Bookmarks (opens the tab), Downloaded, Saved for later and
 * Finished (each chooses its shelf).
 */
@Composable
private fun BookTiles(state: ReadingState, modifier: Modifier, onBookmarks: () -> Unit, onShelf: (BookShelf) -> Unit, onHistory: () -> Unit, onFiles: () -> Unit) {
    val tiles: List<Triple<String, Int?, () -> Unit>> = listOf(
        Triple("Bookmarks", state.bookmarks.size, onBookmarks),
        Triple("Downloaded", state.books.size, { onShelf(BookShelf.DOWNLOADED) }),
        Triple("Saved for later", state.saved.size, { onShelf(BookShelf.SAVED) }),
        Triple("Finished", state.finished.count { id -> state.books.any { it.id == id } }, { onShelf(BookShelf.FINISHED) }),
        Triple("History", state.opened.keys.count { id -> state.books.any { it.id == id } }, onHistory),
        Triple("Files", null, onFiles),
    )
    var stats by remember { mutableStateOf(false) }
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
                        if (count != null) Text(count.toString(), style = fonts.label, color = look.dim)
                        else androidx.compose.material3.Icon(CosmoIcons.ChevronRight, null, tint = look.dim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (state.readingMs.isNotEmpty()) {
            val week = ReadingStatistics.week(state, java.time.LocalDate.now()).sumOf { it.second }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(ReadShape.md).background(look.row)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = { stats = true }).padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("books:tile:stats"), verticalAlignment = Alignment.CenterVertically) {
                Text("Reading time", style = ReadType.rowTitle(), color = look.ink, maxLines = 1, modifier = Modifier.weight(1f))
                Text(ReadingProgressMath.duration(week) + " this week", style = fonts.label, color = look.dim)
            }
        }
    }
    if (stats) ReadingStatsSheet(state, onDismiss = { stats = false })
}

/** A book of the library as a row (the list view): its cover, the title, "Author · 35 %". */
@Composable
private fun BookRow(scope: ReadScope, book: ReadingBook) {
    val state by scope.repository.state.collectAsUiState()
    val missing = remember(book.filePath) { !File(book.filePath).isFile }
    ReadCoverRow(book.title, if (missing) "File missing · import it again" else state.bookLine(book), book.coverPath?.let(::File), onClick = { scope.navigation.book = book }, key = book.id,
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
    val groups = state.books.filter { book -> state.bookmarks.any { it.bookId == book.id } || state.highlights.any { it.bookId == book.id } }
        .sortedByDescending { book -> maxOf(state.bookmarks.filter { it.bookId == book.id }.maxOfOrNull { it.createdAtMs } ?: 0L,
            state.highlights.filter { it.bookId == book.id }.maxOfOrNull { it.updatedAtMs } ?: 0L) }
        .map { book -> book to state.bookmarks.filter { it.bookId == book.id }.sortedByDescending { it.createdAtMs } }
    LazyColumn(Modifier.fillMaxSize().testTag("books:bookmarks:list"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
        item(key = "heading") {
            val count = state.bookmarks.size + state.highlights.size
            ReadPageHead("Bookmarks", if (count == 1) "1 passage kept" else "$count passages kept") { ReadRootActions(scope) }
        }
        if (groups.isEmpty()) item(key = "empty") {
            ReadEmpty("No bookmarks yet", "Bookmark a passage from the reader's tools, or select words to highlight them, to come back to them here.")
        }
        groups.forEach { (book, marks) ->
            item(key = "book:${book.id}") {
                ReadCoverRow(book.title, book.authors.joinToString(" · ").ifBlank { if (marks.size == 1) "1 bookmark" else "${marks.size} bookmarks" }, book.coverPath?.let(::File),
                    key = book.id, onClick = { scope.navigation.book = book }, modifier = Modifier.padding(start = ReadMargin, end = ReadMargin, top = 16.dp))
            }
            items(marks, key = { "mark:${it.id}" }) { mark -> BookmarkRow(scope, book, mark) }
            items(com.cosmotune.reading.data.ReadingNotesExport.highlights(state, book.id), key = { "highlight:${it.id}" }) { mark ->
                BookHighlightRow(scope, book, mark, Modifier.padding(start = 24.dp))
            }
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

/** A highlight in the library's lists: tap opens the reader there; the cross removes it, with Undo. */
@Composable
internal fun BookHighlightRow(scope: ReadScope, book: ReadingBook, mark: ReadingHighlight, modifier: Modifier = Modifier) {
    val undo = LocalOrbitUndo.current
    HighlightRow(mark, onClick = { scope.action { scope.repository.saveLocator(book.id, ReadingLocator(mark.href, mark.progression)); scope.open(book) } },
        onRemove = { scope.action {
            scope.repository.removeHighlight(mark.id)
            undo.removedThen("Highlight") { scope.repository.restoreHighlight(mark) }
        } }, modifier = modifier)
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
    var tagging by remember { mutableStateOf(false) }
    if (tagging) BookTagsSheet(book, state.books.flatMap { it.tags }.distinct(), onSave = { scope.action { scope.repository.setTags(book.id, it) } }, onDismiss = { tagging = false })
    var sendingNearby by remember { mutableStateOf(false) }
    if (sendingNearby) BookNearbySheet(scope, listOf(book), onDismiss = { sendingNearby = false })
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
    val save = rememberReadingSave { scope.onError(it) }
    val highlights = com.cosmotune.reading.data.ReadingNotesExport.highlights(state, book.id)
    val hasNotes = highlights.isNotEmpty() || state.bookmarks.any { it.bookId == book.id }
    fun exportNotes(markdown: Boolean) {
        val text = if (markdown) com.cosmotune.reading.data.ReadingNotesExport.markdown(book, state) else com.cosmotune.reading.data.ReadingNotesExport.html(book, state)
        save(com.cosmotune.reading.data.ReadingNotesExport.fileName(book, if (markdown) "md" else "html"), if (markdown) "text/markdown" else "text/html", text.toByteArray())
    }
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
                            com.cosmotune.ui.orbit.OrbitMenu(more, { more = false }, listOfNotNull(
                                com.cosmotune.ui.orbit.OrbitMenuItem("Export notes as Markdown", icon = CosmoIcons.Download) { exportNotes(true) }.takeIf { hasNotes },
                                com.cosmotune.ui.orbit.OrbitMenuItem("Export notes as a web page", icon = CosmoIcons.Download) { exportNotes(false) }.takeIf { hasNotes },
                                com.cosmotune.ui.orbit.OrbitMenuItem("Make a text copy", detail = "A reflowing copy of this PDF's text", icon = CosmoIcons.List) {
                                    scope.action {
                                        val text = kotlinx.coroutines.withContext(Dispatchers.IO) { readingPdfText(File(book.filePath)) }
                                            ?: error("Reading a PDF's text needs Android 15 or later.")
                                        scope.open(scope.repository.importPdfText(book.id, text))
                                    }
                                }.takeIf { book.format == ReadingFormat.PDF },
                                com.cosmotune.ui.orbit.OrbitMenuItem("Tags", detail = book.tags.joinToString(", ").ifBlank { null }, icon = CosmoIcons.Pin) { tagging = true },
                                com.cosmotune.ui.orbit.OrbitMenuItem("Send nearby", detail = "To your other device or LocalSend on the same Wi-Fi", icon = CosmoIcons.Devices) { sendingNearby = true },
                                com.cosmotune.ui.orbit.OrbitMenuItem("Remove from this device", icon = CosmoIcons.Close) { remove() },
                            ), orbitFonts(), look, header = book.title)
                        }
                    },
                )
            }
            if (!File(book.filePath).isFile) item(key = "missing") {
                ReadInlineBanner("This book's file is missing from this device. Import the same file again to read it; its place and notes are kept.",
                    Modifier.padding(horizontal = ReadMargin, vertical = 8.dp).testTag("books:missing"), actionLabel = "Import", onAction = scope.importBooks)
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
            if (highlights.isNotEmpty()) {
                item(key = "highlights:header") { ReadSection("Highlights and notes", subtitle = highlights.size.toString()) }
                items(highlights, key = { "highlight:${it.id}" }) { mark -> BookHighlightRow(scope, book, mark) }
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
    "Format" to (book.originalFormat.ifBlank { book.format.name }),
    book.series.takeIf { it.isNotBlank() }?.let { "Series" to (it + (book.seriesIndex?.let { n -> " · " + (if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()) } ?: "")) },
    book.subjects.takeIf { it.isNotEmpty() }?.let { "Subjects" to it.take(4).joinToString(", ") },
    book.tags.takeIf { it.isNotEmpty() }?.let { "Tags" to it.sorted().joinToString(", ") },
    book.language.takeIf { it.isNotBlank() }?.let { "Language" to it },
    "Added" to java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH).format(java.time.Instant.ofEpochMilli(book.addedAtMs).atZone(zone)),
    "Source" to (book.sourceUrl ?: "On this device"),
)

/** Search (the heading's 🔍): the books in the library that match, then Open Library's results. */
@Composable
private fun BookSearchPage(scope: ReadScope) {
    val state by scope.repository.state.collectAsUiState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<ReadingPublication>()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) { withContext(Dispatchers.Main.immediate) {
        results = emptyList()
        if (query.trim().length < 2) return@withContext
        searching = true
        try { kotlinx.coroutines.delay(400); results = scope.repository.search(query.trim(), 0).publications }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { scope.onError(failure.message ?: "The catalogue could not be searched.") }
        finally { searching = false }
    } }
    val mine = if (query.isBlank()) emptyList() else state.shelf(BookShelf.ALL, query)
    Column(Modifier.fillMaxSize()) {
        ReadBackHead("Search", onBack = { scope.navigation.search = false })
        LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
            horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) { ReadPageHead("Search", "Your books and Open Library", Modifier.readBleed()) }
            item(key = "field", span = { GridItemSpan(maxLineSpan) }) {
                ReadSearchField(query, { query = it.take(500) }, "Search books and authors", Modifier.readBleed(), focusRequester = focus)
            }
            if (mine.isNotEmpty()) {
                item(key = "mine", span = { GridItemSpan(maxLineSpan) }) { ReadOverline("In your library", Modifier.readBleed()) }
                items(mine, key = { "mine:${it.id}" }) { book -> BookCard(scope, book) }
            }
            if (results.isNotEmpty()) {
                item(key = "catalogue", span = { GridItemSpan(maxLineSpan) }) { ReadOverline("Open Library", Modifier.readBleed()) }
                items(results.distinctBy { it.id }, key = { "result:${it.id}" }) { publication -> PublicationCard(scope, publication) }
            } else if (searching) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { ReadSkeletonGrid(Modifier.readBleed()) }
        }
    }
}

/**
 * The first page of trending books on Open Library, which Read's Library and Browse both show: kept by [pages] for the process
 * (and on the disk, for the app's), shown at once and fetched again after [SLOW_FRESH_MS].
 */
internal fun readTrending(pages: PageCache, repository: ReadingRepository): CachedLoad<com.cosmotune.reading.data.ReadingSearchPage> =
    pages.entry("read:trending", SLOW_FRESH_MS, CacheCodec.json(com.cosmotune.reading.data.ReadingSearchPage.serializer())) { repository.trending(0) }

/** A book's own tags: the tags already used as chips to toggle, and a field for a new one. */
@Composable
private fun BookTagsSheet(book: ReadingBook, known: List<String>, onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember(book.id) { mutableStateOf(book.tags) }
    var typed by remember { mutableStateOf("") }
    com.cosmotune.ui.kit.KitSheet(onDismiss = { onSave(chosen); onDismiss() }) {
        ReadSheetHeader("Tags", onDone = { onSave(chosen + typed.trim().takeIf { it.isNotEmpty() }.let { setOfNotNull(it) }); onDismiss() })
        val all = (known + chosen).distinct().sortedBy { it.lowercase() }
        if (all.isNotEmpty()) ReadChipRow(Modifier.padding(vertical = 4.dp)) {
            all.forEach { name -> ReadChip(name, name in chosen, { chosen = if (name in chosen) chosen - name else chosen + name }) }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            com.cosmotune.ui.orbit.OrbitInputField(typed, { typed = it.take(40) }, "A new tag, such as To lend or Book club", modifier = Modifier.fillMaxWidth().testTag("books:tag-field"))
        }
        if (typed.isNotBlank()) ReadChipRow { ReadChip("Add “${typed.trim()}”", false, { chosen = chosen + typed.trim(); typed = "" }, icon = CosmoIcons.Plus) }
        Spacer(Modifier.height(16.dp))
    }
}

/** Choosing the book to read beside the open one: the library's books, the latest read first. */
@Composable
private fun BesideSheet(books: List<ReadingBook>, onPick: (ReadingBook) -> Unit, onDismiss: () -> Unit) {
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Read beside", onDone = onDismiss, doneLabel = "Close")
        if (books.isEmpty()) Text("Add another book to the library first.", style = ReadType.body(), color = orbitLook().dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(books, key = { it.id }) { book ->
                ReadCoverRow(book.title, book.authors.joinToString(" · "), book.coverPath?.let(::File), onClick = { onPick(book) }, key = book.id,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("books:beside:${book.id}"))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Whether all of [this] have [tag] (true), none (false) or some (null). */
internal fun List<ReadingBook>.tagShare(tag: String): Boolean? {
    val count = count { tag in it.tags }
    return when (count) { size -> true; 0 -> false; else -> null }
}

/** Tags for several books at once: a tag all of them have is on, one only some have is marked "some" and left alone until tapped. */
@Composable
private fun BulkTagsSheet(books: List<ReadingBook>, known: List<String>, onSave: (Set<String>, Set<String>) -> Unit, onDismiss: () -> Unit) {
    val start = remember(books) { known.associateWith { books.tagShare(it) } }
    var states by remember(books) { mutableStateOf(start) }
    var typed by remember { mutableStateOf("") }
    fun finish() {
        val extra = typed.trim().takeIf { it.isNotEmpty() }
        val add = states.filter { (name, on) -> on == true && start[name] != true }.keys + setOfNotNull(extra)
        val remove = states.filter { (name, on) -> on == false && start[name] != false }.keys
        if (add.isNotEmpty() || remove.isNotEmpty()) onSave(add, remove)
        onDismiss()
    }
    com.cosmotune.ui.kit.KitSheet(onDismiss = ::finish) {
        ReadSheetHeader(if (books.size == 1) "Tags for 1 book" else "Tags for ${books.size} books", onDone = ::finish)
        if (states.isNotEmpty()) ReadChipRow(Modifier.padding(vertical = 4.dp)) {
            states.keys.sortedBy { it.lowercase() }.forEach { name ->
                val on = states[name]
                ReadChip(if (on == null) "$name · some" else name, on == true, { states = states + (name to (on != true)) }, Modifier.testTag("books:bulk-tag:$name"))
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            com.cosmotune.ui.orbit.OrbitInputField(typed, { typed = it.take(40) }, "A new tag for all of them", modifier = Modifier.fillMaxWidth().testTag("books:bulk-tag-field"))
        }
        if (typed.isNotBlank()) ReadChipRow { ReadChip("Add “${typed.trim()}”", false, { states = states + (typed.trim() to true); typed = "" }, icon = CosmoIcons.Plus) }
        Spacer(Modifier.height(16.dp))
    }
}

/** Saving a web page (its address) or pasted text (a title and the text) as a book. */
@Composable
private fun BookClipSheet(page: Boolean, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val look = orbitLook()
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader(if (page) "Save a web page" else "Paste text", onDone = onDismiss, doneLabel = "Close")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(if (page) "Page address" else "Title", style = ReadType.caption(), color = look.dim)
            Spacer(Modifier.height(8.dp))
            com.cosmotune.ui.orbit.OrbitInputField(first, { first = it.take(2_000) }, if (page) "https://…" else "Pasted text", modifier = Modifier.fillMaxWidth().testTag("books:clip:first"))
            if (!page) {
                Spacer(Modifier.height(12.dp))
                Text("Text", style = ReadType.caption(), color = look.dim)
                Spacer(Modifier.height(8.dp))
                com.cosmotune.ui.orbit.OrbitInputField(second, { second = it.take(5_000_000) }, "Paste here", modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("books:clip:text"),
                    singleLine = false, minLines = 6)
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Only the page's article is kept (its text and pictures), with a link back to it.", style = ReadType.caption(), color = look.dim)
            }
            Spacer(Modifier.height(16.dp))
            ReadButton(if (page) "Save as a book" else "Make a book", { onSave(first, second); onDismiss() },
                enabled = if (page) first.isNotBlank() else second.isNotBlank(), modifier = Modifier.testTag("books:clip:save"))
            Spacer(Modifier.height(16.dp))
        }
    }
}
