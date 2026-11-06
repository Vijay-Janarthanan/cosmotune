package com.cosmotune.reading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The catalogue a publication came from, as its row and its page name it. */
internal fun ReadingPublication.sourceLabel(sources: List<ReadingOpdsSource>): String =
    if (id.startsWith("gutenberg")) "Project Gutenberg · Public-domain catalogue" else if (id.startsWith("openlibrary")) "Open Library · Book metadata"
    else sources.find { it.id == sourceId }?.name ?: "Personal library"

/**
 * Browse (slot 2, after Kavita and Booklore): a search field, the catalogues as chips (Trending on Open Library, Project
 * Gutenberg's public domain, your own OPDS libraries), and the books as rows with their source; a book opens its page.
 */
@Composable
internal fun BookDiscover(scope: ReadScope) {
    val repository = scope.repository
    val state by repository.state.collectAsState()
    val coroutines = rememberCoroutineScope()
    val list = rememberLazyListState()
    var category by rememberSaveable { mutableStateOf("Trending") }
    var query by rememberSaveable { mutableStateOf("") }
    var publications by remember { mutableStateOf(emptyList<ReadingPublication>()) }
    var navigation by remember { mutableStateOf(emptyList<ReadingLink>()) }
    var searchLinks by remember { mutableStateOf(emptyList<ReadingLink>()) }
    var next by remember { mutableStateOf<ReadingLink?>(null) }
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var sourceId by rememberSaveable { mutableStateOf("") }
    var connections by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    fun load(reset: Boolean = true, address: String? = null, searchRequested: Boolean = false) {
        job?.cancel()
        generation++
        val requestGeneration = generation
        job = coroutines.launch {
            busy = true; failure = null
            if (reset) { publications = emptyList(); navigation = emptyList(); next = null; nextOffset = null; page = 1 }
            try {
                if (category == "My libraries" && sourceId.isBlank()) { loaded = true; return@launch }
                val result = when (category) {
                    "Public domain" -> repository.gutenberg(query, if (reset) 1 else page + 1)
                    "My libraries" -> if (searchRequested) repository.searchOpds(sourceId, query) else repository.browseOpds(sourceId, address)
                    else -> null
                }
                if (result != null) {
                    publications = if (reset) result.publications else publications + result.publications
                    navigation = result.navigation; next = result.next; if (result.search.isNotEmpty()) searchLinks = result.search
                    if (!reset) page++
                } else {
                    val resultSearch = if (category == "Search") repository.search(query, if (reset) 0 else nextOffset ?: 0)
                        else repository.trending(if (reset) 0 else nextOffset ?: 0)
                    publications = if (reset) resultSearch.publications else publications + resultSearch.publications
                    nextOffset = resultSearch.nextOffset
                }
                loaded = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: "The catalogue could not be loaded." }
            finally { if (generation == requestGeneration) busy = false }
        }
    }
    fun submit() {
        if (query.trim().length < 2 || busy || (category == "My libraries" && searchLinks.isEmpty())) return
        if (category != "Public domain" && category != "My libraries") category = "Search"
        load(searchRequested = category == "My libraries")
    }
    LaunchedEffect(category, sourceId) { searchLinks = emptyList(); if (category != "Search") load() }
    LaunchedEffect(state.sources) { if (state.sources.none { it.id == sourceId }) sourceId = state.sources.firstOrNull()?.id.orEmpty() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val palette = LocalCosmoColors.current.palette
    Column(Modifier.fillMaxSize().testTag("books:discover")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "heading") { ReadPageHead("Browse", "Open Library · Project Gutenberg · your libraries") { ReadRootActions(scope) } }
            item(key = "search") {
                ReadSearchField(query, { query = it }, "Search books and authors", Modifier.testTag("books:catalogue-search"), onSearch = ::submit)
            }
            item(key = "catalogues") {
                ReadChipRow(Modifier.padding(vertical = 4.dp)) {
                    listOf("Trending", "Public domain", "My libraries").forEach { item -> ReadChip(item, category == item || (item == "Trending" && category == "Search"), { category = item; query = "" }) }
                    if (query.trim().length >= 2) ReadButton("Search", ::submit, kind = ReadKind.TONAL, icon = CosmoIcons.Search,
                        enabled = !busy && (category != "My libraries" || searchLinks.isNotEmpty()), modifier = Modifier.testTag("books:search-submit"))
                }
                if (category == "My libraries") ReadChipRow(Modifier.padding(vertical = 4.dp)) {
                    state.sources.forEach { source -> ReadChip(source.name, sourceId == source.id, { sourceId = source.id }) }
                    ReadChip("Connect library", false, { connections = true }, icon = CosmoIcons.Plus)
                    if (sourceId.isNotBlank()) ReadChip("Library home", false, { load() })
                }
                Text(when (category) {
                    "Public domain" -> "Project Gutenberg · Check the edition's copyright status where you live."
                    "My libraries" -> if (searchLinks.isEmpty() && sourceId.isNotBlank()) "Your OPDS library · This feed does not advertise a search. Browse its collections below." else "Your OPDS library · Availability comes from your connected server."
                    "Search" -> "Open Library · Search results describe books. Readable copies depend on the edition and provider."
                    else -> "Trending on Open Library · A live discovery list, with source links for each book."
                }, style = ReadType.caption(), color = Color(palette.textTertiary), modifier = Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 8.dp))
            }
            failure?.let { message -> item(key = "error") { ReadError(message, onRetry = { load() }, title = "Couldn't load this catalogue") } }
            if (busy && publications.isEmpty()) item(key = "loading") { ReadSkeletonRows(6) }
            if (category == "My libraries" && state.sources.isEmpty()) item(key = "connect") {
                ReadEmpty("Bring your library with you", "Connect an OPDS feed from BookLore, Calibre-Web, Kavita or another library you can access.",
                    actionLabel = "Connect library", onAction = { connections = true })
            } else if (loaded && failure == null && publications.isEmpty() && navigation.isEmpty() && !busy) item(key = "empty") {
                ReadEmpty("No books found", "Try another title, author or catalogue.")
            }
            items(navigation, key = { "nav:${it.url}" }) { link ->
                ReadSheetRow(link.title.ifBlank { "Browse collection" }, CosmoIcons.Library, { load(address = link.url) })
            }
            items(publications.distinctBy { it.id }, key = { it.id }) { publication ->
                BookPublicationRow(publication, publication.sourceLabel(state.sources), onClick = { scope.navigation.publication = publication })
            }
            if (nextOffset != null || next != null) item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    ReadButton("Load more books", { if (category == "My libraries") load(address = next?.url) else load(reset = false) }, kind = ReadKind.TONAL, enabled = !busy)
                }
            }
        }
    }
    if (connections) BookConnections(repository, onDismiss = { connections = false }, onError = scope.onError)
}

