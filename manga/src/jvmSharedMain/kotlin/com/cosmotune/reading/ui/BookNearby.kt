package com.cosmotune.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.NearbyDevice
import com.cosmotune.reading.data.ReadingBook
import com.cosmotune.reading.data.ReadingNearby
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** This device's model and kind for Nearby: ("Pixel 8", "mobile") or ("DESKTOP-1", "desktop"). */
internal expect fun readingDeviceModel(): Pair<String, String>

/** Lets this device hear other devices' hellos while [active] (Android drops multicast on Wi-Fi otherwise). */
@Composable internal expect fun NearbyMulticastLock(active: Boolean)

/** The name a book file goes by on another device: its title and its file's own ending. */
internal fun nearbyFileName(book: ReadingBook): String =
    ReadingNearby.safeName(book.title.take(100) + "." + book.filePath.substringAfterLast('.', "epub").lowercase())

/**
 * Nearby: sending [books] to the listener's other devices on the same network (Cosmotune, or any LocalSend app), and taking books
 * from them while it is open. Each device that sends is asked about first.
 */
@Composable
internal fun BookNearbySheet(scope: ReadScope, books: List<ReadingBook>, onDismiss: () -> Unit) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val work = rememberCoroutineScope()
    val nearby = remember {
        val (model, kind) = readingDeviceModel()
        ReadingNearby("Cosmotune on $model", model, kind, scope.repository.client, File(scope.repository.root, "nearby-inbox")) { name, file ->
            try { scope.repository.importFile(name) { file.inputStream() } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { scope.onError("$name could not be added: ${e.message}") }
        }
    }
    NearbyMulticastLock(true)
    DisposableEffect(nearby) { nearby.start(); onDispose { nearby.stop() } }
    val devices by nearby.devices.collectAsUiState()
    val offer by nearby.offer.collectAsUiState()
    val receiving by nearby.receiving.collectAsUiState()
    var searching by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    fun send(device: NearbyDevice) {
        if (sending != null) return
        sending = device.id to 0
        sent = null
        work.launch {
            try {
                nearby.send(device, books.map { nearbyFileName(it) to File(it.filePath) }) { done -> sending = device.id to done }
                sent = if (books.size == 1) "Sent to ${device.name}." else "${books.size} books sent to ${device.name}."
            } catch (e: CancellationException) { throw e } catch (e: Exception) { scope.onError(e.message ?: "The books could not be sent.") }
            finally { sending = null }
        }
    }
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss, expanded = true) {
        ReadSheetHeader("Nearby", onDone = onDismiss, doneLabel = "Close")
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).testTag("books:nearby"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This device shows as “${nearby.name}” to Cosmotune and LocalSend on the same Wi-Fi. Keep this open to receive books.",
                style = ReadType.body(), color = look.dim)
            offer?.let { incoming ->
                val names = incoming.books.map { it.fileName }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(ReadShape.md).background(look.row).padding(16.dp).testTag("books:nearby:offer"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${incoming.from.alias} wants to send " + if (names.size == 1) "a book" else "${names.size} books", style = ReadType.rowTitle(), color = look.ink)
                    Text(names.take(4).joinToString("\n") + if (names.size > 4) "\nand ${names.size - 4} more" else "", style = ReadType.body(), color = look.dim,
                        maxLines = 5, overflow = TextOverflow.Ellipsis)
                    if (incoming.files.size > names.size) Text("Other files are left out; Read takes books only.", style = fonts.label, color = look.dim)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReadButton("Add to Library", { nearby.answer(incoming, true) }, Modifier.testTag("books:nearby:accept"))
                        ReadButton("No thanks", { nearby.answer(incoming, false) }, Modifier.testTag("books:nearby:decline"), kind = ReadKind.TONAL)
                    }
                }
            }
            receiving?.let { (done, total) ->
                Text("Receiving ${minOf(done + 1, total)} of $total…", style = ReadType.rowTitle(), color = look.ink, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(when {
                books.isEmpty() -> "Devices nearby"
                books.size == 1 -> "Send “${books.single().title}” to"
                else -> "Send ${books.size} books to"
            }, style = ReadType.rowTitle(), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (devices.isEmpty()) Text("Looking… Open Nearby in Cosmotune (Read › Library › +) or LocalSend on the other device, on the same Wi-Fi.",
                style = ReadType.body(), color = look.dim)
            devices.forEach { device ->
                val busy = sending?.first == device.id
                ReadSheetRow(device.name, CosmoIcons.Devices,
                    { send(device) }, Modifier.testTag("books:nearby:device:${device.host}"), enabled = books.isNotEmpty() && sending == null,
                    trailing = { Text(if (busy) "Sending ${sending?.second ?: 0} of ${books.size}" else device.kind.ifBlank { device.host }, style = fonts.label, color = look.dim) })
            }
            sent?.let { Text(it, style = ReadType.body(), color = look.ink) }
            Spacer(Modifier.height(4.dp))
            ReadButton(if (searching) "Looking…" else "Look again", {
                searching = true
                work.launch { try { nearby.search() } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { searching = false } }
            }, Modifier.testTag("books:nearby:search"), kind = ReadKind.TONAL, icon = CosmoIcons.Repeat, enabled = !searching)
            if (books.isEmpty()) Text("To send, choose books in the Library with the tick button, or use Send nearby in a book's menu.",
                style = fonts.label, color = look.dim)
            Spacer(Modifier.height(16.dp))
        }
    }
}
