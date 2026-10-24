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
}
