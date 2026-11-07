package com.cosmotune.manga.ui

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
import com.cosmotune.ui.orbit.OrbitCoverTitleCard
import com.cosmotune.ui.orbit.orbitLook
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
        androidx.compose.runtime.CompositionLocalProvider(LocalMangaPages provides pages) {
            MangaContent(contentPadding, onMusic.takeIf { showLeave }, ready, showHeader, onReaderVisibility, showNavigation)
        }
    }
}

/** Where Manga's catalogue pages are kept between visits; null: a memory-only cache of the repository's own. */
internal val LocalMangaPages = androidx.compose.runtime.staticCompositionLocalOf<PageCache?> { null }

/** A download in progress: which chapters it covers and what it has done so far. */
internal class MangaTransfer {
    var job by mutableStateOf<Job?>(null)
    var progress by mutableStateOf("")
    var chapters by mutableStateOf(emptySet<String>())
    val active: Boolean get() = job?.isActive == true
}

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
    val transfer: MangaTransfer,
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
        if (target == null) openBook(book)
        else action { repository.store.save(book); navigation.book = book; navigation.chapter = target }
    }
}

@Composable
private fun MangaContent(contentPadding: PaddingValues, onMusic: (() -> Unit)?, repository: MangaRepository, showAccount: Boolean,
    onReaderVisibility: (Boolean) -> Unit, showNavigation: Boolean) {
    MangaBackgroundUpdates(repository)
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
    val transfer = remember { MangaTransfer() }
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
            try { repository.store.initializeDiscoverySource {
                KotatsuSource.catalogue().find { it.id == "kotatsu:MANGAPLUSPARSER_EN" }?.let { source -> MangaSourceConfig(source.id, name = source.title, language = source.language) }
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
        val next = book?.nextChapter(current)
        Box(Modifier.fillMaxSize().testTag("manga:root")) {
            MangaReader(repository, current, book = book, hasNext = next != null, onBack = { navigation.chapter = null }, onNext = { navigation.chapter = next })
        }
        return
    }
    ReadModeFrame(MangaTabs.all, navigation.tab, { navigation.select(it) }, showNavigation && navigation.atRoot, onMusic, showAccount,
        modifier = Modifier.testTag("manga:root").padding(top = contentPadding.calculateTopPadding())) { chrome ->
        val bottom = chrome.calculateBottomPadding() + contentPadding.calculateBottomPadding()
        val pageScope = MangaScope(repository, navigation, transfer, updates, onMusic, showAccount, bottom, ::action, ::openBook) { error = it }
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
    val signedCover = book.sourceId.startsWith("template:") || book.sourceId.startsWith("komga:")
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
internal fun MangaBook.colourUrl(): String? = coverUrl?.takeIf { it.startsWith("http") && !sourceId.startsWith("template:") && !sourceId.startsWith("komga:") }

/**
 * A series as a tall cover. In the library ([inLibrary]) its line says what is unread or new and whether every chapter is on this
 * device, the reading line shows how far into the chapter, and the "continue" moon off the corner opens the reader where it left
 * off; a tap opens the series.
 */
@Composable
internal fun MangaBookCard(scope: MangaScope, book: MangaBook, meta: String?, modifier: Modifier = Modifier, width: Dp? = null, inLibrary: Boolean = false) {
    val state by scope.repository.store.state.collectAsState()
    val cover = rememberMangaCover(scope.repository, book)
    val progress = if (inLibrary) state.progress[book.id]?.takeIf { !it.finished && it.pageCount > 0 }?.let { (it.page + 1f) / it.pageCount } else null
    val downloaded = inLibrary && book.sourceId != "local" && book.chapters.isNotEmpty() && book.chapters.all { state.downloads.containsKey(it.id) }
    val line = listOfNotNull(meta, mangaText("Downloaded").takeIf { downloaded }).joinToString(" · ").ifBlank { null }
    ReadCoverCard(book.title, line, cover, onClick = { scope.openBook(book) }, modifier = modifier, width = width, progress = progress, key = book.id,
        onContinue = if (inLibrary && book.chapters.isNotEmpty()) ({ scope.continueReading(book, state) }) else null)
}

/** A new chapter of a library series (Updates, Library): the cover, the series, the chapter, its download. Tap reads it. */
@Composable
internal fun MangaUpdateRow(scope: MangaScope, entry: MangaUpdateEntry) {
    val state by scope.repository.store.state.collectAsState()
    val cover = rememberMangaCover(scope.repository, entry.book)
    val chapter = entry.chapter
    ReadCoverRow(entry.book.title, chapter.title, cover, key = entry.book.id, modifier = Modifier.padding(horizontal = ReadMargin, vertical = 4.dp),
        onClick = { scope.action { scope.repository.store.save(entry.book); scope.navigation.book = entry.book; scope.navigation.chapter = chapter } },
        trailing = { MangaDownloadButton(scope, entry.book, chapter, state) })
}

/** The download of one chapter: start, progress, or remove. Local comics have nothing to download. */
@Composable
internal fun MangaDownloadButton(scope: MangaScope, book: MangaBook, chapter: MangaChapter, state: MangaLibraryState) {
    ReadDownloadButton(mangaDownloadState(scope, chapter, state)) {
        if (state.downloads.containsKey(chapter.id)) scope.action { scope.repository.removeDownload(chapter.id) } else mangaDownload(scope, book, listOf(chapter))
    }
}

internal fun mangaDownloadState(scope: MangaScope, chapter: MangaChapter, state: MangaLibraryState): ReadDownload = when {
    chapter.sourceId == "local" -> ReadDownload.NONE
    state.downloads.containsKey(chapter.id) -> ReadDownload.DONE
    scope.transfer.active && chapter.id in scope.transfer.chapters -> ReadDownload.RUNNING
    else -> ReadDownload.AVAILABLE
}

/** Downloads [chapters] of [book] one after another (the series is saved first, so a download adds it to the library). */
internal fun mangaDownload(scope: MangaScope, book: MangaBook, chapters: List<MangaChapter>) {
    if (scope.transfer.active || chapters.isEmpty()) return
    scope.transfer.chapters = chapters.map { it.id }.toSet()
    scope.transfer.job = scope.action {
        try {
            scope.repository.store.save(book)
            chapters.forEachIndexed { index, item ->
                scope.repository.download(item) { done, total ->
                    scope.transfer.progress = if (chapters.size == 1) "${item.title}: $done / $total pages" else "Chapter ${index + 1} of ${chapters.size} · ${item.title}: $done / $total pages"
                }
            }
        } finally { scope.transfer.chapters = emptySet(); scope.transfer.progress = "" }
    }
}

/** The mono line of a series' card: "MANGA · 142 CHAPTERS · ONGOING". */
internal fun mangaCardLabel(book: MangaBook): String = listOfNotNull(
    "Manga",
    book.chapters.size.takeIf { it > 0 }?.let { if (it == 1) "1 chapter" else "$it chapters" },
    book.parserData?.state?.takeIf { it.isNotBlank() }?.lowercase(),
).joinToString(" · ")

/** "Next: Chapter 87 · The comet road", or where the reader stands in it ("Page 6 of 22 · …"). */
internal fun mangaNextLine(book: MangaBook, state: MangaLibraryState): String? {
    val chapter = mangaContinue(book, state).chapter ?: return null
    val progress = state.progress[book.id]?.takeIf { it.chapterId == chapter.id && !it.finished && it.pageCount > 0 }
    return (if (progress != null) "Page ${progress.page + 1} of ${progress.pageCount} · " else "Next: ") + chapter.title
}

/**
 * Library (board D): the series being read as the colour card with Continue in its bite and its next chapters as rows (the next
 * one peach), the others in progress as covers, the new chapters, then the collections as chips over the grid of every series.
 */
@Composable
private fun MangaLibrary(scope: MangaScope, busy: Boolean, onBusy: (Boolean) -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val grid = rememberLazyGridState()
    var collection by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("recent") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    val pick = rememberMangaImport { name, open ->
        scope.action { onBusy(true); try { repository.store.save(repository.store.archives.import(name, open)) } finally { onBusy(false) } }
    }
    val pickFolder = rememberMangaFolderImport { result ->
        result.fold(onSuccess = { folder -> scope.action {
            onBusy(true)
            try { repository.store.save(repository.store.archives.importImages(folder.name, folder.images)) } finally { onBusy(false) }
        } }, onFailure = { scope.onError(it.message ?: "Folder could not be opened") })
    }
    val books = state.collectionBooks(collection)
        .filter { !unreadOnly || it.chapters.any { chapter -> chapter.id !in state.readChapters } }
        .let { list -> when (sort) {
            "title" -> list.sortedBy { it.title.lowercase() }
            "added" -> list.sortedByDescending { it.addedAtMs }
            "unread" -> list.sortedByDescending { book -> book.chapters.count { it.id !in state.readChapters } }
            else -> list.sortedByDescending { state.progress[it.id]?.updatedAtMs ?: it.addedAtMs }
        } }
    val reading = state.books.filter { book -> state.progress[book.id]?.let { !it.finished || mangaContinue(book, state).resumes } == true }
        .sortedByDescending { state.progress[it.id]?.updatedAtMs ?: 0L }
    val hero = reading.firstOrNull()
    val fresh = mangaUpdates(state).take(3)
    val discovery = state.sources.firstOrNull { it.enabled && MangaSourcePolicy.allowed(it) }
    val unread = state.books.sumOf { book -> book.chapters.count { it.id !in state.readChapters } }
    LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.fillMaxSize().testTag("manga:library"), state = grid,
        contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
        horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            ReadPageHead(mangaText("Library"), if (state.books.isEmpty()) mangaText("Your series") else "${state.books.size} series · $unread unread", Modifier.readBleed()) {
                ReadIconButton(Icons.Rounded.Tune, "Library options", { options = true }, Modifier.testTag("manga:options"))
                MangaRootActions(scope)
            }
        }
        if (hero != null) item(key = "hero:${hero.id}", span = { GridItemSpan(maxLineSpan) }) { MangaHero(scope, hero, state) }
        if (reading.size > 1) item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.readBleed()) {
                ReadSection("Continue")
                LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                    items(reading.drop(1).take(12), key = { "continue:${it.id}" }) { book ->
                        MangaBookCard(scope, book, mangaNextLine(book, state)?.removePrefix("Next: "), width = 112.dp, inLibrary = true)
                    }
                }
            }
        }
        if (fresh.isNotEmpty()) item(key = "fresh", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.readBleed()) {
                ReadSection("New chapters", onSeeAll = { scope.navigation.select(MangaTabs.UPDATES) })
                fresh.forEach { entry -> MangaUpdateRow(scope, entry) }
            }
        }
        val shortcuts: @Composable () -> Unit = {
            ReadChipRow {
                ReadPill(mangaText("Downloads"), icon = CosmoIcons.Downloaded, onClick = { scope.navigation.page = MangaPages.DOWNLOADS }, modifier = Modifier.testTag("manga:downloads"))
                ReadPill(mangaText("History"), icon = CosmoIcons.Replay, onClick = { scope.navigation.select(MangaTabs.HISTORY) }, modifier = Modifier.testTag("manga:history"))
            }
        }
        // An empty library opens on its empty card; "All series", the order and the collection chips wait until there is a series.
        if (state.books.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.readBleed().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadEmpty("Your library is empty", "Your next story starts here. Import a comic or add a series from Browse.",
                    actionLabel = "Browse sources", onAction = { scope.navigation.select(MangaTabs.BROWSE) })
                shortcuts()
                if (discovery != null) MangaDiscoverShelf(scope, discovery)
            }
        } else {
            item(key = "collections", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.readBleed(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadSection("All series", subtitle = mangaText(sortLabel(sort)) + if (unreadOnly) " · " + mangaText("unread only") else "")
                    MangaCollections(repository, collection) { collection = it }
                    shortcuts()
                }
            }
            if (books.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.readBleed()) {
                    ReadEmpty("Nothing here", if (unreadOnly) "Every series in this view is read." else "No series in this collection yet.")
                }
            }
        }
        items(books, key = { it.id }) { book -> MangaBookCard(scope, book, mangaLibraryLine(book, state), inLibrary = true) }
    }
    if (options) KitSheet(onDismiss = { options = false }, title = mangaText("Library")) {
        ReadSheetRow(mangaText("Import comic"), CosmoIcons.Plus, { options = false; pick() }, enabled = !busy)
        ReadSheetRow(mangaText("Image folder"), Icons.Rounded.Folder, { options = false; pickFolder() }, enabled = !busy)
        ReadSheetRow(mangaText("Manage sources"), CosmoIcons.Settings, { options = false; scope.navigation.select(MangaTabs.SOURCES) })
        ReadSheetSection(mangaText("Sort by")) {
            ReadChipRow(inset = 0.dp) {
                listOf("recent", "title", "added", "unread").forEach { value -> ReadChip(mangaText(sortLabel(value)), sort == value, { sort = value }) }
            }
        }
        ReadSheetSection(mangaText("Filter")) { ReadChip(mangaText("Unread only"), unreadOnly, { unreadOnly = !unreadOnly }) }
        Spacer(Modifier.height(16.dp))
    }
}

