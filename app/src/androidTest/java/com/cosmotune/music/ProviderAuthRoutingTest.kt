package com.cosmotune.music

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cosmotune.providers.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic codes and a separate encrypted store; never touches an installed account. */
@RunWith(AndroidJUnit4::class)
class ProviderAuthRoutingTest {
    @Test fun nativeCallbackIsConsumedBeforeMusicLinksAndStoredEncrypted() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "provider-routing-${UUID.randomUUID()}")
        val production = File(context.filesDir, "providers")
        val callback = "${context.packageName}://provider-auth/fixture"
        val state = UUID.randomUUID().toString()
        val code = "android-synthetic-code-${UUID.randomUUID()}"
        try {
            ProviderHub.configure(root, force = true)
            val manager = withTimeout(15_000) { ProviderHub.state.first { !it.loading }.manager!! }
            val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider"),
                permissions = ProviderPermissions(listOf("example.com"), storage = true)), "0".repeat(64))
            manager.store.update { it.copy(installed = listOf(install), auth = mapOf("fixture" to buildJsonObject {
                put("_epoch", UUID.randomUUID().toString())
                putJsonObject("pending") {
                    put("callback", callback); put("state", state); put("pkce", true)
                    put("createdAt", System.currentTimeMillis()); put("completed", false)
                }
            })) }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$callback?code=$code&state=$state"))
                .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, intent.data).setPackage(context.packageName), 0)
            assertNotNull("The installed manifest must register the app-owned scheme", resolved)
            // Real Activity launch checks the same cold-start handoff used by an external browser.
            val activity = instrumentation.startActivitySync(intent)
            try {
                assertNull("Authorization codes must leave the Activity intent", activity.intent.data)
                withTimeout(10_000) { manager.store.state.first { it.auth["fixture"]?.get("code") == JsonPrimitive(code) } }
                assertEquals(JsonPrimitive(true), manager.store.state.value.auth["fixture"]?.get("pending")?.jsonObject?.get("completed"))
                assertFalse(File(root, "providers.json").readBytes().toString(Charsets.ISO_8859_1).contains(code))
                assertNull(manager.completeRoutedAuthentication("$callback?code=$code&state=$state"))
                val unrelated = Intent(Intent.ACTION_VIEW, Uri.parse("another-reader://provider-auth/fixture?state=$state"))
                assertFalse(ProviderAuthCallbackBridge.consume(context, unrelated))
                assertNotNull(unrelated.data)
                // Warm handoff consumes a recognized but invalid callback without routing it to Music.
                val stale = Intent(Intent.ACTION_VIEW, Uri.parse("$callback?code=late&state=$state"))
                assertTrue(ProviderAuthCallbackBridge.consume(context, stale))
                assertNull(stale.data)
                assertEquals(JsonPrimitive(code), manager.store.state.value.auth["fixture"]?.get("code"))
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        } finally {
            ProviderHub.configure(production, force = true, cache = File(context.cacheDir, "provider-artwork"))
            withTimeout(15_000) { ProviderHub.state.first { !it.loading } }
            // Hub retires the old manager off-thread. This fixture contains no live runtime or sockets.
            root.deleteRecursively()
        }
    }
}
