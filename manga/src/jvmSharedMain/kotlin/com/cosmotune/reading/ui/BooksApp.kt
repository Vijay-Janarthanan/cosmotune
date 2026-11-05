package com.cosmotune.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitEmptyState
import com.cosmotune.ui.kit.KitErrorState
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.ui.kit.collapseFraction
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * The Read mode (books, DESIGN_SYSTEM.md 6.5): Home, Browse, Bookmarks and Library in the dock (a rail from 600 dp; the desktop's
 * sidebar when [showNavigation] is false), a book's page and a catalogue entry's page pushed over them, and the reader over
 * everything. [repository] defaults to the reading library next to Manga's storage. Music keeps playing underneath.
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
    if (repository != null) return ReadContent(repository, contentPadding, onMusic.takeIf { showLeave }, onReaderVisibility, showNavigation, showAccount)
    var retry by remember { mutableIntStateOf(0) }
    val manga = key(retry) { rememberMangaRepository() }
    val books = remember(manga, retry) { runCatching {
        val ready = manga.getOrThrow()
        ReadingRepository(File(ready.store.root.parentFile, "reading"), protection = ready.store.protection)
    } }
    val ready = books.getOrNull()
    if (ready == null) Column(Modifier.fillMaxSize().padding(contentPadding)) {
        ReadTopBar("Read", collapse = { 0f }, leading = LocalReadModeSwitcher.current, actions = { if (showAccount) ReadAccountAction(onMusic.takeIf { showLeave }) })
        KitErrorState(books.exceptionOrNull()?.message ?: "Check storage access and try again.", onRetry = { retry++ }, title = "Your reading library could not be opened")
    } else ReadContent(ready, contentPadding, onMusic.takeIf { showLeave }, onReaderVisibility, showNavigation, showAccount)
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
        tab != BookTabs.HOME -> { tab = BookTabs.HOME; true }
        else -> false
    }
}

/** The pieces every page of Read is handed. */
internal class ReadScope(
    val repository: ReadingRepository,
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
private fun ReadContent(repository: ReadingRepository, contentPadding: PaddingValues, onMusic: (() -> Unit)?, onReaderVisibility: (Boolean) -> Unit,
    showNavigation: Boolean, showAccount: Boolean) {
    val scope = rememberCoroutineScope()
    var savedTab by rememberSaveable { mutableStateOf(BookTabs.HOME) }
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
    MangaBackHandler(!navigation.atRoot || navigation.tab != BookTabs.HOME) { navigation.back() }
    ReadModeScaffold(readTabs(), BookTabs.all.indexOf(navigation.tab), { navigation.select(BookTabs.all[it]) }, showNavigation, showTabs = true,
        modifier = Modifier.testTag("books:root")) { chrome ->
        val bottom = chrome.calculateBottomPadding() + contentPadding.calculateBottomPadding()
        val page = ReadScope(repository, navigation, onMusic, showAccount, bottom, ::action, ::open, { error = it }, busy, pick)
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            error?.let { message -> ReadInlineBanner(message, Modifier.padding(top = 8.dp), onDismiss = { error = null }) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Color(LocalCosmoColors.current.palette.accent))
            Box(Modifier.weight(1f)) {
                val publication = navigation.publication
                val book = navigation.book
                when {
                    publication != null -> BookDetails(page, publication)
                    book != null -> BookLocalDetails(page, book)
                    navigation.search -> BookSearchPage(page)
                    navigation.tab == BookTabs.BROWSE -> holder.SaveableStateProvider("tab:browse") { BookDiscover(page) }
                    navigation.tab == BookTabs.BOOKMARKS -> holder.SaveableStateProvider("tab:bookmarks") { BookBookmarks(page) }
                    navigation.tab == BookTabs.LIBRARY -> holder.SaveableStateProvider("tab:library") { BookLibrary(page) }
                    else -> holder.SaveableStateProvider("tab:home") { BookHome(page) }
                }
            }
        }
    }
}

@Composable
private fun readTabs(): List<CosmoTab> = listOf(
    CosmoTab("Home", CosmoIcons.Home, CosmoIcons.HomeFill),
    CosmoTab("Browse", Icons.Outlined.Explore, Icons.Rounded.Explore),
    CosmoTab("Bookmarks", Icons.Outlined.BookmarkBorder, Icons.Rounded.Bookmark),
    CosmoTab("Library", Icons.Outlined.CollectionsBookmark, Icons.Rounded.CollectionsBookmark),
)

