@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.cosmotune.manga.ui

import com.cosmotune.ui.orbit.LocalOrbitUndo
import com.cosmotune.ui.orbit.OrbitUndoState
import com.cosmotune.ui.orbit.removeAfterUndo
import com.cosmotune.ui.orbit.showThen
import androidx.compose.foundation.layout.heightIn
import com.cosmotune.cache.PageCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitCardChip
import com.cosmotune.ui.orbit.OrbitCardColors
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Manga mode (Canvas repo, design/cosmotune/batch-be/board.html, section D): Library, Updates, Browse, Sources and History as
 * the page's chips on a phone (the desktop's sidebar when [showNavigation] is false), pushed pages for a source's catalogue,
 * search, downloads and a series, and the reader over everything. [showLeave] adds a way back to Music where the host has no
 * account button; [showHeader] false leaves the account slot to a host that draws its own. Music keeps playing underneath:
 * nothing here touches playback.
 */
@Composable
fun MangaApp(
    contentPadding: PaddingValues = PaddingValues(),
    onMusic: () -> Unit = {},
    repository: MangaRepository? = null,
    showLeave: Boolean = false,
    showHeader: Boolean = true,
    onReaderVisibility: (Boolean) -> Unit = {},
    showNavigation: Boolean = true,
) {
    var retry by remember { mutableIntStateOf(0) }
    val initialized = key(retry) { repository?.let { Result.success(it) } ?: rememberMangaRepository() }
    val ready = initialized.getOrNull()
    if (ready == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            ReadPageHead(mangaText("Manga"), mangaText("Storage"))
            ReadError(mangaText("Check available disk space and file access, then try again."), onRetry = { retry++ }, title = mangaText("Storage is unavailable"))
        }
    } else {
        // The app's repository keeps its catalogue pages for the process and on the disk; one handed in (a test's) keeps its own.
        val pages = remember(ready, repository) { if (repository == null) PageCache.shared else PageCache.of(ready, persistent = false) }
        val locks by ready.store.state.collectAsState()
        MangaSecureScreen(locks.secureScreen)
        MangaLockGate(locks.lockManga) {
            androidx.compose.runtime.CompositionLocalProvider(LocalMangaPages provides pages) {
                MangaContent(contentPadding, onMusic.takeIf { showLeave }, ready, showHeader, onReaderVisibility, showNavigation)
            }
        }
    }
}

/** Where Manga's catalogue pages are kept between visits; null: a memory-only cache of the repository's own. */
internal val LocalMangaPages = androidx.compose.runtime.staticCompositionLocalOf<PageCache?> { null }

/** Where the mode is: its tab, and what is pushed over it (a catalogue, a page, a series, a chapter). */
internal class MangaNavigation(tab: String, book: MangaBook?, chapter: MangaChapter?) {
    var tab by mutableStateOf(tab)
    var source by mutableStateOf<String?>(null)
    var page by mutableStateOf<String?>(null)
    var book by mutableStateOf(book)
    var chapter by mutableStateOf(chapter)
    val atRoot: Boolean get() = source == null && page == null && book == null

    fun select(next: String) { tab = next; source = null; page = null; book = null }

    /** One step back: the series, then a page, then a catalogue, then the Library. False when there is nothing to undo. */
    fun back(): Boolean = when {
        book != null -> { book = null; true }
        page != null -> { page = null; true }
        source != null -> { source = null; true }
        tab != MangaTabs.LIBRARY -> { tab = MangaTabs.LIBRARY; true }
        else -> false
    }
}

internal object MangaPages {
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
}

/** The pieces every page of the mode is handed. */
internal class MangaScope(
    val repository: MangaRepository,
    val navigation: MangaNavigation,
    val updates: MangaUpdateCheck,
    val onMusic: (() -> Unit)?,
    val showAccount: Boolean,
    val bottom: Dp,
    val action: (suspend () -> Unit) -> Job,
    val openBook: (MangaBook) -> Unit,
    val onError: (String?) -> Unit,
) {
    /** Opens [book] at the chapter it continues with (the reader), or its page while its chapters are not known yet. */
    fun continueReading(book: MangaBook, state: MangaLibraryState) {
        val target = mangaContinue(book, state).chapter
        if (target == null || book.unlinkedSourceName() != null) openBook(book)
        else action { repository.store.save(book); navigation.book = book; navigation.chapter = target }
    }
}

