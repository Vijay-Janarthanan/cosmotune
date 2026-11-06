package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.providers.*
import com.cosmotune.ui.providers.ProviderPackagesDialog
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ProviderServiceHealthInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aFailedEndpointIsVisibleWithoutDisablingProviderAndDetailsShowItsDeclaredCapabilities(): Unit = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("health.example", chain.request().url.host)
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val protection = object : ProviderFileProtection {
            override fun seal(bytes: ByteArray) = bytes
            override fun open(bytes: ByteArray) = bytes
        }
        ProviderManager(ProviderStore(temporary.newFolder(), protection), httpClient = client).use { manager ->
            val installed = ProviderInstall(ProviderManifest("fixture", "1.0.0", "Fixture provider", type = listOf("metadata_provider", "download_provider"),
                permissions = ProviderPermissions(network = listOf("health.example")),
                serviceHealth = listOf(ProviderHealthCheck("gateway", "https://health.example/status", label = "Gateway"))), "a".repeat(64))
            manager.store.update { it.copy(installed = listOf(installed)) }
            compose.setContent { PodioTestTheme { ProviderPackagesDialog({}, manager) } }
            compose.onNodeWithTag("provider:fixture:health").performScrollTo().performClick()
            compose.waitUntil(10_000) { manager.serviceHealth.reports.value.isNotEmpty() }
            compose.onNodeWithText("Gateway · Endpoint or service unavailable · HTTP 404", substring = true).assertExists()
            compose.onNodeWithTag("provider:fixture:enabled").assertIsOn()
            compose.runOnIdle { assertTrue(manager.store.state.value.installed.single().enabled); assertEquals(1, calls.get()) }
            compose.onNodeWithText("Details", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Music catalogue, search and collections", substring = false).assertExists()
            compose.onNodeWithText("Audio streams and downloads", substring = false).assertExists()
        }
    }
}
