package com.cosmotune.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.CalibreServer
import com.cosmotune.reading.data.ReadingCalibreWireless
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Receiving from calibre: Read connects to calibre's wireless device connection, found on the network or at an address typed in,
 * and the books calibre sends to the device land in the Library. It stays connected while this is open.
 */
@Composable
internal fun BookCalibreSheet(scope: ReadScope, onDismiss: () -> Unit) {
    val look = orbitLook()
    val work = rememberCoroutineScope()
    val calibre = remember {
        ReadingCalibreWireless("Cosmotune on ${readingDeviceModel().first}", File(scope.repository.root, "calibre-inbox")) { name, file ->
            try { scope.repository.importFile(name) { file.inputStream() } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { scope.onError("$name could not be added: ${e.message}") }
        }
    }
    DisposableEffect(calibre) { onDispose { calibre.disconnect() } }
    val status by calibre.status.collectAsUiState()
    var found by remember { mutableStateOf<List<CalibreServer>?>(null) }
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var connection by remember { mutableStateOf<Job?>(null) }
    fun search() { found = null; work.launch { found = try { calibre.discover() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } } }
    LaunchedEffect(Unit) { search() }
    fun connect(host: String, port: Int) {
        connection?.cancel()
        connection = work.launch { calibre.connect(host, port, password) }
    }
    val connected = status is ReadingCalibreWireless.Status.Connected || status is ReadingCalibreWireless.Status.Receiving
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss, expanded = true) {
        ReadSheetHeader("calibre", onDone = onDismiss, doneLabel = "Close")
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).testTag("books:calibre"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("In calibre, choose Connect/share › Start wireless device connection. Books you then send to this device land in your Library.",
                style = ReadType.body(), color = look.dim)
            when (val now = status) {
                is ReadingCalibreWireless.Status.Connected -> Text("Connected to ${now.library}" + when (now.received) { 0 -> ". Send books from calibre now."; 1 -> " · 1 book received"; else -> " · ${now.received} books received" },
                    style = ReadType.rowTitle(), color = look.ink, modifier = Modifier.padding(top = 8.dp).testTag("books:calibre:status"))
                is ReadingCalibreWireless.Status.Receiving -> Text("Receiving ${now.index} of ${now.total}: ${now.name}", style = ReadType.rowTitle(), color = look.ink,
                    modifier = Modifier.padding(top = 8.dp).testTag("books:calibre:status"))
                is ReadingCalibreWireless.Status.Failed -> Text(now.message, style = ReadType.body(), color = look.ink, modifier = Modifier.padding(top = 8.dp).testTag("books:calibre:status"))
                ReadingCalibreWireless.Status.Idle -> Unit
            }
            if (connected) {
                Spacer(Modifier.height(4.dp))
                ReadButton("Disconnect", { connection?.cancel(); calibre.disconnect() }, Modifier.testTag("books:calibre:disconnect"), kind = ReadKind.TONAL, icon = CosmoIcons.Close)
            } else {
                Text("calibre on this network", style = ReadType.rowTitle(), color = look.ink, modifier = Modifier.padding(top = 8.dp))
                val servers = found
                when {
                    servers == null -> Text("Looking…", style = ReadType.body(), color = look.dim)
                    servers.isEmpty() -> Text("None found. Check calibre's wireless connection is started, or type its address below.", style = ReadType.body(), color = look.dim)
                    else -> servers.forEach { server ->
                        ReadSheetRow(server.name, CosmoIcons.Devices, { connect(server.host, server.port) }, Modifier.testTag("books:calibre:server:${server.host}"),
                            trailing = { Text("${server.host}:${server.port}", style = orbitFonts().label, color = look.dim) })
                    }
                }
                ReadButton("Look again", ::search, Modifier.testTag("books:calibre:search"), kind = ReadKind.TONAL, icon = CosmoIcons.Repeat, enabled = found != null)
                Text("Or connect by address", style = ReadType.rowTitle(), color = look.ink, modifier = Modifier.padding(top = 12.dp))
                OrbitInputField(address, { address = it.take(100) }, "Address, such as 192.168.1.5:9090", modifier = Modifier.fillMaxWidth().testTag("books:calibre:address"))
                OrbitInputField(password, { password = it.take(200) }, "Password (if calibre asks for one)", modifier = Modifier.fillMaxWidth().testTag("books:calibre:password"), secret = true)
                Spacer(Modifier.height(4.dp))
                ReadButton("Connect", {
                    val host = address.trim().substringBeforeLast(':').ifBlank { address.trim() }
                    val port = address.trim().substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: ReadingCalibreWireless.DEFAULT_PORT
                    connect(host, port)
                }, Modifier.testTag("books:calibre:connect"), enabled = address.isNotBlank())
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
