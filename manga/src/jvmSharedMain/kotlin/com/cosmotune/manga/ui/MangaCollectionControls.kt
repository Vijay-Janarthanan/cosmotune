package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun MangaCollections(repository: MangaRepository, selected: String, onSelected: (String) -> Unit) {
    val state by repository.store.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    Row {
        Box {
            TextButton(modifier = Modifier.testTag("manga:collection:filter"), onClick = { menu = true }) {
                Text(when (selected) { "" -> mangaText("All collections"); "unassigned" -> mangaText("Unassigned"); else -> state.collections.find { it.id == selected }?.name ?: mangaText("All collections") })
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(mangaText("All collections")) }, onClick = { onSelected(""); menu = false })
                DropdownMenuItem(text = { Text(mangaText("Unassigned")) }, onClick = { onSelected("unassigned"); menu = false })
                state.collections.forEach { collection -> DropdownMenuItem(text = { Text(collection.name) }, onClick = { onSelected(collection.id); menu = false }) }
            }
        }
        TextButton(onClick = { manage = true }, modifier = Modifier.testTag("manga:collection:manage")) { Text(mangaText("Manage collections")) }
    }
    if (manage) MangaCollectionManager(repository) { manage = false; if (selected !in listOf("", "unassigned") && state.collections.none { it.id == selected }) onSelected("") }
}

@Composable private fun MangaCollectionManager(repository: MangaRepository, onDismiss: () -> Unit) {
    val state by repository.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<MangaCollection?>(null) }
    fun action(block: suspend () -> Unit) { scope.launch { try { block(); error = null } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message } } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(mangaText("Collections")) }, confirmButton = { TextButton(onClick = onDismiss) { Text(mangaText("Done")) } }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(mangaText("Collection name")) }, modifier = Modifier.testTag("manga:collection:name"))
            TextButton(onClick = { action { val id = editing; if (id == null) repository.store.createCollection(name) else repository.store.renameCollection(id, name); name = ""; editing = null } }, modifier = Modifier.testTag("manga:collection:save")) { Text(mangaText(if (editing == null) "Create collection" else "Save name")) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.collections.forEach { collection ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${collection.name} (${collection.bookIds.size})", Modifier.weight(1f))
                    TextButton(onClick = { editing = collection.id; name = collection.name }, modifier = Modifier.testTag("manga:collection:rename:${collection.id}")) { Text(mangaText("Rename")) }
                    TextButton(onClick = { deleting = collection }, modifier = Modifier.testTag("manga:collection:delete:${collection.id}")) { Text(mangaText("Delete")) }
                }
            }
        }
    })
    deleting?.let { collection -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${collection.name}?") },
        text = { Text(mangaText("The series and their reading progress stay in your library.")) },
        confirmButton = { TextButton(onClick = { action { repository.store.deleteCollection(collection.id); deleting = null } }) { Text(mangaText("Delete collection")) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(mangaText("Cancel")) } }) }
}

@Composable internal fun MangaCollectionAssignment(repository: MangaRepository, bookId: String) {
    val state by repository.store.state.collectAsState()
    var open by remember(bookId) { mutableStateOf(false) }
    var error by remember(bookId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("manga:collection:assign")) { Text(mangaText("Collections")) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(mangaText("Assign collections")) }, confirmButton = { TextButton(onClick = { open = false }) { Text(mangaText("Done")) } }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            if (state.collections.isEmpty()) Text(mangaText("Create a collection from Manage collections in your library."))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.collections.forEach { collection ->
                Row {
                    Checkbox(bookId in collection.bookIds, onCheckedChange = { included -> scope.launch {
                        try { repository.store.assignCollection(bookId, collection.id, included); error = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                    } }, modifier = Modifier.testTag("manga:collection:member:${collection.id}"))
                    Text(collection.name)
                }
            }
        }
    })
}
