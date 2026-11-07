package com.cosmotune.manga.ui

import com.cosmotune.cache.CacheCodec
import com.cosmotune.cache.PageCache
import com.cosmotune.cache.SLOW_FRESH_MS
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One source's catalogue as it loads page by page: what is shown, the next offset, and what went wrong. */
internal class SourceCatalogue {
    var results by mutableStateOf(emptyList<MangaBook>())
    var next by mutableStateOf<Int?>(0)
    var requested by mutableIntStateOf(0)
    var retry by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var verificationAddress by mutableStateOf<String?>(null)
    var generation = 0
    /** The [retry] last served: a newer one asks the source again whatever the age of the first page shown. */
    var servedRetry = 0

    fun loadMore() { val offset = next ?: return; if (!loading && offset != requested) requested = offset }
    fun retryNow() { requested = 0; retry++ }
}

/** [sourceId]'s catalogue for [query] (blank: the source's own first pages). A changed configuration (another language) starts again. */
@Composable
internal fun rememberSourceCatalogue(repository: MangaRepository, sourceId: String, query: String): SourceCatalogue {
    val library by repository.store.state.collectAsState()
    val configuration = library.sources.find { it.id == sourceId }
    val revision by repository.sourceChanges.collectAsState()
    // The first page of this catalogue as it was last loaded comes back at once (from memory, or the disk after a restart) and
    // stays while the source is asked again; inside the freshness window it is not asked again.
    val pages = LocalMangaPages.current ?: remember(repository) { PageCache.of(repository, persistent = false) }
    val first = remember(pages, sourceId, query, configuration, revision) {
        pages.entry<MangaBrowsePage>("manga:browse:$sourceId:${configuration.hashCode()}:$revision:$query", SLOW_FRESH_MS,
            CacheCodec.json(MangaBrowsePage.serializer())) { repository.browse(sourceId, query, 0) }
    }
    val catalogue = remember(sourceId, query, configuration) {
        SourceCatalogue().also { fresh -> first.state.value.data?.let { fresh.results = it.books.distinctBy { b -> b.id }; fresh.next = it.nextOffset } }
    }
    LaunchedEffect(catalogue, catalogue.requested, revision, catalogue.retry) {
        val generation = ++catalogue.generation
        catalogue.error = null
        catalogue.verificationAddress = null
        val forced = catalogue.retry != catalogue.servedRetry
        catalogue.servedRetry = catalogue.retry
        if (catalogue.requested == 0) {
            first.restore()
            first.state.value.data?.let { catalogue.results = it.books.distinctBy { b -> b.id }; catalogue.next = it.nextOffset }
            if (!forced && first.isFresh) { catalogue.loading = false; return@LaunchedEffect }
        }
        catalogue.loading = true
        try {
            delay(300)
            val requested = catalogue.requested
            val response = repository.browse(sourceId, query, requested)
            if (requested == 0) first.set(response)
            catalogue.results = (if (requested == 0) response.books else catalogue.results + response.books).distinctBy { it.id }
            catalogue.next = response.nextOffset
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            catalogue.error = failure.message ?: "Source could not be loaded"
            catalogue.verificationAddress = (failure as? MangaBrowserRequired)?.address
        } finally { if (generation == catalogue.generation) catalogue.loading = false }
    }
    return catalogue
}

/** A search across every enabled source, streaming each source's first page as it answers. */
internal class GlobalSearch {
    var results by mutableStateOf<MangaSearchResults?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var job by mutableStateOf<Job?>(null)
    var generation = 0
}