/** The trailing actions of a root page: its own, then Search, then the account. */
@Composable
internal fun RowScope.ReadRootActions(scope: ReadScope, page: @Composable () -> Unit = {}) {
    page()
    KitIconButton(CosmoIcons.Search, "Search", { scope.navigation.search = true })
    if (scope.showAccount) ReadAccountAction(scope.onMusic)
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

@Composable
private fun BookCard(scope: ReadScope, book: ReadingBook, width: Dp? = null) {
    val state by scope.repository.state.collectAsState()
    ReadPosterCard(book.title, state.bookLine(book), book.coverPath?.let(::File), onClick = { scope.navigation.book = book }, width = width,
        badges = ReadCoverBadges(progress = state.fraction(book).takeIf { book.id !in state.finished }))
}

@Composable
internal fun PublicationCard(scope: ReadScope, publication: ReadingPublication, width: Dp? = null) {
    ReadPosterCard(publication.title, publication.authors.firstOrNull(), publication.coverUrl, onClick = { scope.navigation.publication = publication }, width = width)
}

/** Home (slot 1): the one book to resume, then what was added lately, then today's trending books on Open Library. */
@Composable
private fun BookHome(scope: ReadScope) {
    val state by scope.repository.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val list = rememberLazyListState()
    val threshold = rememberCollapseThreshold()
    val resume = state.books.filter { it.id in state.progress && it.id !in state.finished }.maxByOrNull { state.progress[it.id]?.updatedAtMs ?: 0L }
    val recent = state.books.filter { it.id !in state.progress }.sortedByDescending { it.addedAtMs }.take(12)
    var trending by remember { mutableStateOf(emptyList<ReadingPublication>()) }
    LaunchedEffect(Unit) {
        trending = try { scope.repository.trending(0).publications.take(15) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
    }
    Column(Modifier.fillMaxSize()) {
        ReadTopBar("Home", collapse = { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) },
            leading = LocalReadModeSwitcher.current, actions = { ReadRootActions(scope) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("books:home"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            if (resume != null) item(key = "resume") {
                val fraction = state.fraction(resume) ?: 0f
                KitSectionHeader("Continue reading")
                Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin).clip(ReadShape.md).background(Color(palette.surface))
                    .clickable(role = Role.Button) { scope.navigation.book = resume }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReadCover(resume.coverPath?.let(::File), resume.title, Modifier.width(96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(resume.title, style = LocalCosmoType.current.forText(ReadType.titleMedium(), resume.title), color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (resume.authors.isNotEmpty()) Text(resume.authors.joinToString(" · "), style = ReadType.meta(), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${resume.format.name} · ${(fraction * 100).toInt()} %", style = ReadType.meta(), color = Color(palette.textSecondary))
                        ReadProgressBar(fraction)
                        KitButton("Resume", { scope.open(resume) }, icon = CosmoIcons.Play)
                    }
                }
            }
            if (recent.isNotEmpty()) item(key = "recent") {
                KitSectionHeader("Recently added", onSeeAll = { scope.navigation.select(BookTabs.LIBRARY) })
                LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                    items(recent, key = { "recent:${it.id}" }) { book -> BookCard(scope, book, width = 112.dp) }
                }
            }
            if (trending.isNotEmpty()) item(key = "trending") {
                KitSectionHeader("Trending on Open Library", onSeeAll = { scope.navigation.select(BookTabs.BROWSE) })
                LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                    items(trending, key = { "trending:${it.id}" }) { publication -> PublicationCard(scope, publication, width = 112.dp) }
                }
            }
            if (state.books.isEmpty()) item(key = "empty") {
                KitEmptyState("Your next read starts here", "Import an EPUB or PDF, or find books from public libraries and public-domain catalogues.",
                    actionLabel = "Import books", onAction = scope.importBooks)
            }
        }
    }
}

/** A 3 dp reading-progress bar in the accent. */
@Composable
internal fun ReadProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    Box(modifier.fillMaxWidth().height(3.dp).clip(ReadShape.xs).background(Color(palette.textPrimary).copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Color(palette.accent)))
    }
}