/** A catalogue entry as an Orbit row: its tall cover where a disc would be, the title, the authors and where it comes from. */
@Composable
internal fun BookPublicationRow(publication: ReadingPublication, source: String, onClick: () -> Unit) {
    ReadCoverRow(publication.title, listOf(publication.authors.joinToString(", "), source).filter(String::isNotBlank).joinToString(" · "), publication.coverUrl,
        key = publication.id, onClick = onClick, modifier = Modifier.padding(horizontal = ReadMargin, vertical = 4.dp))
}

/**
 * A catalogue entry's page: the cover-washed header, Save for later, every link of the entry (downloads open the book once it is
 * on this device; borrowing, buying and samples open with their provider), the description and, for Open Library, its editions.
 */
@Composable
internal fun BookDetails(scope: ReadScope, initialPublication: ReadingPublication) {
    val repository = scope.repository
    val state by repository.state.collectAsState()
    val coroutines = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val palette = LocalCosmoColors.current.palette
    val list = rememberLazyListState()
    var publication by remember(initialPublication.id) { mutableStateOf(initialPublication) }
    var detailsLoading by remember(initialPublication.id) { mutableStateOf(false) }
    var detailsError by remember(initialPublication.id) { mutableStateOf<String?>(null) }
    var detailsRetry by remember(initialPublication.id) { mutableIntStateOf(0) }
    var transfer by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var editions by remember(publication.id) { mutableStateOf(emptyList<ReadingPublication>()) }
    var editionsOffset by remember(publication.id) { mutableStateOf<Int?>(null) }
    var editionsLoaded by remember(publication.id) { mutableStateOf(false) }
    var editionsBusy by remember(publication.id) { mutableStateOf(false) }
    val saved = state.saved.any { it.id == publication.id }
    fun action(block: suspend () -> Unit) = coroutines.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { scope.onError(failure.message ?: "This action could not be completed.") }
    }
    LaunchedEffect(initialPublication.id, detailsRetry) {
        if (initialPublication.sourceId == "gutenberg" || initialPublication.id.startsWith("gutenberg:")) {
            detailsLoading = true; detailsError = null
            try { publication = repository.details(initialPublication) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { detailsError = failure.message ?: "The available editions could not be loaded." }
            finally { detailsLoading = false }
        }
    }
    val sourceName = state.sources.find { it.id == publication.sourceId }?.name
        ?: if (publication.id.startsWith("openlibrary")) "Open Library" else if (publication.id.startsWith("gutenberg")) "Project Gutenberg" else publication.sourceId.ifBlank { "Personal library" }
    val (fill, _) = com.cosmotune.ui.orbit.coverFill(publication.coverUrl, publication.id)
    Box(Modifier.fillMaxSize()) {
        com.cosmotune.ui.orbit.OrbitPageWash(fill)
        LazyColumn(Modifier.fillMaxSize().testTag("books:details"), state = list, contentPadding = PaddingValues(bottom = scope.bottom)) {
            item(key = "head") { ReadBackHead("Book", onBack = { scope.navigation.publication = null }, backLabel = "Back to books") }
            item(key = "header") {
                Column(Modifier.fillMaxWidth()) {
                    com.cosmotune.ui.orbit.OrbitCoverTitleCard(
                        cover = publication.coverUrl, colourUrl = publication.coverUrl, key = publication.id,
                        label = (listOf("Book") + publication.editionFormats.take(2) + sourceName).joinToString(" · "), title = publication.title,
                        line = publication.authors.joinToString(" · ").ifBlank { null }, onContinue = null,
                        modifier = Modifier.padding(horizontal = ReadMargin).padding(top = 4.dp),
                        chips = { colors ->
                            com.cosmotune.ui.orbit.OrbitCardChip(if (saved) "Saved · Remove" else "Save for later", colors,
                                onClick = { action { if (saved) repository.removeSaved(publication.id) else repository.savePublication(publication) } },
                                icon = if (saved) CosmoIcons.HeartFill else CosmoIcons.Heart, modifier = Modifier.testTag("books:save"))
                        },
                    )
                    if (publication.description.isNotBlank()) Text(org.jsoup.Jsoup.parse(publication.description).text(), style = ReadType.body(), color = Color(palette.textSecondary),
                        maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 12.dp))
                    Text("Availability is specific to each edition. Library loans, samples and purchase links open with their provider.", style = ReadType.caption(),
                        color = Color(palette.textTertiary), modifier = Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 8.dp))
                    run {
                        if (detailsLoading) {
                            ReadSkeletonRows(2)
                            Text("Loading available editions from Project Gutenberg…", style = ReadType.meta(), color = Color(palette.textSecondary), modifier = Modifier.padding(horizontal = ReadMargin))
                        }
                        detailsError?.let { message -> ReadError(message, onRetry = { detailsRetry++ }, title = "Editions could not be loaded", retryLabel = "Retry available editions") }
                        if (transfer?.isActive == true) Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalAlignment = Alignment.CenterVertically) {
                            Text(progress, style = ReadType.meta(), color = Color(palette.textSecondary), modifier = Modifier.weight(1f))
                            ReadTextButton("Cancel download", { transfer?.cancel() })
                        }
                    }
                }
            }
            item(key = "links:header") { ReadSection("Get this book") }
            items(publication.links.filterNot { it.kind == ReadingLinkKind.ACQUIRE && (detailsLoading || detailsError != null) }.distinctBy { it.url }, key = { it.url }) { link ->
                val downloadable = link.kind == ReadingLinkKind.ACQUIRE && (link.mediaType.contains("epub", true) || link.mediaType.contains("pdf", true))
                val label = if (downloadable) "Download ${if (link.mediaType.contains("epub", true)) "EPUB" else "PDF"}" +
                    link.title.takeIf { it.isNotBlank() && it != publication.title }?.let { " · ${it.take(100)}" }.orEmpty()
                    else when (link.kind) { ReadingLinkKind.BORROW -> "Borrow from provider"; ReadingLinkKind.BUY -> "Buy from provider"; ReadingLinkKind.SAMPLE -> "Open sample"; else -> link.title.ifBlank { "View at source" } }
                ReadSheetRow(label, if (downloadable) CosmoIcons.Download else Icons.AutoMirrored.Rounded.OpenInNew, enabled = transfer?.isActive != true, onClick = {
                    if (downloadable) transfer = action {
                        val book = repository.download(publication, link) { done, total -> progress = if (total != null && total > 0) "${done / 1024} / ${total / 1024} KB" else "${done / 1024} KB downloaded" }
                        scope.open(book)
                    } else runCatching { uri.openUri(link.url) }.onFailure { scope.onError("This source link could not be opened.") }
                })
            }
            if (!detailsLoading && detailsError == null && publication.links.none { it.kind == ReadingLinkKind.ACQUIRE }) item(key = "no-download") {
                Text("No downloadable edition was supplied by this catalogue. You can import an EPUB or PDF you already have.", style = ReadType.body(),
                    color = Color(palette.textSecondary), modifier = Modifier.padding(horizontal = ReadMargin, vertical = 12.dp))
            }
            if (publication.sourceId == "openlibrary" || publication.id.startsWith("openlibrary:")) {
                item(key = "editions:header") {
                    ReadSection("Editions")
                    Text("Binding, publication and ISBN details come from each edition's catalogue record.", style = ReadType.meta(), color = Color(palette.textSecondary),
                        modifier = Modifier.padding(horizontal = ReadMargin, vertical = 4.dp))
                    if (!editionsLoaded || editionsOffset != null) Box(Modifier.padding(horizontal = ReadMargin, vertical = 8.dp)) {
                        ReadButton(if (editionsBusy) "Loading editions…" else if (editionsLoaded) "More editions" else "View paperback, hardcover and other editions", {
                            action {
                                editionsBusy = true
                                try {
                                    val result = repository.editions(publication, if (editionsLoaded) editionsOffset ?: 0 else 0)
                                    editions = (editions + result.publications).distinctBy { it.id }; editionsOffset = result.nextOffset; editionsLoaded = true
                                } finally { editionsBusy = false }
                            }
                        }, kind = ReadKind.TONAL, enabled = !editionsBusy, modifier = Modifier.testTag("books:editions"))
                    }
                    if (editionsLoaded && editions.isEmpty()) Text("No edition records were returned by Open Library.", style = ReadType.body(), color = Color(palette.textSecondary),
                        modifier = Modifier.padding(horizontal = ReadMargin))
                }
                items(editions, key = { "edition:${it.id}" }) { edition ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = edition.links.isNotEmpty(), role = Role.Button) {
                        edition.links.firstOrNull()?.let { link -> runCatching { uri.openUri(link.url) }.onFailure { scope.onError("The edition source could not be opened.") } }
                    }.padding(horizontal = ReadMargin, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(edition.title, style = ReadType.rowTitle(), color = Color(palette.textPrimary))
                        val facts = (edition.editionFormats + edition.publishers + listOfNotNull(edition.publishedDate)).joinToString(" · ")
                        if (facts.isNotBlank()) Text(facts, style = ReadType.meta(), color = Color(palette.textSecondary))
                        if (edition.identifiers.isNotEmpty()) Text("ISBN ${edition.identifiers.joinToString(" · ")}", style = ReadType.caption(), color = Color(palette.textTertiary))
                        if (edition.links.isNotEmpty()) Text("View edition at source", style = ReadType.caption(), color = Color(palette.accentStrong))
                    }
                    ReadHairline()
                }
            }
        }
    }
}

