package com.cosmotune.providers

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val protection = object : ProviderFileProtection {
        override fun seal(bytes: ByteArray) = bytes.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun open(bytes: ByteArray) = seal(bytes)
    }

    @Test fun settingsAndCredentialsRoundTripThroughProtectionAndConcurrentReopens() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), protection)
        store.update { it.copy(credentials = mapOf("fixture" to buildJsonObject { put("accessToken", "private-token") })) }
        assertFalse(File(store.root, "providers.json").readText().contains("private-token"))
        coroutineScope {
            launch(Dispatchers.IO) { repeat(30) { index -> store.update { it.copy(settings = mapOf("fixture" to buildJsonObject { put("counter", index) })) } } }
            launch(Dispatchers.IO) { repeat(30) {
                val restored = ProviderStore(store.root, protection)
                assertNull(restored.recoveryMessage)
                assertEquals("private-token", restored.state.value.credentials.getValue("fixture").getValue("accessToken").jsonPrimitive.content)
            } }
        }
    }

    @Test fun anUnreadableStateIsLeftUntouchedAndOrdinaryUpdatesCannotEraseIt() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val original = File(root, "providers.json").apply { writeText("broken protected state") }
        val store = ProviderStore(root, protection)
        assertNotNull(store.recoveryMessage)
        assertFailsWith<IllegalStateException> { store.update { it.copy(settings = emptyMap()) } }
        assertEquals("broken protected state", original.readText())
    }

    @Test fun aStaleManagerCannotEraseAnotherManagersSessionOrEnableChoice() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val older = ProviderStore(root, protection)
        val newer = ProviderStore(root, protection)
        val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider")), "0".repeat(64), enabled = false)
        newer.update { it.copy(installed = listOf(install), auth = mapOf("fixture" to buildJsonObject {
            put("access_token", "fresh-token"); put("_epoch", "current-session")
        })) }
        assertEquals(newer.state.value, older.state.value, "A store already loaded during retry must observe a later token write")
        older.update { it.copy(settings = it.settings + ("fixture" to buildJsonObject { put("quality", "lossless") })) }
        assertEquals(older.state.value, newer.state.value, "The replacement manager must observe the accepted settings too")
        val restored = ProviderStore(root, protection).state.value
        assertEquals("fresh-token", restored.auth.getValue("fixture")["access_token"]?.jsonPrimitive?.content)
        assertFalse(restored.installed.single().enabled)
        assertEquals("lossless", restored.settings.getValue("fixture")["quality"]?.jsonPrimitive?.content)

        newer.update { it.copy(auth = it.auth + ("fixture" to buildJsonObject { put("_epoch", "disconnected") })) }
        assertNull(older.state.value.auth.getValue("fixture")["access_token"], "Disconnect must reach every still-live instance")
        older.update { it.copy(credentials = it.credentials + ("other" to buildJsonObject { put("value", "unrelated") })) }
        assertNull(ProviderStore(root, protection).state.value.auth.getValue("fixture")["access_token"],
            "An old instance must not restore a token after Disconnect")
    }
}