/** Library (slot 4, after Librum and Mihon): the shelves as chips over a 2:3 grid; Import is the page's action. */
@Composable
private fun BookLibrary(scope: ReadScope) {
    val state by scope.repository.state.collectAsState()
    val grid = rememberLazyGridState()
    val threshold = rememberCollapseThreshold()
    var shelf by rememberSaveable { mutableStateOf(BookShelf.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val books = state.shelf(shelf, query)
    val saved = if (shelf == BookShelf.SAVED) state.saved.filter { query.isBlank() || (it.title + " " + it.authors.joinToString(" ")).contains(query.trim(), true) } else emptyList()
    Column(Modifier.fillMaxSize()) {
        ReadTopBar("Library", collapse = { collapseFraction(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, threshold) },
            leading = LocalReadModeSwitcher.current, actions = { ReadRootActions(scope) {
                KitIconButton(CosmoIcons.Plus, "Import EPUB or PDF", { if (!scope.busy) scope.importBooks() }, Modifier.testTag("books:import"))
            } })
        LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.weight(1f).fillMaxWidth().testTag("books:library"), state = grid,
            contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
            horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                ReadSearchField(query, { query = it }, "Search your books", Modifier.readBleed().testTag("books:library-search"))
            }
            item(key = "shelves", span = { GridItemSpan(maxLineSpan) }) {
                ReadChipRow(Modifier.readBleed().padding(vertical = 4.dp)) {
                    BookShelf.entries.forEach { item -> ReadChip(item.label, shelf == item, { shelf = item }, Modifier.testTag("books:shelf:${item.label}")) }
                }
            }
            if (shelf == BookShelf.SAVED) {
                if (saved.isEmpty()) item(key = "saved:empty", span = { GridItemSpan(maxLineSpan) }) {
                    KitEmptyState("Save something for later", "Books you save from Browse appear here. Import an EPUB or PDF to read your own copy.", icon = Icons.Outlined.BookmarkBorder)
                }
                items(saved, key = { "saved:${it.id}" }) { publication -> PublicationCard(scope, publication) }
            } else {
                if (books.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    KitEmptyState(
                        if (query.isNotBlank()) "No matching books" else when (shelf) {
                            BookShelf.READING -> "Start your next chapter"; BookShelf.FINISHED -> "A shelf of stories completed"; BookShelf.UNREAD -> "Everything here is started"
                            else -> "Your next read starts here"
                        },
                        if (query.isNotBlank()) "Try a title or author in your library." else "Import an EPUB or PDF, or find books from public libraries and public-domain catalogues.",
                        actionLabel = if (query.isBlank() && state.books.isEmpty()) "Import books" else null, onAction = scope.importBooks,
                    )
                }
                items(books, key = { it.id }) { book -> BookCard(scope, book) }
            }
        }
    }
}