@Composable
internal fun rememberGlobalSearch(repository: MangaRepository, query: String): GlobalSearch {
    val library by repository.store.state.collectAsState()
    val search = remember(query) { GlobalSearch() }
    val revision by repository.sourceChanges.collectAsState()
    LaunchedEffect(search, library.sources, revision) {
        val generation = ++search.generation
        search.error = null
        search.results = null
        if (query.trim().length < 2) { search.loading = false; return@LaunchedEffect }
        search.loading = true
        search.job = currentCoroutineContext()[Job]
        try {
            delay(300)
            mangaGlobalSearch(library.sources, query) { config, term -> repository.browse(config.id, term, 0) }.collect { search.results = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { search.error = failure.message ?: "Search could not be started" }
        finally { if (generation == search.generation) { search.loading = false; search.job = null } }
    }
    return search
}

/** The grid items of a search across sources: a status line, the partial-failure note, the series (with their source), or a hint. */
internal fun LazyGridScope.mangaGlobalResultItems(scope: MangaScope, search: GlobalSearch, query: String, sources: List<MangaSourceConfig>) {
    val current = search.results
    item(key = "global:status", span = { GridItemSpan(maxLineSpan) }) {
        val palette = LocalCosmoColors.current.palette
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                query.trim().length < 2 -> Text(mangaText("Enter at least two characters to search enabled sources"), style = ReadType.meta(), color = Color(palette.textSecondary))
                current != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${current.completed} / ${current.total} sources · ${current.books.size} series", style = ReadType.meta(), color = Color(palette.textSecondary), modifier = Modifier.weight(1f))
                    if (search.loading) ReadButton(mangaText("Stop search"), { search.job?.cancel() }, kind = ReadKind.TONAL)
                }
            }
            if (current != null) {
                if (current.errors.isNotEmpty()) Text("${current.errors.size} sources unavailable. Choose an individual source to retry or check its settings.", style = ReadType.meta(), color = Color(palette.textSecondary))
                Text(mangaText("Searches the first catalogue page, up to 25 series per source. Choose a source to see more."), style = ReadType.caption(), color = Color(palette.textTertiary))
                if (current.truncated) Text(mangaText("Showing up to 2,000 results. Choose a source for its full catalogue."), style = ReadType.caption(), color = Color(palette.textTertiary))
            }
            search.error?.let { Text(it, style = ReadType.meta(), color = Color(palette.danger)) }
        }
    }
    val books = current?.books.orEmpty()
    if (books.isEmpty() && search.loading) item(key = "global:loading", span = { GridItemSpan(maxLineSpan) }) { ReadSkeletonGrid(Modifier.readBleed()) }
    else if (books.isEmpty() && current != null && !search.loading && query.trim().length >= 2) item(key = "global:empty", span = { GridItemSpan(maxLineSpan) }) {
        ReadEmpty(mangaText("No results"), mangaText("No manga found. Try another search or source."))
    }
    items(books, key = { "global:${it.id}" }) { book ->
        MangaBookCard(scope, book, sources.find { it.id == book.sourceId }?.name ?: book.sourceId)
    }
}

/** Browse (slot 2): a search across all sources, and the enabled sources by language; a source opens its catalogue. */
@Composable
internal fun MangaBrowse(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val enabled = state.sources.filter { it.enabled && MangaSourcePolicy.allowed(it) }
    val search = rememberGlobalSearch(scope.repository, query)
    var language by rememberSaveable { mutableStateOf("") }
    val languages = enabled.map { languageName(it.language) }.distinct().sorted()
    if (language.isNotBlank() && language !in languages) language = ""
    LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.fillMaxSize().testTag("manga:browse"), state = grid,
        contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
        horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            ReadPageHead(mangaText("Browse"), if (enabled.size == 1) mangaText("1 source on") else "${enabled.size} " + mangaText("sources on"), Modifier.readBleed()) {
                ReadIconButton(CosmoIcons.Settings, "Manage sources", { scope.navigation.select(MangaTabs.SOURCES) })
            }
        }
        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            ReadSearchField(query, { query = it.take(500) }, mangaText("Search all sources"), Modifier.readBleed().testTag("manga:browse:search"))
        }
        if (query.isNotBlank()) mangaGlobalResultItems(scope, search, query, state.sources)
        else if (enabled.isEmpty()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
            ReadEmpty(mangaText("Nothing to browse yet"), mangaText("No sources are enabled. Manage sources to choose a catalogue."),
                actionLabel = mangaText("Manage sources"), onAction = { scope.navigation.select(MangaTabs.SOURCES) }, modifier = Modifier.readBleed())
        } else {
            item(key = "discover:${enabled.first().id}", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.readBleed()) { MangaDiscoverShelf(scope, enabled.first()) } }
            item(key = "sources:heading", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.readBleed(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadSection("Sources", subtitle = mangaText("A source opens its own catalogue"))
                    if (languages.size > 1) ReadChipRow {
                        ReadChip(mangaText("All languages"), language.isBlank(), { language = "" })
                        languages.forEach { name -> ReadChip(name, language == name, { language = name }) }
                    }
                }
            }
            enabled.filter { language.isBlank() || languageName(it.language) == language }.sortedBy { languageName(it.language) + it.name.lowercase() }.forEach { source ->
                item(key = "source:${source.id}", span = { GridItemSpan(maxLineSpan) }) { MangaSourceRow(source) { scope.navigation.source = source.id } }
            }
        }
    }
}