private fun sortLabel(sort: String): String = when (sort) { "title" -> "Title"; "added" -> "Added"; "unread" -> "Unread"; else -> "Last read" }

/** The series being read: its colour card with Continue in the bite, then its next three chapters as rows, the next one peach. */
@Composable
private fun MangaHero(scope: MangaScope, book: MangaBook, state: MangaLibraryState) {
    val cover = rememberMangaCover(scope.repository, book)
    val next = mangaContinue(book, state).chapter
    val ordered = book.listedChapters()
    val start = next?.let { n -> ordered.indexOfFirst { it.id == n.id } }?.coerceAtLeast(0) ?: 0
    val upcoming = ordered.drop(start).take(3)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbitCoverTitleCard(
            cover = cover, colourUrl = book.colourUrl(), key = book.id, label = mangaCardLabel(book), title = book.title,
            line = mangaNextLine(book, state), onContinue = { scope.continueReading(book, state) }, onClick = { scope.openBook(book) },
            modifier = Modifier.testTag("manga:hero"),
            chips = { colors ->
                OrbitCardChip(mangaText("Chapters"), colors, onClick = { scope.openBook(book) }, icon = CosmoIcons.List)
                val pending = if (book.sourceId == "local") emptyList() else ordered.drop(start).filter { it.id !in state.readChapters && !state.downloads.containsKey(it.id) }.take(10)
                if (scope.transfer.active) Box(Modifier.height(40.dp).width(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = colors.ink)
                } else if (pending.isNotEmpty()) OrbitCardChip(mangaText("Download"), colors, onClick = { mangaDownload(scope, book, pending) }, icon = CosmoIcons.Download)
            },
        )
        Spacer(Modifier.height(8.dp))
        upcoming.forEach { chapter ->
            val read = chapter.id in state.readChapters
            ReadChapterRow(
                title = chapter.title,
                meta = if (chapter.id == next?.id) mangaText("Next") + chapterLength(state, book, chapter) else chapterSubtitle(chapter, state.progress[book.id], read),
                read = read, fresh = chapter.id in state.newChapters, download = mangaDownloadState(scope, chapter, state),
                onClick = { scope.action { scope.repository.store.save(book); scope.navigation.book = book; scope.navigation.chapter = chapter } },
                onLongClick = { scope.openBook(book) },
                onDownload = { if (state.downloads.containsKey(chapter.id)) scope.action { scope.repository.removeDownload(chapter.id) } else mangaDownload(scope, book, listOf(chapter)) },
                next = chapter.id == next?.id,
            )
        }
    }
}

/** " · 22 pages" when the chapter's pages are known (downloaded or part read), else nothing. */
internal fun chapterLength(state: MangaLibraryState, book: MangaBook, chapter: MangaChapter): String {
    val pages = state.downloads[chapter.id]?.size ?: state.progress[book.id]?.takeIf { it.chapterId == chapter.id }?.pageCount ?: 0
    return if (pages > 0) " · $pages pages" else ""
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