@Composable
private fun MangaContent(contentPadding: PaddingValues, onMusic: (() -> Unit)?, repository: MangaRepository, showAccount: Boolean,
    onReaderVisibility: (Boolean) -> Unit, showNavigation: Boolean) {
    MangaBackgroundUpdates(repository)
    LaunchedEffect(repository) { repository.refreshParsers() }
    val state by repository.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var savedTab by rememberSaveable { mutableStateOf(MangaTabs.LIBRARY) }
    val navigation = remember {
        val book = state.books.find { it.id == state.activeBookId }
        MangaNavigation(savedTab, book, book?.chapters?.find { it.id == state.activeChapterId })
    }
    LaunchedEffect(navigation.tab) { savedTab = navigation.tab }
    val readerVisibility by rememberUpdatedState(onReaderVisibility)
    LaunchedEffect(navigation.chapter != null) { readerVisibility(navigation.chapter != null) }
    DisposableEffect(Unit) { onDispose { readerVisibility(false) } }
    // The desktop sidebar's rows and this page's tabs are one choice. The page states where it starts before it listens, so a row
    // chosen in an earlier visit (or in Read) cannot flip it.
    LaunchedEffect(Unit) {
        ModeNav.mangaTab.value = navigation.tab
        ModeNav.mangaTab.collect { name -> val next = MangaTabs.of(name); if (next != navigation.tab) navigation.select(next) }
    }
    LaunchedEffect(navigation.tab) { ModeNav.mangaTab.value = navigation.tab }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(repository.store.recoveryMessage) }
    val updates = remember { MangaUpdateCheck() }
    fun action(block: suspend () -> Unit): Job = scope.launch {
        error = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This action could not be completed" }
    }
    // First discovery opens the official publisher catalogue, without a provider setup step.
    LaunchedEffect(navigation.tab == MangaTabs.BROWSE, state.sourceSetupConsidered) {
        if (navigation.tab == MangaTabs.BROWSE && !state.sourceSetupConsidered) {
            repository.context.parserUpdates.awaitReady()
            try { repository.store.initializeDiscoverySource {
                KotatsuSource.catalogue(repository.context.parsers).find { it.id == "kotatsu:MANGAPLUSPARSER_EN" }?.let { source -> MangaSourceConfig(source.id, name = source.title, language = source.language) }
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The catalogue could not be opened" }
        }
    }
    LaunchedEffect(navigation.source, state.sources) {
        val id = navigation.source ?: return@LaunchedEffect
        if (state.sources.none { it.id == id && it.enabled && MangaSourcePolicy.allowed(it) }) navigation.source = null
    }
    LaunchedEffect(navigation.book?.id, navigation.chapter?.id) {
        try { repository.store.navigate(navigation.book?.id, navigation.chapter?.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Reading position could not be saved" }
    }
    fun openBook(item: MangaBook) {
        navigation.book = state.books.find { it.id == item.id } ?: item
        // A series from a Mihon backup has no source to refresh from until it is found on one.
        if (item.unlinkedSourceName() != null) return
        scope.launch {
            busy = true; error = null
            try { val detail = repository.chapters(item); if (navigation.book?.id == item.id) navigation.book = detail }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    MangaBackHandler(navigation.chapter == null && (!navigation.atRoot || navigation.tab != MangaTabs.LIBRARY)) { navigation.back() }

    // Pages keep their filters and scroll while a series, a page or the reader stands over them.
    val holder = rememberSaveableStateHolder()
    val current = navigation.chapter
    if (current != null) {
        val book = navigation.book
        val next = book?.nextChapter(current, state.readChapters + current.id, state.skipReadChapters)
        Box(Modifier.fillMaxSize().testTag("manga:root")) {
            MangaReader(repository, current, book = book, hasNext = next != null, onBack = { navigation.chapter = null }, onNext = { navigation.chapter = next },
                onChapter = { navigation.chapter = it })
        }
        return
    }
    // The bar and the rail: Library, Updates (its count: the new chapters), Browse, History and More (the sources and their settings).
    val newChapters = remember(state) { mangaUpdateDays(state).sumOf { it.entries.size } }
    ReadModeFrame(MangaTabs.bar, navigation.tab, { navigation.select(it) }, showNavigation, showNavigation && navigation.atRoot, onMusic, showAccount,
        counts = mapOf(MangaTabs.UPDATES to newChapters),
        modifier = Modifier.testTag("manga:root").padding(top = contentPadding.calculateTopPadding())) { chrome ->
        val bottom = chrome.calculateBottomPadding() + contentPadding.calculateBottomPadding()
        val pageScope = MangaScope(repository, navigation, updates, onMusic, showAccount, bottom, ::action, ::openBook) { error = it }
        Column(Modifier.fillMaxSize()) {
            error?.let { message -> ReadInlineBanner(message, Modifier.padding(top = 8.dp), onDismiss = { error = null }) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = orbitLook().accent)
            Box(Modifier.weight(1f)) {
                val book = navigation.book
                when {
                    book != null -> MangaDetails(pageScope, book, onRefresh = { openBook(book) }, onRead = { chapter -> pageScope.action { repository.store.save(book); navigation.chapter = chapter } })
                    navigation.page == MangaPages.SEARCH -> MangaSearchPage(pageScope)
                    navigation.page == MangaPages.DOWNLOADS -> MangaDownloadsPage(pageScope)
                    navigation.source != null -> { val source = requireNotNull(navigation.source); holder.SaveableStateProvider("source:$source") { MangaSourcePage(pageScope, source) } }
                    navigation.tab == MangaTabs.BROWSE -> holder.SaveableStateProvider("tab:browse") { MangaBrowse(pageScope) }
                    navigation.tab == MangaTabs.UPDATES -> holder.SaveableStateProvider("tab:updates") { MangaUpdatesPage(pageScope) }
                    navigation.tab == MangaTabs.SOURCES -> holder.SaveableStateProvider("tab:sources") { MangaSources(repository, bottom) }
                    navigation.tab == MangaTabs.HISTORY -> holder.SaveableStateProvider("tab:history") { MangaHistoryPage(pageScope) }
                    else -> holder.SaveableStateProvider("tab:library") { MangaLibrary(pageScope, busy = busy, onBusy = { busy = it }) }
                }
            }
        }
    }
}

/** The round actions every tab's heading carries: the page's own, then Search. */
@Composable
internal fun androidx.compose.foundation.layout.RowScope.MangaRootActions(scope: MangaScope, page: @Composable () -> Unit = {}) {
    page()
    ReadIconButton(CosmoIcons.Search, "Search", { scope.navigation.page = MangaPages.SEARCH })
}

/** The cover of a series: its address, the first page of a local comic, or a signed cover fetched through its source. */
@Composable
internal fun rememberMangaCover(repository: MangaRepository, book: MangaBook): Any? {
    // A cover the listener chose (a page set as cover) comes first.
    val chosen = repository.store.state.collectAsState().value.customCovers[book.id]?.let { java.io.File(it) }?.takeIf { it.isFile }
    if (chosen != null) return chosen
    val signedCover = book.sourceId.startsWith("template:") || book.sourceId.startsWith("komga:") || book.sourceId.startsWith("suwayomi:")
    var cover by remember(book.id, book.coverUrl) { mutableStateOf<Any?>(book.coverUrl.takeUnless { signedCover }) }
    LaunchedEffect(book.id, book.coverUrl) {
        if (book.sourceId == "local") runCatching {
            book.chapters.firstOrNull()?.let { chapter -> repository.pages(chapter).firstOrNull()?.let { repository.pageFile(it, "local") } }
        }.getOrNull()?.let { cover = it }
        else if (signedCover && book.coverUrl != null) try {
            cover = repository.pageFile(MangaPage("${book.id}:cover:${book.coverUrl}", book.coverUrl), book.sourceId)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { cover = null }
    }
    return cover
}

/** The address of a series' cover when it is a plain one, for the colour of its card. */
internal fun MangaBook.colourUrl(): String? = coverUrl?.takeIf { it.startsWith("http") && !sourceId.startsWith("template:") && !sourceId.startsWith("komga:") && !sourceId.startsWith("suwayomi:") }

/**
 * A series as a tall cover. In the library ([inLibrary]) its corner carries the unread chapters (in peach) and the downloaded ones
 * (board frame 10, Mihon's badges), the reading line shows how far into the chapter, and the "continue" moon off the corner opens
 * the reader where it left off; a tap opens the series.
 */
@Composable
internal fun MangaBookCard(scope: MangaScope, book: MangaBook, meta: String?, modifier: Modifier = Modifier, width: Dp? = null, inLibrary: Boolean = false) {
    val state by scope.repository.store.state.collectAsState()
    val cover = rememberMangaCover(scope.repository, book)
    val progress = if (inLibrary) state.progress[book.id]?.takeIf { !it.finished && it.pageCount > 0 }?.let { (it.page + 1f) / it.pageCount } else null
    val counts = if (inLibrary) mangaCoverCounts(book, state) else null
    Box(modifier) {
        ReadCoverCard(book.title, meta, cover, onClick = { scope.openBook(book) }, width = width, progress = progress, key = book.id,
            onContinue = if (inLibrary && book.chapters.isNotEmpty()) ({ scope.continueReading(book, state) }) else null)
        if (counts != null && (counts.unread > 0 || counts.downloaded > 0)) Row(Modifier.align(Alignment.TopStart).padding(6.dp).testTag("manga:badges:${book.id}"),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (counts.unread > 0) MangaCountBadge(counts.unread, peach = true, label = "${counts.unread} unread")
            if (counts.downloaded > 0) MangaCountBadge(counts.downloaded, peach = false, label = "${counts.downloaded} downloaded")
        }
    }
}

/** A count on a cover's corner: unread in peach, downloaded in the ink. */
@Composable
internal fun MangaCountBadge(count: Int, peach: Boolean, label: String) {
    val look = orbitLook()
    val fonts = com.cosmotune.ui.orbit.orbitFonts()
    val fill = if (peach) look.accent else look.ink
    val ink = if (peach) androidx.compose.ui.graphics.Color(0xFF2A100A) else look.ground
    androidx.compose.material3.Text(if (count > 999) "999+" else count.toString(), style = fonts.label.copy(fontSize = 11.sp), color = ink, maxLines = 1,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(fill).padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = label })
}

/** A new chapter of a library series (Updates, Library): the cover, the series, the chapter, its download. Tap reads it. */
@Composable
internal fun MangaUpdateRow(scope: MangaScope, entry: MangaUpdateEntry) {
    val state by scope.repository.store.state.collectAsState()
    val cover = rememberMangaCover(scope.repository, entry.book)
    val chapter = entry.chapter
    ReadCoverRow(entry.book.title, chapter.title, cover, key = entry.book.id, modifier = Modifier.padding(horizontal = ReadMargin),
        onClick = { scope.action { scope.repository.store.save(entry.book); scope.navigation.book = entry.book; scope.navigation.chapter = chapter } },
        trailing = { MangaDownloadButton(scope, entry.book, chapter, state) })
}

/** The download of one chapter: start, progress, or remove. Local comics have nothing to download. */
@Composable
internal fun MangaDownloadButton(scope: MangaScope, book: MangaBook, chapter: MangaChapter, state: MangaLibraryState) {
    val undo = LocalOrbitUndo.current
    val queue by scope.repository.downloads.state.collectAsState()
    ReadDownloadButton(mangaDownloadState(chapter, state, queue)) {
        if (state.downloads.containsKey(chapter.id)) mangaRemoveDownloads(scope, undo, listOf(chapter), chapter.title) else mangaDownload(scope, book, listOf(chapter))
    }
}

/**
 * Removes the downloads of [chapters] with "Removed “[what]”" and Undo instead of asking: they leave the device's list at once and
 * their pages are freed only once Undo has run out ([MangaRepository.takeOutDownloads]).
 */
internal fun mangaRemoveDownloads(scope: MangaScope, undo: OrbitUndoState?, chapters: Collection<MangaChapter>, what: String) {
    if (chapters.isEmpty()) return
    scope.action {
        val taken = scope.repository.takeOutDownloads(chapters.map { it.id })
        if (taken.isNotEmpty()) undo.removeAfterUndo(what, hide = {}, restore = { scope.repository.putBackDownloads(taken) }) { scope.repository.trimDownloads() }
    }
}

internal fun mangaDownloadState(chapter: MangaChapter, state: MangaLibraryState, queue: MangaQueueState): ReadDownload = when {
    chapter.sourceId == "local" -> ReadDownload.NONE
    state.downloads.containsKey(chapter.id) -> ReadDownload.DONE
    queue.has(chapter.id) -> ReadDownload.RUNNING
    else -> ReadDownload.AVAILABLE
}

/** Puts [chapters] of [book] in the download queue (the series is saved first, so a download adds it to the library). */
internal fun mangaDownload(scope: MangaScope, book: MangaBook, chapters: List<MangaChapter>) {
    if (chapters.isEmpty()) return
    scope.action { scope.repository.store.save(book); scope.repository.downloads.enqueue(book, chapters) }
}

/** The mono line of a series' card: "MANGA · 142 CHAPTERS · ONGOING". */
internal fun mangaCardLabel(book: MangaBook): String = listOfNotNull(
    "Manga",
    book.chapters.size.takeIf { it > 0 }?.let { if (it == 1) "1 chapter" else "$it chapters" },
    book.parserData?.state?.takeIf { it.isNotBlank() }?.lowercase(),
).joinToString(" · ")

/**
 * Library (board frame 10): it opens on the grid. Under the heading the collections as chips with their counts, then the series
 * being read as one Continue row (its cover, "Chapter 9 · Page 6 of 22", Continue), then every series as a cover with its unread
 * (peach) and downloaded counts in the corner. New chapters live in Updates; Downloads and the sort are in the options sheet.
 */
@Composable
private fun MangaLibrary(scope: MangaScope, busy: Boolean, onBusy: (Boolean) -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val grid = rememberLazyGridState()
    var collection by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("recent") }
    var filters by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var genres by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var types by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var options by remember { mutableStateOf(false) }
    var dupes by remember { mutableStateOf(false) }
    val pick = rememberMangaImport { name, open ->
        scope.action { onBusy(true); try { repository.store.save(repository.store.archives.import(name, open)) } finally { onBusy(false) } }
    }
    val pickFolder = rememberMangaFolderImport { result ->
        result.fold(onSuccess = { folder -> scope.action {
            onBusy(true)
            try { repository.store.save(repository.store.archives.importImages(folder.name, folder.images)) } finally { onBusy(false) }
        } }, onFailure = { scope.onError(it.message ?: "Folder could not be opened") })
    }
    val books = mangaLibraryOrder(mangaGenreFilter(mangaLibraryFilter(state.collectionBooks(collection), filters.toSet(), state), genres.toSet(), types.toSet()), sort, state)
    val reading = state.books.filter { book -> state.progress[book.id]?.let { !it.finished || mangaContinue(book, state).resumes } == true }
        .maxByOrNull { state.progress[it.id]?.updatedAtMs ?: 0L }
    val discovery = state.sources.firstOrNull { it.enabled && MangaSourcePolicy.allowed(it) }
    val unread = state.books.sumOf { book -> book.chapters.count { it.id !in state.readChapters } }
    LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.fillMaxSize().readScrollbar(grid.scrollIndicatorState, bottomInset = scope.bottom).testTag("manga:library"), state = grid,
        contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
        horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            ReadPageHead(mangaText("Library"), if (state.books.isEmpty()) mangaText("Your series") else "${state.books.size} series · $unread unread", Modifier.readBleed()) {
                // While incognito the heading says so, and a tap turns it off.
                if (state.incognito) ReadIconButton(CosmoIcons.Close, "Incognito is on. Turn it off", { scope.action { repository.store.update { it.copy(incognito = false) } } },
                    Modifier.testTag("manga:incognito"), on = true)
                ReadIconButton(Icons.Rounded.Tune, "Library options", { options = true }, Modifier.testTag("manga:options"))
                MangaRootActions(scope)
            }
        }
        // An empty library opens on its empty card; the collections and the grid wait until there is a series.
        if (state.books.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.readBleed().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadEmpty("Your library is empty", "Your next story starts here. Import a comic or add a series from Browse.",
                    actionLabel = "Browse sources", onAction = { scope.navigation.select(MangaTabs.BROWSE) })
                if (discovery != null) MangaDiscoverShelf(scope, discovery)
            }
        } else {
            item(key = "collections", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.readBleed()) { MangaCollections(repository, collection) { collection = it } }
            }
            if (reading != null) item(key = "continue:${reading.id}", span = { GridItemSpan(maxLineSpan) }) { MangaContinueRow(scope, reading, state) }
            if (books.isEmpty()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.readBleed()) {
                    val filtered = filters.isNotEmpty() || genres.isNotEmpty() || types.isNotEmpty()
                    ReadEmpty("Nothing here", if (filtered) "No series match these filters." else "No series in this collection yet.",
                        actionLabel = if (filtered) "Clear filters" else null, onAction = { filters = emptyList(); genres = emptyList(); types = emptyList() })
                }
            }
        }
        items(books, key = { it.id }) { book -> MangaBookCard(scope, book, null, inLibrary = true) }
    }
    if (dupes) MangaDuplicatesSheet(scope) { dupes = false }
    if (options) KitSheet(onDismiss = { options = false }, title = mangaText("Library")) {
        ReadSheetRow(mangaText("Import comic"), CosmoIcons.Plus, { options = false; pick() }, enabled = !busy)
        ReadSheetRow(mangaText("Image folder"), Icons.Rounded.Folder, { options = false; pickFolder() }, enabled = !busy)
        ReadSheetRow(mangaText("Downloads"), CosmoIcons.Downloaded, { options = false; scope.navigation.page = MangaPages.DOWNLOADS }, Modifier.testTag("manga:downloads"))
        ReadSheetRow(mangaText("Manage sources"), CosmoIcons.Settings, { options = false; scope.navigation.select(MangaTabs.SOURCES) })
        val duplicates = remember(state.books) { mangaDuplicates(state) }
        if (duplicates.isNotEmpty()) ReadSheetRow(mangaText("Duplicates") + " · ${duplicates.size}", CosmoIcons.List, { options = false; dupes = true }, Modifier.testTag("manga:duplicates"))
        ReadSheetSection(mangaText("Sort by")) {
            ReadChipRow(inset = 0.dp) {
                listOf("recent", "title", "added", "unread", "latest", "chapters").forEach { value -> ReadChip(mangaText(sortLabel(value)), sort == value, { sort = value }) }
            }
        }
        ReadSheetSection(mangaText("Filter")) {
            ReadChipRow(inset = 0.dp) {
                MangaLibraryFilters.all.forEach { filter ->
                    ReadChip(mangaText(MangaLibraryFilters.label(filter)), filter in filters, { filters = if (filter in filters) filters - filter else filters + filter },
                        Modifier.testTag("manga:filter:$filter"))
                }
            }
        }
        val kinds = remember(state.books) { state.books.mapNotNull(::mangaType).distinct().sorted() }
        if (kinds.size > 1) ReadSheetSection(mangaText("Type")) {
            ReadChipRow(inset = 0.dp) {
                kinds.forEach { kind -> ReadChip(mangaText(kind), kind in types, { types = if (kind in types) types - kind else types + kind }, Modifier.testTag("manga:type:$kind")) }
            }
        }
        val genreChips = remember(state.books) { mangaLibraryGenres(state.books) }
        if (genreChips.isNotEmpty()) ReadSheetSection(mangaText("Genre")) {
            ReadChipRow(inset = 0.dp) {
                genreChips.forEach { genre -> ReadChip(genre, genre in genres, { genres = if (genre in genres) genres - genre else genres + genre }, Modifier.testTag("manga:genre:$genre")) }
            }
        }
        com.cosmotune.ui.orbit.OrbitSwitchRow(mangaText("Incognito"), state.incognito, { on -> scope.action { repository.store.update { it.copy(incognito = on) } } },
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), detail = mangaText("Reading leaves no history, place or read marks"))
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The library's duplicates: each group of series that look the same, with Keep on each one. Keeping one merges the others into it
 * (read chapters, place, collections and reading-list links) and removes them; Undo puts the library back.
 */
