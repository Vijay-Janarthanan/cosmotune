package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.listening.ListeningLibrary
import com.cosmotune.listening.ListeningPreferences
import com.cosmotune.listening.ListeningStore
import com.cosmotune.remote.*
import com.cosmotune.ui.listening.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class PhoneRemoteInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsStartARealPairedServerStopClientsAndRotateTheNextPairingLink() {
        val commands = CopyOnWriteArrayList<RemoteCommand>()
        val controller = LanRemoteController(object : LanRemoteHost {
            override fun playback() = RemotePlayback("Fixture song", "Fixture singer", true, 1000, 60_000, 0.5f)
            override suspend fun command(command: RemoteCommand) { commands += command }
        })
        val file = Files.createTempDirectory("cosmotune-remote-ui-").resolve("listening.json").toFile()
        ListeningStore(file).update { it.copy(preferences = ListeningPreferences(setupComplete = true)) }
        var qrLink: String? = null
        try {
            compose.setContent { MaterialTheme {
                ListeningTools(true, ListeningToolsHost(file, ListeningLibrary(), { ListeningQueue(emptyList(), 0, 0, 0, false) },
                    replay = { ReplayTracks("", emptyList()) }, createPlaylist = { _, _ -> }, restore = {}, play = {},
                    openRelease = { _, _ -> }, findMusic = {}, importMusic = {}, applySetup = { _, _ -> }, exportFile = { _, _ -> false }, importFile = { null },
                    remote = ListeningRemoteHost(controller) { link -> qrLink = link; Text("Pairing QR", Modifier.testTag("remote:test-qr")) }), {})
            } }
            compose.onNodeWithTag("listening:tab:REMOTE").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("This computer only").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("This computer only").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:remote:start").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
            compose.onNodeWithTag("listening:remote:start").performScrollTo().performClick()
            compose.waitUntil(10_000) { controller.session.value != null && qrLink != null }
            val first = assertNotNull(controller.session.value)
            compose.onNodeWithTag("listening:remote:running").performScrollTo().assertIsDisplayed()
            assertEquals(first.pairingUrl, qrLink)
            val key = first.pairingUrl.substringAfter("#key=")
            fun getStatus(authenticated: Boolean): HttpURLConnection = (URL(first.origin + "/api/status").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000
                if (authenticated) setRequestProperty("Authorization", "Bearer $key")
            }
            getStatus(false).let { connection -> try { assertEquals(401, connection.responseCode) } finally { connection.disconnect() } }
            getStatus(true).let { connection -> try { assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("Fixture song")) } finally { connection.disconnect() } }
            (URL(first.origin + "/api/action").openConnection() as HttpURLConnection).let { connection -> try {
                connection.connectTimeout = 3000; connection.readTimeout = 3000; connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $key"); connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("""{"action":"PAUSE"}""".toByteArray()) }
                assertEquals(200, connection.responseCode)
            } finally { connection.disconnect() } }
            assertEquals(RemoteAction.PAUSE, commands.single().action)
            compose.onNodeWithTag("listening:remote:stop").performScrollTo().performClick()
            compose.waitUntil(10_000) { controller.session.value == null }
            val address = URI(first.origin)
            assertFails { java.net.Socket(address.host, address.port).use {} }
            compose.onNodeWithTag("listening:remote:start").performScrollTo().performClick()
            compose.waitUntil(10_000) { controller.session.value != null && controller.session.value!!.pairingUrl != first.pairingUrl }
            assertNotEquals(first.pairingUrl, controller.session.value!!.pairingUrl)
        } finally { controller.close() }
    }
}
