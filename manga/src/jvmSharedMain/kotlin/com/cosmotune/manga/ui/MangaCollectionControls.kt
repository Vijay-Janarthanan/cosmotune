package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The library's collection chips (Mihon's categories): All, each collection, Unassigned, and one chip to manage them in a sheet. */
@Composable internal fun MangaCollections(repository: MangaRepository, selected: String, onSelected: (String) -> Unit) {
    val state by repository.store.state.collectAsState()
    var manage by remember { mutableStateOf(false) }
    ReadChipRow(Modifier.padding(vertical = 4.dp).testTag("manga:collections")) {
        ReadChip(mangaText("All"), selected == "", { onSelected("") })
        state.collections.forEach { collection -> ReadChip(collection.name, selected == collection.id, { onSelected(collection.id) }) }
        if (state.collections.isNotEmpty()) ReadChip(mangaText("Unassigned"), selected == "unassigned", { onSelected("unassigned") })
        ReadChip(mangaText(if (state.collections.isEmpty()) "New collection" else "Edit"), false, { manage = true }, Modifier.testTag("manga:collection:manage"), icon = CosmoIcons.Plus)
    }
    if (manage) MangaCollectionManager(repository) { manage = false; if (selected !in listOf("", "unassigned") && state.collections.none { it.id == selected }) onSelected("") }
}

@Composable private fun MangaCollectionManager(repository: MangaRepository, onDismiss: () -> Unit) {
    val state by repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<MangaCollection?>(null) }
    fun action(block: suspend () -> Unit) { scope.launch { try { block(); error = null } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message } } }
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader(mangaText("Collections"), onDone = onDismiss)
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(mangaText("Collection name")) }, modifier = Modifier.fillMaxWidth().testTag("manga:collection:name"))
            KitButton(mangaText(if (editing == null) "Create collection" else "Save name"), {
                action { val id = editing; if (id == null) repository.store.createCollection(name) else repository.store.renameCollection(id, name); name = ""; editing = null }
            }, kind = KitButtonKind.TONAL, modifier = Modifier.testTag("manga:collection:save"))
            error?.let { Text(it, style = ReadType.body(), color = Color(palette.danger)) }
            state.collections.forEach { collection ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(collection.name, style = ReadType.rowTitle(), color = Color(palette.textPrimary))
                        Text(if (collection.bookIds.size == 1) "1 series" else "${collection.bookIds.size} series", style = ReadType.meta(), color = Color(palette.textSecondary))
                    }
                    KitTextButton(mangaText("Rename"), { editing = collection.id; name = collection.name }, Modifier.testTag("manga:collection:rename:${collection.id}"))
                    KitTextButton(mangaText("Delete"), { deleting = collection }, Modifier.testTag("manga:collection:delete:${collection.id}"))
                }
                ReadHairline(start = 0.dp)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    deleting?.let { collection -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${collection.name}?") },
        text = { Text(mangaText("The series and their reading progress stay in your library.")) },
        confirmButton = { TextButton(onClick = { action { repository.store.deleteCollection(collection.id); deleting = null } }) { Text(mangaText("Delete collection"), color = Color(palette.danger)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(mangaText("Cancel")) } }) }
}

/** The series page's "In library" button (the collections it belongs to) and its sheet of collections to tick. */
@Composable internal fun MangaCollectionAssignment(repository: MangaRepository, bookId: String) {
    val state by repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    var open by remember(bookId) { mutableStateOf(false) }
    var error by remember(bookId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val count = state.collections.count { bookId in it.bookIds }
    KitButton(mangaText("In library") + if (count > 0) " · $count" else "", { open = true }, kind = KitButtonKind.TONAL, icon = CosmoIcons.HeartFill,
        modifier = Modifier.testTag("manga:collection:assign"))
    if (open) KitSheet(onDismiss = { open = false }) {
        ReadSheetHeader(mangaText("Assign collections"), onDone = { open = false })
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            if (state.collections.isEmpty()) Text(mangaText("Create a collection from the chips above your library."), style = ReadType.body(), color = Color(palette.textSecondary),
                modifier = Modifier.padding(8.dp))
            error?.let { Text(it, style = ReadType.body(), color = Color(palette.danger)) }
            state.collections.forEach { collection ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(bookId in collection.bookIds, onCheckedChange = { included -> scope.launch {
                        try { repository.store.assignCollection(bookId, collection.id, included); error = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                    } }, modifier = Modifier.testTag("manga:collection:member:${collection.id}"))
                    Text(collection.name, style = ReadType.rowTitle(), color = Color(palette.textPrimary))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
