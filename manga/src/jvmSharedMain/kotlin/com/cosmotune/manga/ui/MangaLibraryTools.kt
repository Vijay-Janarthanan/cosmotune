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
            ReadButton(mangaText("Restore library backup"), transfer.restore, kind = ReadKind.TONAL, icon = CosmoIcons.Replay, modifier = Modifier.testTag("manga:backup:restore"))
        }
        // The note under the backup buttons stands a text-to-action gap from them (8 + 4), and the next group a group's gap (8 + 16).
        Text("Backups contain reading history, collections and source URLs. Source credentials, browser sessions and comic files are excluded. Reimport local comics before restoring their collections.", style = ReadType.meta(), color = look.dim, modifier = Modifier.padding(top = 4.dp))
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
        Text("Checks saved series on enabled sources; no automatic downloads. Android uses connected, battery-safe background work. Desktop checks while the app is open.", style = ReadType.meta(), color = look.dim)
        if (state.lastUpdateAttemptMs > 0) Text(if (state.lastUpdateCompletedMs >= state.lastUpdateAttemptMs)
            "Last check: ${state.lastUpdateNewChapters} new chapters · ${state.lastUpdateFailures} unavailable series"
            else "The last check did not finish; use Check for updates to retry.", style = ReadType.meta(), color = look.dim)
        message?.let { Text(it, style = ReadType.meta(), color = look.ink) }
    }
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
