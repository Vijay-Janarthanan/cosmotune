package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.providers.SubsonicCatalogue
import com.cosmotune.ui.providers.SubsonicServerDialog
import com.cosmotune.ui.providers.SubsonicServerEdit
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shared Subsonic server editor, against a real loopback server: wrong password refused, right one saved. */
@OptIn(ExperimentalTestApi::class)
class SubsonicServerInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val q = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(("sesame" + q["s"]).toByteArray()).joinToString("") { "%02x".format(it) }
            val body = if (q["u"] == "vijay" && q["t"] == md5)
                """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome"}}"""
            else """{"subsonic-response":{"status":"failed","error":{"code":40}}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @After fun stop() = server.stop(0)

    @Test fun aServerIsSavedOnlyOnceItAcceptsTheCredential() {
        var saved: SubsonicServerEdit? = null
        compose.setContent {
            MaterialTheme {
                SubsonicServerDialog(isNew = true, label = "", address = "", settings = emptyMap(), http = OkHttpClient(),
                    onSave = { saved = it }, onRemove = null, onDismiss = {})
            }
        }
        compose.onNodeWithTag("subsonic-address").performTextInput("http://127.0.0.1:${server.address.port}/rest/")
        compose.onNodeWithTag("subsonic-user").performTextInput("vijay")
        compose.onNodeWithTag("subsonic-secret").performTextInput("wrong")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Wrong user name or password.").fetchSemanticsNodes().isNotEmpty() }
        assertNull(saved)

        compose.onNodeWithTag("subsonic-secret").performTextClearance()
        compose.onNodeWithTag("subsonic-secret").performTextInput("sesame")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        val edit = saved!!
        assertEquals("http://127.0.0.1:${server.address.port}", edit.address)
        assertEquals(mapOf(SubsonicCatalogue.KEY_USER to "vijay", SubsonicCatalogue.KEY_PASSWORD to "sesame"), edit.settings)
        assertEquals("Navidrome", edit.label) // named after the server when left blank
        compose.onNodeWithText("Connected · navidrome 1.16.1").assertExists()
    }

    @Test fun editingKeepsTheSavedPasswordWhenTheFieldIsLeftEmpty() {
        var saved: SubsonicServerEdit? = null
        val address = "http://127.0.0.1:${server.address.port}"
        compose.setContent {
            MaterialTheme {
                SubsonicServerDialog(isNew = false, label = "Home", address = address,
                    settings = mapOf(SubsonicCatalogue.KEY_USER to "vijay", SubsonicCatalogue.KEY_PASSWORD to "sesame"), http = OkHttpClient(),
                    onSave = { saved = it }, onRemove = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Leave empty to keep the saved one").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals("sesame", saved!!.settings[SubsonicCatalogue.KEY_PASSWORD])
        assertEquals("Home", saved!!.label)
    }
}