@Composable
private fun BookConnections(repository: ReadingRepository, onDismiss: () -> Unit, onError: (String?) -> Unit) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var insecure by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val palette = LocalCosmoColors.current.palette
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = "Connect your library",
        scroll = false,
        content = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Use your library's OPDS endpoint. Credentials stay with this device.", style = ReadType.body(), color = Color(palette.textSecondary)) }
            items(state.sources, key = { it.id }) { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(source.name, style = ReadType.rowTitle(), modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { runCatching { repository.removeOpdsSource(source.id) }.onFailure { onError("The connection could not be removed.") } } }) { Text("Remove") }
                }
            }
            item { OutlinedTextField(name, { name = it }, label = { Text("Library name") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(url, { url = it }, label = { Text("OPDS address") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(insecure, { insecure = it }); Text("Allow HTTP on my local network", style = ReadType.body()) } }
            error?.let { message -> item { Text(message, style = ReadType.body(), color = Color(palette.danger)) } }
        }
    },
        confirmLabel = if (busy) "Connecting…" else "Connect",
        onConfirm = {
        scope.launch { busy = true; error = null
            try { repository.saveOpdsSource(name, url, username, password, insecure); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "The library connection could not be saved. Check the address and storage access." }
            finally { busy = false }
        }
    },
        confirmEnabled = name.isNotBlank() && url.isNotBlank() && !busy,
        dismissLabel = "Close",
        onDismiss = onDismiss,
    )
}
