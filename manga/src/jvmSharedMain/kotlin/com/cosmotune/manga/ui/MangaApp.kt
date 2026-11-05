package com.cosmotune.manga.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitEmptyState
import com.cosmotune.ui.kit.KitErrorState
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitPill
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.kit.collapseFraction
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Manga mode (DESIGN_SYSTEM.md 6.4): Home, Browse, Updates and Library in the dock (a rail from 600 dp; the desktop's sidebar
 * when [showNavigation] is false), pushed pages for a source's catalogue, search, history, downloads, sources and a series, and
 * the reader over everything. [showLeave] adds a way back to Music where the host has no account button; [showHeader] false
 * leaves the account slot to a host that draws its own. Music keeps playing underneath: nothing here touches playback.
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
            ReadTopBar("Manga", collapse = { 0f }, leading = LocalReadModeSwitcher.current, actions = { if (showHeader) ReadAccountAction(onMusic.takeIf { showLeave }) })
            KitErrorState("Check available disk space and file access, then try again.", onRetry = { retry++ }, title = "Storage is unavailable")
        }
    } else MangaContent(contentPadding, onMusic.takeIf { showLeave }, ready, showHeader, onReaderVisibility, showNavigation)
}

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

    /** One step back (DESIGN_SYSTEM.md 5.3): the series, then a page, then a catalogue, then Home. False when there is nothing to undo. */
    fun back(): Boolean = when {
        book != null -> { book = null; true }
        page != null -> { page = null; true }
        source != null -> { source = null; true }
        tab != MangaTabs.HOME -> { tab = MangaTabs.HOME; true }
        else -> false
    }
}

internal object MangaPages {
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    const val SOURCES = "sources"
}

/** The pieces every page of the mode is handed. */
internal class MangaScope(
    val repository: MangaRepository,
    val navigation: MangaNavigation,
    val transfer: MangaTransfer,
    val updates: MangaUpdateCheck,
    val onMusic: (() -> Unit)?,
    val showAccount: Boolean,
    val bottom: androidx.compose.ui.unit.Dp,
    val action: (suspend () -> Unit) -> Job,
    val openBook: (MangaBook) -> Unit,
    val onError: (String?) -> Unit,
)

@Composable
private fun MangaContent(contentPadding: PaddingValues, onMusic: (() -> Unit)?, repository: MangaRepository, showAccount: Boolean,
    onReaderVisibility: (Boolean) -> Unit, showNavigation: Boolean) {
    MangaBackgroundUpdates(repository)
    val state by repository.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var savedTab by rememberSaveable { mutableStateOf(MangaTabs.HOME) }
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
    MangaBackHandler(navigation.chapter == null && (!navigation.atRoot || navigation.tab != MangaTabs.HOME)) { navigation.back() }

    // Pages keep their filters and scroll while a series, a page or the reader stands over them.
    val holder = rememberSaveableStateHolder()
    val current = navigation.chapter
    if (current != null) {
        val book = navigation.book
        val next = book?.nextChapter(current)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().testTag("manga:root")) {
            MangaReader(repository, current, seriesTitle = book?.title.orEmpty(), hasNext = next != null, onBack = { navigation.chapter = null }, onNext = { navigation.chapter = next })
        }
        return
    }
    val tabs = mangaTabs()
    ReadModeScaffold(tabs, MangaTabs.all.indexOf(navigation.tab), { navigation.select(MangaTabs.all[it]) }, showNavigation, showTabs = true,
        modifier = Modifier.testTag("manga:root")) { chrome ->
        val bottom = chrome.calculateBottomPadding() + contentPadding.calculateBottomPadding()
        val pageScope = MangaScope(repository, navigation, transfer, updates, onMusic, showAccount, bottom, ::action, ::openBook) { error = it }
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            error?.let { message -> ReadInlineBanner(message, Modifier.padding(top = 8.dp), onDismiss = { error = null }) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Color(LocalCosmoColors.current.palette.accent))
            Box(Modifier.weight(1f)) {
                val book = navigation.book
                when {
                    book != null -> MangaDetails(pageScope, book, onRefresh = { openBook(book) }, onRead = { chapter -> pageScope.action { repository.store.save(book); navigation.chapter = chapter } })
                    navigation.page == MangaPages.SOURCES -> MangaSources(repository, onDismiss = { navigation.page = null })
                    navigation.page == MangaPages.SEARCH -> MangaSearchPage(pageScope)
                    navigation.page == MangaPages.HISTORY -> MangaHistoryPage(pageScope)
                    navigation.page == MangaPages.DOWNLOADS -> MangaDownloadsPage(pageScope)
                    navigation.source != null -> { val source = requireNotNull(navigation.source); holder.SaveableStateProvider("source:$source") { MangaSourcePage(pageScope, source) } }
                    navigation.tab == MangaTabs.BROWSE -> holder.SaveableStateProvider("tab:browse") { MangaBrowse(pageScope) }
                    navigation.tab == MangaTabs.UPDATES -> holder.SaveableStateProvider("tab:updates") { MangaUpdatesPage(pageScope) }
                    navigation.tab == MangaTabs.LIBRARY -> holder.SaveableStateProvider("tab:library") { MangaLibrary(pageScope, busy = busy, onBusy = { busy = it }) }
                    else -> holder.SaveableStateProvider("tab:home") { MangaHome(pageScope) }
                }
            }
        }
    }
}

