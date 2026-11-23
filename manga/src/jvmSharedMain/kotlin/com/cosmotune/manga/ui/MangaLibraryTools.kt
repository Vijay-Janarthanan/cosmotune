package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cosmotune.manga.data.*
import kotlinx.coroutines.*

internal class MangaBackupTransfer(val export: (ByteArray) -> Unit, val restore: () -> Unit, /** Saves a Mihon backup (.tachibk). */ val exportMihon: (ByteArray) -> Unit)
@Composable internal expect fun rememberMangaBackupTransfer(onImport: (ByteArray) -> Unit, onResult: (Result<Unit>) -> Unit): MangaBackupTransfer
@Composable internal expect fun MangaBackgroundUpdates(repository: MangaRepository)

@Composable internal fun MangaLibraryTools(repository: MangaRepository) {
    val state by repository.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<MangaLibraryBackup?>(null) }
    var mihon by remember { mutableStateOf<MihonBackupImport?>(null) }
    var busy by remember { mutableStateOf(false) }
    val transfer = rememberMangaBackupTransfer({ bytes ->
        scope.launch { try {
                if (MihonBackupImport.looksLikeOne(bytes)) mihon = withContext(Dispatchers.IO) { MihonBackupImport.read(bytes, repository.store.state.value.sources) }
                else pending = withContext(Dispatchers.IO) { MangaLibraryBackup.decode(bytes) }
                message = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "Library backup could not be opened" } }
    }, { result -> message = result.exceptionOrNull()?.message ?: "Library backup saved" })
    val look = com.cosmotune.ui.orbit.orbitLook()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadButton(mangaText("Export library backup"), enabled = !busy, kind = ReadKind.TONAL, icon = CosmoIcons.Download, modifier = Modifier.testTag("manga:backup:export"), onClick = { scope.launch {
                busy = true
                try { transfer.export(withContext(Dispatchers.IO) { MangaLibraryBackup.from(state).encode() }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message }
                finally { busy = false }
            } })
            ReadButton(mangaText("Export for Mihon"), enabled = !busy, kind = ReadKind.TONAL, icon = CosmoIcons.Download, modifier = Modifier.testTag("manga:backup:mihon"), onClick = { scope.launch {
                busy = true
                try { transfer.exportMihon(withContext(Dispatchers.IO) { MihonBackupImport.export(state) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message }
                finally { busy = false }
            } })
            ReadButton(mangaText("Restore library backup"), transfer.restore, kind = ReadKind.TONAL, icon = CosmoIcons.Replay, modifier = Modifier.testTag("manga:backup:restore"))
        }
        // The note under the backup buttons stands a text-to-action gap from them (8 + 4), and the next group a group's gap (8 + 16).
        Text("Backups contain reading history, collections and source URLs. Source credentials, browser sessions and comic files are excluded. Reimport local comics before restoring their collections. Restore also reads Mihon and Tachiyomi backups (.tachibk).", style = ReadType.meta(), color = look.dim, modifier = Modifier.padding(top = 4.dp))
        Text(mangaText("Automatic chapter updates"), style = ReadType.rowTitle(), color = look.ink, modifier = Modifier.padding(top = 16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 24 to "Daily", 168 to "Weekly").forEach { (hours, label) ->
                ReadChip(mangaText(label), state.updateIntervalHours == hours, { scope.launch {
                    try { repository.store.updateSchedule(hours) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = failure.message }
                } }, Modifier.testTag("manga:updates:$hours"))
            }
        }
        com.cosmotune.ui.orbit.OrbitSwitchRow(mangaText("Skip finished series"), state.updateSkipFinished, { on -> scope.launch {
            try { repository.store.update { it.copy(updateSkipFinished = on) } } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { message = failure.message }
        } }, plain = true, detail = mangaText("Finished series you have read to the end are not checked"))
        Text("Checks saved series on enabled sources; new chapters download only when Downloads says so. Android uses connected, battery-safe background work. Desktop checks while the app is open.", style = ReadType.meta(), color = look.dim)
        if (state.lastUpdateAttemptMs > 0) Text(if (state.lastUpdateCompletedMs >= state.lastUpdateAttemptMs)
            "Last check: ${state.lastUpdateNewChapters} new chapters · ${state.lastUpdateFailures} unavailable series"
            else "The last check did not finish; use Check for updates to retry.", style = ReadType.meta(), color = look.dim)
        message?.let { Text(it, style = ReadType.meta(), color = look.ink) }
    }
    mihon?.let { backup -> com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = { mihon = null },
        title = mangaText("Add a Mihon backup?"),
        message = "Add ${backup.books.size} series and ${backup.collections.size} categories with their read chapters? " +
            (if (backup.unlinked == 0) "All of them open with your installed extensions." else if (backup.linked == 0) "Open each series to find it on one of your sources; its read chapters come along."
                else "${backup.linked} open with your installed extensions. For the other ${backup.unlinked}, open the series to find it on one of your sources.") +
            " Series already in your library are kept as they are.",
        confirmLabel = mangaText("Add to library"),
        onConfirm = { scope.launch {
            busy = true
            try { backup.merge(repository.store); mihon = null; message = "Added ${backup.books.size} series from the Mihon backup" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message }
            finally { busy = false }
        } },
        confirmEnabled = !busy && backup.books.isNotEmpty(),
        dismissLabel = mangaText("Cancel"),
        onDismiss = { mihon = null },
    ) }
    pending?.let { backup -> com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = { pending = null },
        title = mangaText("Restore Manga library?"),
        message = "Merge ${backup.books.size} online series and ${backup.collections.size} collections? Existing series and reading positions are kept. Sources must be configured separately.",
        confirmLabel = mangaText("Merge library"),
        onConfirm = { scope.launch {
            busy = true
            try { backup.merge(repository.store); pending = null; message = "Library metadata restored" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message }
            finally { busy = false }
        } },
        confirmEnabled = !busy,
        dismissLabel = mangaText("Cancel"),
        onDismiss = { pending = null },
    ) }
}
