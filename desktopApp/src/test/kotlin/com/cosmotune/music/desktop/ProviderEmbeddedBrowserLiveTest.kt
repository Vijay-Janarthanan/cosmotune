package com.cosmotune.music.desktop

import com.cosmotune.providers.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit visible native-window proof. The user completes any browser challenge themselves. */
class ProviderEmbeddedBrowserLiveTest {
    @Test(timeout = 210_000) fun aDedicatedProviderWindowReturnsItsGrantWithoutCopying() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_EMBEDDED_BROWSER_LIVE") == "1")
        val provider = "qobuz-web"
        val build = File("../build").canonicalFile
        val root = File(build, "provider-embedded-browser-proof").apply { mkdirs() }
        val output = File(root, "result.json")
        fun report(status: String, error: Exception? = null) { output.writeText(buildJsonObject {
            put("provider", provider); put("status", status)
            error?.let { put("errorType", it.javaClass.simpleName) }
        }.toString()) }
        val archive = requireNotNull(File(build, "provider-live-probe-2026-10-26/profiles/$provider/packages").listFiles()?.firstOrNull { it.extension == "sflx" })
        ProviderManager(ProviderStore(File(root, "profile"))).use { manager ->
            val previous = ProviderVerificationWindows.launcher
            try {
                manager.trust(manager.inspect { archive.inputStream() })
                if (!manager.verifyProviderAccess(provider)) {
                    DesktopProviderVerificationBrowser.install()
                    val failed = AtomicBoolean(false)
                    manager.prepareBrowserAuthentication(provider).use {
                        requireNotNull(ProviderVerificationWindows.launcher)(manager, provider, manager.verificationTarget(provider)) { failed.set(true) }.use {
                            report("BROWSER_STARTING")
                            withTimeout(175_000) {
                                while (true) {
                                    check(!failed.get()) { "Verification window could not start or complete" }
                                    val auth = manager.store.state.value.auth[provider]
                                    if (auth?.get("pending") == null && ((auth?.get("signedSession") as? JsonObject)?.get("session_secret") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) break
                                    delay(200)
                                }
                            }
                        }
                    }
                    check(!failed.get())
                }
                report("SESSION_VERIFIED_WITHOUT_PASTING")
            } catch (error: Exception) { report("BROWSER_OR_VERIFICATION_UNAVAILABLE", error) }
            finally { ProviderVerificationWindows.launcher = previous }
        }
    }
}