@Composable
private fun mangaTabs(): List<CosmoTab> = listOf(
    CosmoTab(mangaText("Home"), CosmoIcons.Home, CosmoIcons.HomeFill),
    CosmoTab(mangaText("Browse"), Icons.Outlined.Explore, Icons.Rounded.Explore),
    CosmoTab(mangaText("Updates"), Icons.Outlined.NewReleases, Icons.Rounded.NewReleases),
    CosmoTab(mangaText("Library"), Icons.Outlined.CollectionsBookmark, Icons.Rounded.CollectionsBookmark),
)

/** The trailing actions every root page carries, in the design system's order: the page's own, then Search, then the account. */
@Composable
internal fun androidx.compose.foundation.layout.RowScope.MangaRootActions(scope: MangaScope, page: @Composable () -> Unit = {}) {
    page()
    KitIconButton(CosmoIcons.Search, mangaText("Search"), { scope.navigation.page = MangaPages.SEARCH })
    if (scope.showAccount) ReadAccountAction(scope.onMusic)
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

/** A series as a 2:3 card, with the library's badges: unread count, all chapters downloaded, reading progress. */
@Composable
internal fun MangaBookCard(scope: MangaScope, book: MangaBook, meta: String?, modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp? = null, inLibrary: Boolean = false) {
    val state by scope.repository.store.state.collectAsState()
    val cover = rememberMangaCover(scope.repository, book)
    val badges = if (!inLibrary) ReadCoverBadges() else {
        val unread = book.chapters.count { it.id !in state.readChapters }
        val fresh = book.chapters.count { it.id in state.newChapters }
        val progress = state.progress[book.id]?.takeIf { !it.finished && it.pageCount > 0 }?.let { (it.page + 1f) / it.pageCount }
        ReadCoverBadges(
            count = if (fresh > 0) "NEW $fresh" else unread.takeIf { it > 0 }?.toString(),
            done = book.sourceId != "local" && book.chapters.isNotEmpty() && book.chapters.all { state.downloads.containsKey(it.id) },
            progress = progress,
        )
    }
    ReadPosterCard(book.title, meta, cover, onClick = { scope.openBook(book) }, modifier = modifier, badges = badges, width = width)
}

@Composable
private fun MangaHome(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    val list = rememberLazyListState()
    val threshold = rememberCollapseThreshold()
    val continuing = state.books.filter { book -> state.progress[book.id]?.let { !it.finished || mangaContinue(book, state).resumes } == true }
        .sortedByDescending { state.progress[it.id]?.updatedAtMs ?: 0L }.take(12)
    val recent = state.books.filter { it.id !in state.progress }.sortedByDescending { it.addedAtMs }.take(12)
    val updates = mangaUpdates(state).take(5)
    val discovery = state.sources.firstOrNull { it.enabled && MangaSourcePolicy.allowed(it) }
    Column(Modifier.fillMaxSize()) {
        ReadTopBar(mangaText("Home"), collapse = { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) },
            leading = LocalReadModeSwitcher.current, actions = { MangaRootActions(scope) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("manga:home"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            if (continuing.isNotEmpty()) item(key = "continue") {
                KitSectionHeader(mangaText("Continue reading"))
                LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                    items(continuing, key = { "continue:${it.id}" }) { book ->
                        val progress = state.progress[book.id]
                        val chapter = book.chapters.find { it.id == progress?.chapterId }
                        MangaBookCard(scope, book, chapter?.let { listOf(chapterName(it).replaceFirstChar(Char::uppercase), progress?.takeIf { p -> p.pageCount > 0 }?.let { p -> "page ${p.page + 1}" }).filterNotNull().joinToString(" · ") }, width = 112.dp, inLibrary = true)
                    }
                }
            }
            if (updates.isNotEmpty()) {
                item(key = "updates:header") { KitSectionHeader(mangaText("New chapters"), onSeeAll = { scope.navigation.select(MangaTabs.UPDATES) }) }
                items(updates, key = { "update:${it.chapter.id}" }) { entry -> MangaUpdateRow(scope, entry) }
            }
            if (recent.isNotEmpty()) item(key = "recent") {
                KitSectionHeader(mangaText("Recently added"), onSeeAll = { scope.navigation.select(MangaTabs.LIBRARY) })
                LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                    items(recent, key = { "recent:${it.id}" }) { book -> MangaBookCard(scope, book, mangaLibraryLine(book, state), width = 112.dp, inLibrary = true) }
                }
            }
            if (discovery != null) item(key = "discover:${discovery.id}") { MangaDiscoverShelf(scope, discovery) }
            if (state.books.isEmpty() && discovery == null) item(key = "empty") {
                KitEmptyState(mangaText("Your next story starts here"), mangaText("Import a comic from Library, or browse a source to find a series."),
                    icon = Icons.Outlined.Explore, actionLabel = mangaText("Browse sources"), onAction = { scope.navigation.select(MangaTabs.BROWSE) })
            }
        }
    }
}

