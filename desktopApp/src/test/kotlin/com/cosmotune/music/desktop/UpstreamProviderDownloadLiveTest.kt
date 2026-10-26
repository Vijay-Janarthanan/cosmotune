package com.cosmotune.music.desktop

import com.cosmotune.providers.ProviderStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in comparison only. An existing user-verified isolated session is piped to the upstream host. */
class UpstreamProviderDownloadLiveTest {
    @Test(timeout = 260_000) fun theUnmodifiedLokalHostCanAttemptTheSameVerifiedQobuzDownload() {
        assumeTrue(System.getenv("COSMOTUNE_UPSTREAM_DOWNLOAD") == "1")
        val build = File("../build").canonicalFile
        val store = ProviderStore(File(build, "provider-embedded-browser-proof/profile"))
        val installed = requireNotNull(store.state.value.installed.find { it.manifest.name == "qobuz-web" })
        val archive = requireNotNull(File(store.root, "packages").listFiles()?.find { it.extension == "sflx" })
        val sources = java.util.zip.ZipFile(archive).use { zip ->
            listOf("manifest.json", "index.js").associateWith { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() } }
        }
        val manifest = Json.parseToJsonElement(sources.getValue("manifest.json")).jsonObject
        val session = requireNotNull(store.state.value.auth["qobuz-web"]?.get("signedSession") as? JsonObject)
        val settings = installed.manifest.effectiveSettings(store.state.value.settings["qobuz-web"].orEmpty())
        val payload = buildJsonObject {
            put("manifest", manifest); put("code", sources.getValue("index.js")); put("settings", settings)
            put("record", JsonObject(session.filterKeys { it in setOf("install_id", "session_id", "session_secret", "expires_at") }))
        }
        val process = ProcessBuilder("C:/Program Files/nodejs/node.exe", File(build, "provider-upstream-comparison/probe-lokal.cjs").absolutePath)
            .directory(build.parentFile).apply { environment()["NODE_PATH"] = File(build, "tooling/lokal-provider-probe/node_modules").absolutePath }.start()
        val errors = Thread { process.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }.apply { isDaemon = true; start() }
        try {
            process.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            val response = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0)
            val report = Json.parseToJsonElement(response).jsonObject
            File(build, "provider-upstream-comparison/result.json").writeText(report.toString())
            check(report["sessionAuthenticated"] == JsonPrimitive(true)) { "Comparison did not use an authenticated session" }
        } finally { process.destroyForcibly(); errors.join(1_000) }
    }
}