@Composable
private fun MangaDuplicatesSheet(scope: MangaScope, onDismiss: () -> Unit) {
    val state by scope.repository.store.state.collectAsState()
    val groups = mangaDuplicates(state)
    val undo = LocalOrbitUndo.current
    val words = mangaText("Series merged")
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader(mangaText("Duplicates"), onDone = onDismiss)
        androidx.compose.material3.Text(mangaText("These look like the same series twice. Keep one: the others' read chapters, place and collections move to it."),
            style = ReadType.meta(), color = orbitLook().dim, modifier = Modifier.padding(horizontal = 20.dp))
        if (groups.isEmpty()) ReadEmpty(mangaText("No duplicates"), mangaText("Every series in your library is there once."))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            groups.forEachIndexed { index, group ->
                item(key = "group:$index") { ReadOverline(group.first().title) }
                items(group, key = { "dupe:${it.id}" }) { book ->
                    val cover = rememberMangaCover(scope.repository, book)
                    val source = state.sources.find { it.id == book.sourceId }?.name ?: if (book.sourceId == "local") "On this device" else book.unlinkedSourceName() ?: book.sourceId
                    val read = book.chapters.count { it.id in state.readChapters }
                    ReadCoverRow(book.title, "$source · $read read", cover, key = book.id, modifier = Modifier.padding(horizontal = ReadMargin).testTag("manga:dupe:${book.id}"),
                        onClick = { onDismiss(); scope.openBook(book) }) {
                        ReadTextButton(mangaText("Keep"), { scope.action {
                            val before = scope.repository.store.state.value
                            scope.repository.store.update { it.merged(book, group) }
                            undo.showThen(words) { scope.repository.store.update { before } }
                        } }, Modifier.testTag("manga:dupe:keep:${book.id}"))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun sortLabel(sort: String): String = when (sort) {
    "title" -> "Title"; "added" -> "Added"; "unread" -> "Unread"; "latest" -> "Latest chapter"; "chapters" -> "Chapters"; else -> "Last read"
}

/**
 * The series being read as one row over the grid (board frame 10): its cover, its title, where the reader stands ("Chapter 9 ·
 * Page 6 of 22"), and Continue, the page's only peach button. A tap on the row opens the series.
 */
@Composable
private fun MangaContinueRow(scope: MangaScope, book: MangaBook, state: MangaLibraryState) {
    val cover = rememberMangaCover(scope.repository, book)
    val look = orbitLook()
    Box(Modifier.readBleed().padding(horizontal = ReadMargin)) {
        ReadCoverRow(book.title, mangaContinueLine(book, state).orEmpty(), cover, onClick = { scope.openBook(book) }, key = book.id,
            modifier = Modifier.testTag("manga:continue-row"), highlighted = true) {
            OrbitCardChip(mangaText("Continue"), OrbitCardColors(androidx.compose.ui.graphics.Color(0xFF2A100A), look.accent), onClick = { scope.continueReading(book, state) },
                solid = true, modifier = Modifier.testTag("manga:continue"))
        }
    }
}

/** The first page of an enabled catalogue, as a shelf: discovery without a provider prompt or a search. */
@Composable
internal fun MangaDiscoverShelf(scope: MangaScope, source: MangaSourceConfig) {
    val catalogue = rememberSourceCatalogue(scope.repository, source.id, "")
    Column(Modifier.fillMaxWidth()) {
        ReadSection(mangaText("Popular on") + " " + source.name, onSeeAll = { scope.navigation.source = source.id })
        when {
            catalogue.results.isNotEmpty() -> LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                items(catalogue.results.take(15), key = { "discover:${it.id}" }) { book -> MangaBookCard(scope, book, null, width = 112.dp) }
            }
            catalogue.loading -> ReadSkeletonGrid(rows = 1)
            catalogue.error != null -> ReadInlineBanner(requireNotNull(catalogue.error), actionLabel = mangaText("Open source"), onAction = { scope.navigation.source = source.id })
        }
    }
}

/** What Manga shows while its lock waits: the mode's name and Unlock. */
@Composable
internal fun MangaLockedPage(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("manga:locked"), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Text(mangaText("Manga is locked"), style = ReadType.titleLarge(), color = orbitLook().ink)
        ReadButton(mangaText("Unlock"), onUnlock, modifier = Modifier.testTag("manga:unlock"))
    }
}