/** The first page of an enabled catalogue, as a shelf on Home: discovery without a provider prompt or a search. */
@Composable
private fun MangaDiscoverShelf(scope: MangaScope, source: MangaSourceConfig) {
    val catalogue = rememberSourceCatalogue(scope.repository, source.id, "")
    KitSectionHeader(mangaText("Popular on") + " " + source.name, onSeeAll = { scope.navigation.source = source.id })
    when {
        catalogue.results.isNotEmpty() -> LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
            items(catalogue.results.take(15), key = { "discover:${it.id}" }) { book -> MangaBookCard(scope, book, null, width = 112.dp) }
        }
        catalogue.loading -> ReadSkeletonGrid(rows = 1)
        catalogue.error != null -> ReadInlineBanner(requireNotNull(catalogue.error), actionLabel = mangaText("Open source"), onAction = { scope.navigation.source = source.id })
    }
}

/** A new chapter of a library series (Updates, Home): the cover, the series, the chapter, its download state. Tap reads it. */
@Composable
internal fun MangaUpdateRow(scope: MangaScope, entry: MangaUpdateEntry) {
    val state by scope.repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val cover = rememberMangaCover(scope.repository, entry.book)
    val chapter = entry.chapter
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(role = Role.Button) { scope.action { scope.repository.store.save(entry.book); scope.navigation.book = entry.book; scope.navigation.chapter = chapter } }
            .padding(start = ReadMargin, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReadCover(cover, entry.book.title, Modifier.width(40.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.book.title, style = ReadType.rowTitle(), color = Color(palette.textPrimary), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(chapter.title, style = ReadType.meta(), color = Color(palette.textSecondary), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        MangaDownloadButton(scope, entry.book, chapter, state)
    }
}

/** The download glyph of one chapter: start, progress, or remove. Local comics have nothing to download. */
@Composable
internal fun MangaDownloadButton(scope: MangaScope, book: MangaBook, chapter: MangaChapter, state: MangaLibraryState) {
    when (mangaDownloadState(scope, chapter, state)) {
        ReadDownload.NONE -> Spacer(Modifier.width(8.dp))
        ReadDownload.RUNNING -> Box(Modifier.width(48.dp).height(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = Color(LocalCosmoColors.current.palette.textSecondary))
        }
        ReadDownload.AVAILABLE -> KitIconButton(CosmoIcons.Download, mangaText("Download"), { mangaDownload(scope, book, listOf(chapter)) })
        ReadDownload.DONE -> KitIconButton(CosmoIcons.Downloaded, mangaText("Remove download"), { scope.action { scope.repository.removeDownload(chapter.id) } })
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

@Composable
private fun MangaLibrary(scope: MangaScope, busy: Boolean, onBusy: (Boolean) -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val grid = rememberLazyGridState()
    val threshold = rememberCollapseThreshold()
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
    Column(Modifier.fillMaxSize()) {
        ReadTopBar(mangaText("Library"), collapse = { collapseFraction(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, threshold) },
            leading = LocalReadModeSwitcher.current, actions = { MangaRootActions(scope) {
                KitIconButton(Icons.Rounded.Tune, mangaText("Library options"), { options = true }, Modifier.testTag("manga:options"))
            } })
        LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.weight(1f).fillMaxWidth().testTag("manga:library"), state = grid,
            contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
            horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "shortcuts", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitPill(mangaText("History"), icon = Icons.Rounded.History, onClick = { scope.navigation.page = MangaPages.HISTORY }, modifier = Modifier.testTag("manga:history"))
                    KitPill(mangaText("Downloads"), icon = CosmoIcons.Downloaded, onClick = { scope.navigation.page = MangaPages.DOWNLOADS }, modifier = Modifier.testTag("manga:downloads"))
                }
            }
            item(key = "collections", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.readBleed()) { MangaCollections(repository, collection) { collection = it } }
            }
            if (books.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                if (state.books.isEmpty()) KitEmptyState(mangaText("Your library is empty"), mangaText("Your next story starts here. Import a comic or add a series from Browse."),
                    actionLabel = mangaText("Browse sources"), onAction = { scope.navigation.select(MangaTabs.BROWSE) })
                else KitEmptyState(mangaText("Nothing here"), mangaText(if (unreadOnly) "Every series in this view is read." else "No series in this collection yet."))
            }
            items(books, key = { it.id }) { book -> MangaBookCard(scope, book, mangaLibraryLine(book, state), inLibrary = true) }
        }
    }
    if (options) KitSheet(onDismiss = { options = false }, title = mangaText("Library")) {
        ReadSheetRow(mangaText("Import comic"), CosmoIcons.Plus, { options = false; pick() }, enabled = !busy)
        ReadSheetRow(mangaText("Image folder"), Icons.Rounded.Folder, { options = false; pickFolder() }, enabled = !busy)
        ReadSheetRow(mangaText("Manage sources"), CosmoIcons.Settings, { options = false; scope.navigation.page = MangaPages.SOURCES })
        ReadSheetSection(mangaText("Sort by")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("recent" to "Last read", "title" to "Title", "added" to "Added", "unread" to "Unread").forEach { (value, label) ->
                    ReadChip(mangaText(label), sort == value, { sort = value })
                }
            }
        }
        ReadSheetSection(mangaText("Filter")) { ReadChip(mangaText("Unread only"), unreadOnly, { unreadOnly = !unreadOnly }) }
        Spacer(Modifier.height(16.dp))
    }
}