/** The display name of a language tag in its own language ("English", "日本語"), or "Other". */
internal fun languageName(tag: String): String = if (tag.isBlank() || tag == "multi") "Other" else java.util.Locale.forLanguageTag(tag).let { locale ->
    locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { tag }
}

/** A source in Browse as an Orbit row: its initials on a round tile, its name, its language and kind, a chevron. */
@Composable
private fun MangaSourceRow(source: MangaSourceConfig, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    OrbitSongRow(
        title = source.name, cover = null, fonts = fonts, look = look, onClick = onClick, modifier = modifier.fillMaxWidth(), height = 64.dp,
        subtitle = listOf(languageName(source.language), when (source.kind) { "komga" -> "Komga"; "template" -> "Website"; "mihon" -> "Extension"; else -> "Source" }).joinToString(" · "),
        discContent = {
            Box(Modifier.size(44.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                Text(titleInitials(source.name), style = fonts.label, color = look.ink)
            }
        },
        trailing = { Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.size(20.dp)) },
    )
}

/** A source's catalogue (pushed from Browse): its own search, the catalogue language, the series, more as the grid nears its end. */
@Composable
internal fun MangaSourcePage(scope: MangaScope, sourceId: String) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val source = state.sources.find { it.id == sourceId }
    var query by rememberSaveable(sourceId) { mutableStateOf("") }
    val catalogue = rememberSourceCatalogue(repository, sourceId, query)
    val grid = rememberLazyGridState()
    val coroutines = rememberCoroutineScope()
    var languages by remember(sourceId) { mutableStateOf(emptyList<String>()) }
    var languageSheet by remember { mutableStateOf(false) }
    LaunchedEffect(sourceId) {
        languages = try { repository.languages(sourceId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
    }
    LaunchedEffect(catalogue) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index to grid.layoutInfo.totalItemsCount }.collect { (last, total) ->
            if (last != null && last >= total - 4 && catalogue.results.isNotEmpty() && catalogue.error == null) catalogue.loadMore()
        }
    }
    val chosen = source?.settings?.get(MANGA_LANGUAGE_SETTING).orEmpty()
    fun label(tag: String) = if (tag.isBlank()) "Source default" else java.util.Locale.forLanguageTag(tag).let { locale ->
        locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { tag } + " ($tag)"
    }
    Column(Modifier.fillMaxSize()) {
        ReadBackHead("Source", onBack = { scope.navigation.source = null })
        LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.weight(1f).fillMaxWidth().testTag("manga:catalogue"), state = grid,
            contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
            horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                ReadPageHead(source?.name ?: mangaText("Source"), listOf(languageName(source?.language.orEmpty()), mangaText("Catalogue")).joinToString(" · "), Modifier.readBleed())
            }
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                ReadSearchField(query, { query = it.take(500) }, mangaText("Search") + " " + (source?.name ?: ""), Modifier.readBleed())
            }
            if (languages.isNotEmpty()) item(key = "language", span = { GridItemSpan(maxLineSpan) }) {
                Row { ReadPill(mangaText("Language") + ": ${label(chosen)}", icon = Icons.Rounded.Language, onClick = { languageSheet = true },
                    modifier = Modifier.testTag("manga:catalogue:language")) }
            }
            val failure = catalogue.error
            if (failure != null && !catalogue.loading) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    ReadError(failure, onRetry = { catalogue.retryNow() }, title = "Couldn't load ${source?.name ?: "this source"}", modifier = Modifier.testTag("manga:catalogue:retry"))
                    if (source != null && source.kind != "komga") MangaVerificationControls(repository, sourceId, catalogue.verificationAddress)
                }
            }
            if (catalogue.results.isEmpty() && catalogue.loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { ReadSkeletonGrid(Modifier.readBleed(), rows = 3) }
            else if (catalogue.results.isEmpty() && failure == null && !catalogue.loading) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                ReadEmpty(mangaText("No results"), mangaText("No manga found. Try another search or source."))
            }
            items(catalogue.results, key = { it.id }) { book -> MangaBookCard(scope, book, null) }
            if (catalogue.results.isNotEmpty() && catalogue.next != null) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (catalogue.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = Color(LocalCosmoColors.current.palette.textSecondary))
                    else ReadButton(mangaText("Load more"), { catalogue.loadMore() }, kind = ReadKind.TONAL)
                }
            }
        }
    }
    if (languageSheet) KitSheet(onDismiss = { languageSheet = false }, title = mangaText("Catalogue language")) {
        (listOf("") + languages).forEach { tag ->
            ReadSheetRow(label(tag), if (tag == chosen) CosmoIcons.Check else null, {
                languageSheet = false
                coroutines.launch {
                    try { repository.chooseLanguage(sourceId, tag) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { scope.onError(failure.message ?: "Language could not be saved") }
                }
            })
        }
        Spacer(Modifier.size(16.dp))
    }
}

