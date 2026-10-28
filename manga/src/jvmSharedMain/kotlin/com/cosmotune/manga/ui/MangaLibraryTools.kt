package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cosmotune.manga.data.*
import kotlinx.coroutines.*

internal class MangaBackupTransfer(val export: (ByteArray) -> Unit, val restore: () -> Unit)
@Composable internal expect fun rememberMangaBackupTransfer(onImport: (ByteArray) -> Unit, onResult: (Result<Unit>) -> Unit): MangaBackupTransfer
@Composable internal expect fun MangaBackgroundUpdates(repository: MangaRepository)

@Composable internal fun MangaLibraryTools(repository: MangaRepository) {
    val state by repository.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<MangaLibraryBackup?>(null) }
    var busy by remember { mutableStateOf(false) }
    val transfer = rememberMangaBackupTransfer({ bytes ->
        scope.launch { try { pending = withContext(Dispatchers.IO) { MangaLibraryBackup.decode(bytes) }; message = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "Library backup could not be opened" } }
    }, { result -> message = result.exceptionOrNull()?.message ?: "Library backup saved" })
    Column {
        FlowRow {
            TextButton(enabled = !busy, modifier = Modifier.testTag("manga:backup:export"), onClick = { scope.launch {
                busy = true
                try { transfer.export(withContext(Dispatchers.IO) { MangaLibraryBackup.from(state).encode() }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message }
                finally { busy = false }
            } }) { Text(mangaText("Export library backup")) }
            TextButton(onClick = transfer.restore, modifier = Modifier.testTag("manga:backup:restore")) { Text(mangaText("Restore library backup")) }
        }
        Text("Backups contain reading history, collections and source URLs. Source credentials, browser sessions and comic files are excluded. Reimport local comics before restoring their collections.", style = MaterialTheme.typography.bodySmall)
        Text("Automatic chapter updates", style = MaterialTheme.typography.titleSmall)
        FlowRow {
            listOf(0 to "Off", 24 to "Daily", 168 to "Weekly").forEach { (hours, label) ->
                FilterChip(selected = state.updateIntervalHours == hours, onClick = { scope.launch {
                    try { repository.store.updateSchedule(hours) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = failure.message }
                } }, label = { Text(mangaText(label)) }, modifier = Modifier.testTag("manga:updates:$hours"))
            }
        }
        Text("Checks saved series on enabled sources; no automatic downloads. Android uses connected, battery-safe background work. Desktop checks while the app is open.", style = MaterialTheme.typography.bodySmall)
        if (state.lastUpdateAttemptMs > 0) Text(if (state.lastUpdateCompletedMs >= state.lastUpdateAttemptMs)
            "Last check: ${state.lastUpdateNewChapters} new chapters · ${state.lastUpdateFailures} unavailable series"
            else "The last check did not finish; use Check for updates to retry.", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    pending?.let { backup -> AlertDialog(onDismissRequest = { pending = null }, title = { Text(mangaText("Restore Manga library?")) },
        text = { Text("Merge ${backup.books.size} online series and ${backup.collections.size} collections? Existing series and reading positions are kept. Sources must be configured separately.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { backup.merge(repository.store); pending = null; message = "Library metadata restored" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message }
            finally { busy = false }
        } }) { Text(mangaText("Merge library")) } }, dismissButton = { TextButton(onClick = { pending = null }) { Text(mangaText("Cancel")) } }) }
}
