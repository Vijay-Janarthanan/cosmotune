package com.cosmotune.ui.listening

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitBusyRing
import kotlinx.coroutines.CancellationException
import java.net.InetAddress

@Suppress("DEPRECATION")
@Composable
internal fun ColumnScope.PhoneRemotePage(host: ListeningRemoteHost, busy: Boolean, run: (suspend () -> String?) -> Unit) {
    val session by host.controller.session.collectAsState()
    val clipboard = LocalClipboardManager.current
    var addresses by remember(host.controller) { mutableStateOf<List<InetAddress>>(emptyList()) }
    var selected by remember(host.controller) { mutableStateOf<InetAddress?>(null) }
    var loading by remember(host.controller) { mutableStateOf(true) }
    var failure by remember(host.controller) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(host.controller, revision) {
        loading = true; failure = null
        try {
            addresses = host.controller.addresses()
            if (selected !in addresses) selected = addresses.firstOrNull()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure = "Network interfaces could not be read. Check your connection and retry." }
        finally { loading = false }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("listening:remote:page"), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Heading("Control Music from your phone", "Use a browser on the same local network for playback, position, volume and music-library search. Audio continues through this app's selected output.")
        }
        val active = session
        if (active == null) item {
            ToolSection("Network interface")
            if (loading) OrbitBusyRing(Modifier.padding(vertical = 6.dp))
            failure?.let { ToolError(it) }
            ToolChipRow {
                addresses.forEach { address ->
                    ToolChip(if (address.isLoopbackAddress) "This computer only" else address.hostAddress.orEmpty(), selected == address, { selected = address }, enabled = !busy)
                }
            }
            if (selected?.isLoopbackAddress == true) ToolNote("This interface opens a browser on this computer. Select a LAN address to connect your phone.")
            ToolButton("Refresh interfaces", enabled = !loading && !busy, kind = ToolKind.QUIET, onClick = { revision++ })
            ToolButton("Start remote", enabled = selected != null && !busy && !loading, tag = "listening:remote:start", onClick = { run {
                host.controller.start(checkNotNull(selected)); "Remote started. Pair using the current QR code or link."
            } })
        } else item {
            ToolSection("Remote is running", Modifier.testTag("listening:remote:running"))
            SelectionContainer { Text(active.origin, style = com.cosmotune.ui.orbit.orbitFonts().title) }
            host.qrCode(active.pairingUrl)
            Text("Scan to pair, or copy the private pairing link to your phone. Anyone with this link can control Music while the remote is running.")
            ToolButton("Copy pairing link", enabled = !busy, kind = ToolKind.QUIET, tag = "listening:remote:copy", onClick = { clipboard.setText(AnnotatedString(active.pairingUrl)) })
            ToolButton("Stop remote", enabled = !busy, tag = "listening:remote:stop", onClick = { run {
                host.controller.stop(); "Remote stopped. All paired browsers are disconnected."
            } })
            ToolNote("Stopping rotates the pairing key on the next start. Closing the application also disconnects clients; hiding this settings page keeps an enabled remote running.")
        }
    }
}