/** Search (the top bar's 🔍): the series in the library that match, then every enabled source's first page. */
@Composable
internal fun MangaSearchPage(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val search = rememberGlobalSearch(scope.repository, query)
    val grid = rememberLazyGridState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val saved = if (query.isBlank()) emptyList() else state.books.filter { it.title.contains(query.trim(), true) }
    Column(Modifier.fillMaxSize()) {
        ReadBackHead("Search", onBack = { scope.navigation.page = null })
        LazyVerticalGrid(GridCells.Adaptive(ReadPosterMin), Modifier.weight(1f).fillMaxWidth(), state = grid,
            contentPadding = PaddingValues(start = ReadMargin, end = ReadMargin, bottom = scope.bottom),
            horizontalArrangement = Arrangement.spacedBy(ReadGutter), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                ReadPageHead(mangaText("Search"), mangaText("Your library and every source that is on"), Modifier.readBleed())
            }
            item(key = "field", span = { GridItemSpan(maxLineSpan) }) {
                ReadSearchField(query, { query = it.take(500) }, mangaText("Search manga"), Modifier.readBleed(), focusRequester = focus)
            }
            if (saved.isNotEmpty()) {
                item(key = "saved:header", span = { GridItemSpan(maxLineSpan) }) { ReadOverline(mangaText("In your library"), Modifier.readBleed()) }
                items(saved, key = { "saved:${it.id}" }) { book -> MangaBookCard(scope, book, mangaLibraryLine(book, state), inLibrary = true) }
                item(key = "sources:header", span = { GridItemSpan(maxLineSpan) }) { ReadOverline(mangaText("Sources"), Modifier.readBleed()) }
            }
            if (query.isNotBlank()) mangaGlobalResultItems(scope, search, query, state.sources)
        }
    }
}