/** Bookmarks (slot 3): every bookmark by book; tap opens the reader there. */
@Composable
private fun BookBookmarks(scope: ReadScope) {
    val state by scope.repository.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val list = rememberLazyListState()
    val threshold = rememberCollapseThreshold()
    val groups = state.bookmarks.groupBy { it.bookId }.mapNotNull { (id, marks) -> state.books.find { it.id == id }?.let { it to marks.sortedByDescending { mark -> mark.createdAtMs } } }
    Column(Modifier.fillMaxSize()) {
        ReadTopBar("Bookmarks", collapse = { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) },
            leading = LocalReadModeSwitcher.current, actions = { ReadRootActions(scope) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("books:bookmarks:list"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            if (groups.isEmpty()) item(key = "empty") {
                KitEmptyState("No bookmarks yet", "Bookmark a passage from the reader's tools to come back to it here.", icon = Icons.Outlined.BookmarkBorder)
            }
            groups.forEach { (book, marks) ->
                item(key = "book:${book.id}") {
                    Row(Modifier.fillMaxWidth().padding(start = ReadMargin, end = ReadMargin, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReadCover(book.coverPath?.let(::File), book.title, Modifier.width(40.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(book.title, style = ReadType.titleMedium(), color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).semantics { heading() })
                    }
                }
                items(marks, key = { "mark:${it.id}" }) { mark -> BookmarkRow(scope, book, mark) }
            }
        }
    }
}

@Composable
internal fun BookmarkRow(scope: ReadScope, book: ReadingBook, mark: ReadingBookmark) {
    val palette = LocalCosmoColors.current.palette
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
        scope.action { scope.repository.saveLocator(book.id, mark.locator); scope.open(book) }
    }.padding(start = 72.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(mark.name, style = ReadType.body(), color = Color(palette.textPrimary), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(agoLabel(mark.createdAtMs).replaceFirstChar(Char::uppercase), style = ReadType.meta(), color = Color(palette.textSecondary))
        }
        KitIconButton(CosmoIcons.Close, "Remove bookmark", { scope.action { scope.repository.removeBookmark(mark.id) } })
    }
}

/** A book on this device (DESIGN_SYSTEM.md 6.5, "Book details"): the cover-washed header, Continue or Read, finish and remove, its bookmarks. */
@Composable
private fun BookLocalDetails(scope: ReadScope, initial: ReadingBook) {
    val state by scope.repository.state.collectAsState()
    val book = state.books.find { it.id == initial.id } ?: initial
    val palette = LocalCosmoColors.current.palette
    val list = rememberLazyListState()
    val threshold = rememberCollapseThreshold()
    val cover = book.coverPath?.let(::File)
    val fraction = state.fraction(book)
    val finished = book.id in state.finished
    var remove by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("books:book"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "header") {
                Box {
                    ReadCoverWash(cover)
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp)) {
                        BookHeader(book.title, book.authors, cover, listOf(book.format.name, "On this device") + listOfNotNull(book.language.takeIf { it.isNotBlank() }))
                        if (fraction != null && !finished) ReadProgressBar(fraction, Modifier.padding(horizontal = ReadMargin, vertical = 8.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ReadMargin, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KitButton(if (fraction != null && !finished) "Continue · ${(fraction * 100).toInt()} %" else "Read", { scope.open(book) }, icon = CosmoIcons.Play,
                                modifier = Modifier.testTag("books:read:${book.id}"))
                            KitButton(if (finished) "Mark unfinished" else "Mark finished", { scope.action { scope.repository.markFinished(book.id, !finished) } }, kind = KitButtonKind.TONAL, icon = CosmoIcons.Check)
                            KitButton("Remove", { remove = true }, kind = KitButtonKind.TONAL, icon = CosmoIcons.Close)
                        }
                        if (book.description.isNotBlank()) Text(org.jsoup.Jsoup.parse(book.description).text(), style = ReadType.body(), color = Color(palette.textSecondary),
                            maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = ReadMargin, vertical = 8.dp))
                    }
                }
            }
            val marks = state.bookmarks.filter { it.bookId == book.id }
            if (marks.isNotEmpty()) {
                item(key = "marks:header") { KitSectionHeader("Bookmarks") }
                items(marks, key = { "mark:${it.id}" }) { mark -> BookmarkRow(scope, book, mark) }
            }
            item(key = "facts") {
                ReadOverline("Details")
                listOfNotNull("Format" to book.format.name, book.language.takeIf { it.isNotBlank() }?.let { "Language" to it }, book.sourceUrl?.let { "Source" to it }).forEach { (name, value) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = ReadMargin), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = ReadType.rowTitle(), color = Color(palette.textPrimary), modifier = Modifier.weight(1f))
                        Text(value, style = ReadType.meta(), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f))
                    }
                    ReadHairline()
                }
            }
        }
        ReadTopBar(book.title, collapse = { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold * 4f) }, large = false,
            onBack = { scope.navigation.book = null })
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Remove this book?") },
        text = { Text("The imported copy, reading position and bookmarks will be removed from this device.") },
        confirmButton = { TextButton(onClick = { remove = false; scope.action { scope.repository.removeBook(book.id); scope.navigation.book = null } }) { Text("Remove book", color = Color(palette.danger)) } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("Keep book") } })
}

/** The header of a book page: the 2:3 cover beside the title, the authors and up to three pills. */
@Composable
internal fun BookHeader(title: String, authors: List<String>, cover: Any?, pills: List<String>) {
    val palette = LocalCosmoColors.current.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 8.dp)) {
        ReadCover(cover, title, Modifier.width(120.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = LocalCosmoType.current.forText(ReadType.headline(), title), color = Color(palette.textPrimary), maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            if (authors.isNotEmpty()) Text(authors.joinToString(" · "), style = ReadType.body(), color = Color(palette.textSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { pills.take(3).forEach { ReadMetaPill(it) } }
        }
    }
}

/** Search (the top bar's 🔍): the books in the library that match, then Open Library's results. */
@Composable
private fun BookSearchPage(scope: ReadScope) {
    val state by scope.repository.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<ReadingPublication>()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        results = emptyList()
        if (query.trim().length < 2) return@LaunchedEffect
        searching = true
        try { kotlinx.coroutines.delay(400); results = scope.repository.search(query.trim(), 0).publications }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { scope.onError(failure.message ?: "The catalogue could not be searched.") }
        finally { searching = false }
    }
    val mine = if (query.isBlank()) emptyList() else state.shelf(BookShelf.ALL, query)
    Column(Modifier.fillMaxSize()) {
        ReadTopBar("Search", collapse = { 1f }, large = false, onBack = { scope.navigation.search = false })
        LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
            horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "field", span = { GridItemSpan(maxLineSpan) }) {
                ReadSearchField(query, { query = it.take(500) }, "Search books and authors", Modifier.readBleed().focusRequester(focus))
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
