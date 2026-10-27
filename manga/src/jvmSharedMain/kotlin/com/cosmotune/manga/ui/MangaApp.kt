package com.cosmotune.manga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import com.cosmotune.manga.data.*
import com.cosmotune.ui.blocks.ModePageHeader
import com.cosmotune.ui.blocks.ModeTabs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun MangaApp(contentPadding: PaddingValues = PaddingValues(), onMusic: () -> Unit = {}, repository: MangaRepository? = null) {
    var retry by remember { mutableIntStateOf(0) }
    val initialized = key(retry) { repository?.let { Result.success(it) } ?: rememberMangaRepository() }
    val ready = initialized.getOrNull()
    if (ready == null) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(contentPadding).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                Text("Manga storage is unavailable", style = MaterialTheme.typography.titleLarge)
                Text("Check available disk space and file access, then retry.")
                TextButton(onClick = { retry++ }) { Text("Retry") }
                TextButton(onClick = onMusic) { Text("Music") }
            }
        }
    } else MangaContent(contentPadding, onMusic, ready)
}

@Composable
private fun MangaContent(contentPadding: PaddingValues, onMusic: () -> Unit, repository: MangaRepository) {
    val state by repository.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(state.books.find { it.id == state.activeBookId }) }
    var chapter by remember { mutableStateOf(selected?.chapters?.find { it.id == state.activeChapterId }) }
    var sourceId by rememberSaveable { mutableStateOf("library") }
    var query by rememberSaveable { mutableStateOf("") }
    // The desktop sidebar's Library and Discover rows and this page's tabs are one choice.
    LaunchedEffect(Unit) {
        // The page states where it starts before it listens, so a row chosen in an earlier visit cannot flip it.
        com.cosmotune.ui.blocks.ModeNav.mangaTab.value = if (sourceId == "library") "Library" else "Discover"
        com.cosmotune.ui.blocks.ModeNav.mangaTab.collect { tab ->
            if ((sourceId == "library") != (tab == "Library")) { sourceId = if (tab == "Library") "library" else "all"; query = "" }
        }
    }
    LaunchedEffect(sourceId) { com.cosmotune.ui.blocks.ModeNav.mangaTab.value = if (sourceId == "library") "Library" else "Discover" }
    var sourceDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(repository.store.recoveryMessage) }
    var transfer by remember { mutableStateOf<Job?>(null) }
    var downloadProgress by remember { mutableStateOf("") }
    var updates by remember { mutableStateOf<MangaUpdateReport?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    fun action(block: suspend () -> Unit): Job = scope.launch {
        error = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This action could not be completed" }
    }
    LaunchedEffect(selected?.id, chapter?.id) {
        try { repository.store.navigate(selected?.id, chapter?.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Reading position could not be saved" }
    }
    val pick = rememberMangaImport { name, open ->
        scope.launch {
            busy = true; error = null
            try { repository.store.save(repository.store.archives.import(name, open)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Import failed" }
            finally { busy = false }
        }
    }
    val pickFolder = rememberMangaFolderImport { result ->
        result.fold(onSuccess = { folder -> scope.launch {
            busy = true; error = null
            try { repository.store.save(repository.store.archives.importImages(folder.name, folder.images)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Folder import failed" }
            finally { busy = false }
        } }, onFailure = { error = it.message ?: "Folder could not be opened" })
    }
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize().testTag("manga:root")) {
        if (chapter != null) {
            val current = requireNotNull(chapter)
            val next = selected?.nextChapter(current)
            MangaReader(repository, current, hasNext = next != null, onBack = { chapter = null }, onNext = { chapter = next })
        } else Column(Modifier.fillMaxSize().padding(contentPadding).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
            ModePageHeader(selected?.title ?: "Manga", if (selected != null) "Chapters, downloads and reading progress" else "Your library, new discoveries and stories to continue.")
            if (selected != null) TextButton(onClick = { selected = null }) { Text("Back") }
            else ModeTabs(listOf("Library", "Discover"), if (sourceId == "library") "Library" else "Discover",
                { sourceId = if (it == "Library") "library" else "all"; query = ""; com.cosmotune.ui.blocks.ModeNav.mangaTab.value = it }, Modifier.widthIn(max = 440.dp).fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                FilledTonalButton(onClick = { sourceDialog = true }) { Text("Sources") }
                OutlinedButton(onClick = pick, enabled = !busy) { Text("Import CBZ") }
                TextButton(onClick = pickFolder, enabled = !busy) { Text("Image folder") }
            }
            error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f)); TextButton(onClick = { error = null }) { Text("Dismiss") }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (transfer?.isActive == true) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(downloadProgress, modifier = Modifier.weight(1f)); TextButton(onClick = { transfer?.cancel() }) { Text("Cancel download") }
            }
            val book = selected
            if (book != null) {
                MangaBackHandler(true) { selected = null }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val saved = state.books.any { it.id == book.id }
                    Button(onClick = { action { repository.store.save(book) } }, enabled = !saved) { Text(if (saved) "In library" else "Add to library") }
                    TextButton(onClick = { scope.launch {
                        busy = true; error = null
                        try { selected = repository.chapters(book) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                        finally { busy = false }
                    } }) { Text("Refresh chapters") }
                    if (saved) TextButton(enabled = transfer?.isActive != true, onClick = { action { repository.removeBook(book.id); selected = null } }) { Text("Remove from library") }
                }
                val editions = book.editions()
                var edition by rememberSaveable(book.id) { mutableStateOf("") }
                var newestFirst by rememberSaveable(book.id) { mutableStateOf(false) }
                var unreadOnly by rememberSaveable(book.id) { mutableStateOf(false) }
                if (edition.isNotBlank() && edition !in editions) edition = ""
                val listed = book.listedChapters(edition, newestFirst, unreadOnly, state.readChapters)
                LazyColumn(Modifier.weight(1f).testTag("manga:chapters"), contentPadding = PaddingValues(vertical = 12.dp)) {
                    item(key = "series:header") {
                        Text(book.authors.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
                        if (book.description.isNotBlank()) Text(org.jsoup.Jsoup.parse(book.description).text(), modifier = Modifier.padding(vertical = 12.dp))
                        if (book.chapters.isEmpty()) Text("Refresh chapters to load this series.")
                        else {
                            if (editions.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("manga:editions")) {
                                FilterChip(selected = edition.isBlank(), onClick = { edition = "" }, label = { Text("All editions") })
                                editions.forEach { item -> FilterChip(selected = edition == item, onClick = { edition = item }, label = { Text(item) }) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                                TextButton(onClick = { newestFirst = !newestFirst }) { Text(if (newestFirst) "Newest first" else "Oldest first") }
                                FilterChip(selected = unreadOnly, onClick = { unreadOnly = !unreadOnly }, label = { Text("Unread only") })
                                Text("${listed.size} of ${book.chapters.size} chapters · ${book.chapters.count { it.id !in state.readChapters }} unread",
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                            if (listed.isEmpty()) Text(if (unreadOnly) "Every chapter here is read." else "No chapters in this edition.", modifier = Modifier.padding(vertical = 12.dp))
                            val pending = if (book.sourceId == "local") emptyList() else listed.filter { it.id !in state.readChapters && !state.downloads.containsKey(it.id) }
                            if (pending.isNotEmpty()) TextButton(enabled = transfer?.isActive != true, onClick = {
                                transfer = scope.launch {
                                    error = null
                                    try {
                                        repository.store.save(book)
                                        pending.forEachIndexed { index, item ->
                                            repository.download(item) { done, total -> downloadProgress = "Chapter ${index + 1} of ${pending.size} · ${item.title}: $done / $total pages" }
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message ?: "Download failed" }
                                }
                            }) { Text(if (pending.size == 1) "Download 1 unread chapter" else "Download ${pending.size} unread chapters") }
                        }
                    }
                    items(listed, key = { it.id }) { item ->
                        val read = state.progress[book.id]?.takeIf { it.chapterId == item.id }
                        val finished = item.id in state.readChapters
                        Row(Modifier.fillMaxWidth().clickable { action { repository.store.save(book); chapter = item } }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                val status = if (finished) "Read" else read?.let { "Page ${it.page + 1} of ${it.pageCount}" } ?: item.language
                                Text(if (item.id in state.newChapters) listOf("New", status).filter(String::isNotBlank).joinToString(" · ") else status,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { action { repository.store.save(book); repository.store.markRead(item, !finished) } }) {
                                Text(if (finished) "Mark unread" else "Mark read")
                            }
                            if (item.sourceId != "local") {
                                val downloaded = state.downloads.containsKey(item.id)
                                TextButton(enabled = transfer?.isActive != true, onClick = {
                                    if (downloaded) action { repository.removeDownload(item.id) }
                                    else transfer = scope.launch {
                                        error = null
                                        try {
                                            repository.store.save(book)
                                            repository.download(item) { done, total -> downloadProgress = "${item.title}: $done / $total pages" }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { error = failure.message ?: "Download failed" }
                                    }
                                }) { Text(if (downloaded) "Remove download" else "Download") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                var menu by remember { mutableStateOf(false) }
                FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Box {
                        FilledTonalButton(onClick = { menu = true }) { Text(when (sourceId) { "library" -> "My library"; "all" -> "All enabled sources"; else -> state.sources.find { it.id == sourceId }?.name ?: "Choose source" }) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("My library") }, onClick = { sourceId = "library"; menu = false })
                            DropdownMenuItem(text = { Text("All enabled sources") }, onClick = { sourceId = "all"; menu = false })
                            state.sources.filter { it.enabled }.forEach { source ->
                                DropdownMenuItem(text = { Text("${source.name} · ${source.language}") }, onClick = { sourceId = source.id; menu = false })
                            }
                        }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("Search manga") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (sourceId == "library" && state.books.any { it.sourceId != "local" }) Row(verticalAlignment = Alignment.CenterVertically) {
                    val running = updateJob?.isActive == true
                    TextButton(enabled = !running, onClick = {
                        updateJob = scope.launch {
                            error = null
                            try { repository.checkForUpdates { updates = it } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "Updates could not be checked" }
                        }
                    }) { Text("Check for updates") }
                    updates?.let { report ->
                        val found = if (report.newChapters == 1) "1 new chapter" else "${report.newChapters} new chapters"
                        val text = if (running) "Checking ${report.checked} of ${report.total} series · $found"
                            else "$found in ${report.updatedSeries} series" + if (report.failures.isEmpty()) "" else " · ${report.failures.size} unavailable"
                        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                    if (running) TextButton(onClick = { updateJob?.cancel() }) { Text("Stop") }
                }
                MangaCatalogue(repository, sourceId, query, onBook = { item ->
                    selected = item
                    scope.launch {
                        busy = true; error = null
                        try { val detail = repository.chapters(item); if (selected?.id == item.id) selected = detail }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                        finally { busy = false }
                    }
                })
            }
        }
    }
    if (sourceDialog) MangaSources(repository, onDismiss = { sourceDialog = false })
}

@Composable
private fun ColumnScope.MangaCatalogue(repository: MangaRepository, sourceId: String, query: String, onBook: (MangaBook) -> Unit) {
    val library by repository.store.state.collectAsState()
    val configuration: Any? = if (sourceId == "all") library.sources else library.sources.find { it.id == sourceId }
    // A changed source configuration (another catalogue language) starts its catalogue again from the first page.
    var results by remember(sourceId, query, configuration) { mutableStateOf(emptyList<MangaBook>()) }
    var next by remember(sourceId, query, configuration) { mutableStateOf<Int?>(0) }
    var requested by remember(sourceId, query, configuration) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var aggregate by remember(sourceId, query) { mutableStateOf<MangaSearchResults?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    var languages by remember(sourceId) { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sourceId) {
        languages = if (sourceId == "library" || sourceId == "all") emptyList() else try { repository.languages(sourceId) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
    }
    if (languages.isNotEmpty()) {
        val chosen = (configuration as? MangaSourceConfig)?.settings?.get(MANGA_LANGUAGE_SETTING).orEmpty()
        var open by remember { mutableStateOf(false) }
        fun label(tag: String) = if (tag.isBlank()) "Source default" else java.util.Locale.forLanguageTag(tag).let { locale ->
            locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { tag } + " ($tag)"
        }
        Box(Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("manga:catalogue:language")) { Text("Language: ${label(chosen)}") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (listOf("") + languages).forEach { tag -> DropdownMenuItem(text = { Text(label(tag)) }, onClick = {
                    open = false
                    scope.launch {
                        try { repository.chooseLanguage(sourceId, tag) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Language could not be saved" }
                    }
                }) }
            }
        }
    }
    val revision by repository.sourceChanges.collectAsState()
    LaunchedEffect(sourceId, query, requested, configuration, revision) {
        val generation = ++loadGeneration
        error = null
        aggregate = null
        if (sourceId == "all") {
            next = null; results = emptyList()
            if (query.trim().length < 2) { loading = false; error = "Enter at least two characters to search enabled sources"; return@LaunchedEffect }
            loading = true
            searchJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
                kotlinx.coroutines.delay(300)
                mangaGlobalSearch(library.sources, query) { config, term -> repository.browse(config.id, term, 0) }.collect {
                    aggregate = it; results = it.books
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Search could not be started" }
            finally { if (generation == loadGeneration) { loading = false; searchJob = null } }
            return@LaunchedEffect
        }
        if (sourceId != "library") {
            loading = true
            try {
                kotlinx.coroutines.delay(300)
                val response = repository.browse(sourceId, query, requested)
                results = (if (requested == 0) response.books else results + response.books).distinctBy { it.id }
                next = response.nextOffset
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Source could not be loaded" }
            finally { if (generation == loadGeneration) loading = false }
        }
    }
    val books = if (sourceId == "library") library.books.filter { it.title.contains(query, true) }.sortedByDescending { library.progress[it.id]?.updatedAtMs ?: it.addedAtMs } else results
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    if (sourceId == "all") aggregate?.let { current ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${current.completed} / ${current.total} sources · ${current.books.size} series", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (loading) TextButton(onClick = { searchJob?.cancel() }) { Text("Stop search") }
        }
        if (current.errors.isNotEmpty()) Text("${current.errors.size} sources unavailable. Choose an individual source to retry or check its settings.", style = MaterialTheme.typography.bodySmall)
        Text("Searches the first catalogue page, up to 25 series per source. Choose a source to see more.", style = MaterialTheme.typography.bodySmall)
        if (current.truncated) Text("Showing up to 2,000 results. Choose a source for its full catalogue.", style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
    if (books.isEmpty() && !loading) Text(if (sourceId == "library") "Import a CBZ comic or enable a source to start your library." else "No manga found. Try another search or source.", modifier = Modifier.padding(vertical = 24.dp))
    LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(books, key = { it.id }) { book ->
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBook(book) }) {
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
                Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer))), contentAlignment = Alignment.Center) {
                    Text(book.title.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString(""),
                        style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (cover != null) AsyncImage(cover, book.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                if (sourceId == "all") Text(library.sources.find { it.id == book.sourceId }?.name ?: book.sourceId, style = MaterialTheme.typography.labelSmall)
                library.progress[book.id]?.let { Text(if (it.finished) "Chapter read" else "Continue · page ${it.page + 1}", style = MaterialTheme.typography.labelSmall) }
                if (sourceId == "library" && book.chapters.isNotEmpty()) {
                    val fresh = book.chapters.count { it.id in library.newChapters }
                    Text("${book.chapters.count { it.id !in library.readChapters }} unread" + if (fresh > 0) " · $fresh new" else "",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (sourceId != "library" && next != null && !loading && results.isNotEmpty()) TextButton(onClick = { requested = requireNotNull(next) }) { Text("Load more") }
}

@Composable
private fun MangaSources(repository: MangaRepository, onDismiss: () -> Unit) {
    val state by repository.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val catalogue = remember { KotatsuSource.catalogue() }
    var query by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var komga by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) = scope.launch {
        error = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Source settings could not be saved" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Manga sources") }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }, text = {
        LazyColumn(Modifier.heightIn(max = 560.dp).fillMaxWidth().testTag("manga:sources:list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "management") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.cacheLimitBytes / (1024 * 1024)} MB page storage", modifier = Modifier.weight(1f))
                TextButton(onClick = { action { repository.clearCache() } }) { Text("Clear cache") }
            }
            FlowRow {
                listOf(128L, 256L, 512L, 1024L).forEach { mb -> TextButton(onClick = { action { repository.store.update { it.copy(cacheLimitBytes = mb * 1024 * 1024) } } }) { Text("$mb") } }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { komga = !komga }) { Text("Connect Komga library") }
            MangaExtensionControls(repository, onError = { error = it })
            MangaTemplateControls(repository)
            if (komga) {
                OutlinedTextField(server, { server = it.take(2_048) }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.testTag("manga:komga:server"))
                OutlinedTextField(key, { key = it.take(512) }, label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.testTag("manga:komga:key"))
                Text("Or sign in with a Komga user name and password instead of an API key.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(user, { user = it.take(256) }, label = { Text("User name") }, singleLine = true)
                OutlinedTextField(password, { password = it.take(512) }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                TextButton(enabled = !testing, onClick = { scope.launch {
                    testing = true
                    try {
                        val config = KomgaSource.configuration(server, key, user, password).let { next ->
                            state.sources.find { it.id == next.id }?.let { previous -> next.copy(enabled = previous.enabled) } ?: next
                        }
                        KomgaSource(config, repository.context.httpClient).browse()
                        repository.store.source(config); repository.invalidateSource(config.id)
                        key = ""; password = ""; komga = false; error = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Komga could not be reached" }
                    finally { testing = false }
                } }) { Text("Test and save") }
            }
            state.sources.filter { it.kind == "komga" }.forEach { config ->
                Row(Modifier.fillMaxWidth().testTag("manga:komga:${config.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(config.name); Text(config.baseUrl, style = MaterialTheme.typography.labelSmall) }
                    Switch(checked = config.enabled, onCheckedChange = { enabled -> action { repository.store.source(config.copy(enabled = enabled)); repository.invalidateSource(config.id) } },
                        modifier = Modifier.semantics { contentDescription = "Use ${config.name}" })
                    TextButton(onClick = { action {
                        repository.store.update { it.copy(sources = it.sources.filterNot { source -> source.id == config.id }) }
                        repository.invalidateSource(config.id)
                    } }) { Text("Remove") }
                }
            }
            OutlinedTextField(query, { query = it.take(500) }, label = { Text("Find source") }, singleLine = true, modifier = Modifier.testTag("manga:sources:query"))
            OutlinedTextField(language, { language = it.take(32) }, label = { Text("Language code, e.g. en or ja") }, singleLine = true, modifier = Modifier.testTag("manga:sources:language"))
            } }
                items(catalogue.filter { it.title.contains(query, true) && it.language.contains(language, true) }, key = { it.id }) { source ->
                    val enabled = state.sources.any { it.id == source.id && it.enabled }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("manga:source:${source.id}")
                        .toggleable(value = enabled, role = Role.Switch, onValueChange = { value -> action {
                            val previous = state.sources.find { it.id == source.id }
                            repository.store.source(previous?.copy(enabled = value) ?: MangaSourceConfig(source.id, name = source.title, language = source.language, enabled = value))
                        } }).semantics(mergeDescendants = true) { contentDescription = "${source.title} (${source.language})" }
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(source.title); Text(source.language, style = MaterialTheme.typography.labelSmall) }
                        Switch(checked = enabled, onCheckedChange = null)
                    }
                }
        }
    })
}
