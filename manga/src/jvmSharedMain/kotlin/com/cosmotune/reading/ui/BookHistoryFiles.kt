package com.cosmotune.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Today", "Yesterday", "This week", "This month" or "Earlier", for grouping the history by when a book was opened. */
internal fun historyGroup(atMs: Long, today: LocalDate = LocalDate.now()): String {
    val day = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        !day.isBefore(today) -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> "This week"
        day.isAfter(today.minusDays(31)) -> "This month"
        else -> "Earlier"
    }
}

/** The history: books in the order they were last opened, by day, each removable from it (the book and its place stay). */
@Composable
internal fun BookHistorySheet(scope: ReadScope, onDismiss: () -> Unit) {
    val state by scope.repository.state.collectAsUiState()
    val look = orbitLook()
    val entries = state.opened.entries.mapNotNull { (id, at) -> state.books.firstOrNull { it.id == id }?.let { it to at } }.sortedByDescending { it.second }
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss, expanded = true) {
        ReadSheetHeader("History", onDone = onDismiss, doneLabel = "Close")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).testTag("books:history")) {
            if (entries.isEmpty()) item(key = "empty") {
                Text("Books you open show here, the latest first.", style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            entries.groupBy { historyGroup(it.second) }.forEach { (group, rows) ->
                item(key = "group:$group") { ReadSection(group, subtitle = rows.size.toString()) }
                items(rows, key = { "history:${it.first.id}" }) { (book, _) ->
                    ReadCoverRow(book.title, state.bookLine(book), book.coverPath?.let(::File), onClick = { onDismiss(); scope.open(book) }, key = book.id,
                        modifier = Modifier.padding(horizontal = ReadMargin).testTag("books:history:${book.id}")) {
                        ReadIconButton(CosmoIcons.Close, "Remove from history", { scope.action { scope.repository.forgetOpened(book.id) } },
                            Modifier.testTag("books:history:remove:${book.id}"))
                    }
                }
            }
            if (entries.isNotEmpty()) item(key = "clear") {
                ReadButton("Clear history", { scope.action { scope.repository.forgetOpened(null) } }, Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("books:history:clear"), kind = ReadKind.TONAL)
            }
            item(key = "end") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * Browsing book files in a folder on this device, with what each says about itself (title, author, format, size) and whether it is
 * in the Library already; a tap adds it (or opens the copy in the Library). Folders added to Read are offered first.
 */
@Composable
internal fun BookFilesSheet(scope: ReadScope, onDismiss: () -> Unit) {
    val state by scope.repository.state.collectAsUiState()
    val look = orbitLook()
    val folders = rememberReadingFolders()
    var folder by remember { mutableStateOf(state.folders.firstOrNull()) }
    var files by remember { mutableStateOf<List<Pair<ReadingFolderFile, ReadingFileInfo>>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(folder) {
        val current = folder ?: return@LaunchedEffect
        files = null
        files = try {
            withContext(Dispatchers.IO) {
                folders.list(current).filter { ReadingFilePeek.isBook(it.name) }.sortedBy { it.name.lowercase() }.take(2_000)
                    .map { it to ReadingFilePeek.peek(it.name, it.open) }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { scope.onError(e.message); emptyList() }
    }
    val known = state.folderFiles
    val titles = remember(state.books) { state.books.map { it.title.lowercase() }.toSet() }
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss, expanded = true) {
        ReadSheetHeader("Files", onDone = onDismiss, doneLabel = "Close")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).testTag("books:files")) {
            item(key = "folders") {
                ReadChipRow(Modifier.padding(bottom = 8.dp)) {
                    state.folders.forEach { item -> ReadChip(item.name, folder?.location == item.location, { folder = item }, Modifier.testTag("books:files:folder:${item.name}")) }
                    folder?.takeIf { chosen -> state.folders.none { it.location == chosen.location } }?.let { ReadChip(it.name, true, {}) }
                    ReadChip("Choose a folder", false, { folders.pick { picked -> folder = picked } }, Modifier.testTag("books:files:choose"), icon = CosmoIcons.Plus)
                }
            }
            val listed = files
            when {
                folder == null -> item(key = "none") {
                    Text("Choose a folder to see its books: their titles and authors, read from the files.", style = ReadType.body(), color = look.dim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                listed == null -> item(key = "wait") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
                }
                listed.isEmpty() -> item(key = "empty") { ReadEmpty("No books here", "This folder and its subfolders have no book files Read opens.") }
                else -> {
                    item(key = "search") { ReadSearchField(query, { query = it }, "Find a title or author", Modifier.testTag("books:files:search")) }
                    val shown = listed.filter { (_, info) -> query.isBlank() || info.title.contains(query, true) || info.author.contains(query, true) }
                    items(shown, key = { it.first.location }) { (file, info) ->
                        val inLibrary = known[file.key]?.takeIf { it != "!" }?.let { id -> state.books.firstOrNull { it.id == id } }
                            ?: state.books.firstOrNull { info.title.lowercase() in titles && it.title.equals(info.title, true) }
                        ReadCoverRow(info.title, listOfNotNull(info.author.ifBlank { null }, info.format, sizeLabel(file.size), if (inLibrary != null) "In your Library" else null)
                            .joinToString(" · "), null, onClick = {
                                if (inLibrary != null) { onDismiss(); scope.open(inLibrary) }
                                else scope.action { val book = scope.repository.importFile(file.name, file.open); onDismiss(); scope.open(book) }
                            }, key = file.location, highlighted = inLibrary != null, modifier = Modifier.padding(horizontal = ReadMargin).testTag("books:files:${file.name}"))
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
