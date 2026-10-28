package com.cosmotune.music.desktop

import com.cosmotune.providers.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in diagnosis using a disposable copy of the installed encrypted profile, never the live store. */
class CurrentProviderPlaybackProbeTest {
    @Test(timeout = 360_000) fun installedProvidersCanBeDiagnosedWithoutChangingTheLiveProfile() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_CURRENT_PROVIDER_PROBE") == "1")
        val source = File(requireNotNull(System.getenv("APPDATA")), "Cosmotune/providers").canonicalFile
        val build = File("../build/upstream-audit-2026-10-28").canonicalFile.apply { mkdirs() }
        val profile = java.nio.file.Files.createTempDirectory(build.toPath(), "provider-profile-").toFile().canonicalFile
        check(profile.toPath().startsWith(build.toPath()))
        val reports = mutableListOf<JsonObject>()
        val network = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val client = com.cosmotune.music.data.Http.client.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // No query strings, headers, request/response bodies, or signed media addresses.
            if (response.code >= 400) network += buildJsonObject {
                put("host", chain.request().url.host)
                put("path", chain.request().url.encodedPath.replace(Regex("[A-Za-z0-9_-]{24,}"), "<id>"))
                put("status", response.code)
            }
            response
        }.build()
        ProviderMediaTools.installed = DesktopProviderMediaTools
        try {
            File(source, "providers.json").copyTo(File(profile, "providers.json"))
            File(source, "packages").copyRecursively(File(profile, "packages"))
            ProviderManager(ProviderStore(profile), httpClient = client).use { manager ->
                check(manager.store.recoveryMessage == null) { "Encrypted profile could not be opened" }
                val wanted = setOf("amazon", "youtube-music", "tidal")
                val installs = manager.store.state.value.installed.filter { it.enabled && (it.manifest.name in wanted ||
                    it.manifest.title.contains("YouTube", true) || it.manifest.title.contains("Tidal", true)) }
                for (install in installs) {
                    val provider = install.manifest.name
                    if (System.getenv("COSMOTUNE_PROVIDER_PROBE_LEGACY_ONLY") == "1" && provider == "amazon") continue
                    for (title in listOf("Ayyo Ayyo", "Aathi")) {
                        val report = buildJsonObject {
                            put("provider", provider); put("song", title); put("packageSha256", install.sha256)
                            try {
                                val tracks = withTimeout(20_000) { manager.search(provider, title, 15) }
                                put("results", tracks.size)
                                val sameTitle = tracks.filter { it.title.equals(title, true) }.take(2)
                                put("rows", JsonArray(sameTitle.map { track -> buildJsonObject {
                                    put("title", track.title); put("artist", track.artist); put("durationMs", track.durationMs)
                                } }))
                                val match = sameTitle.firstOrNull { track ->
                                    if (title == "Ayyo Ayyo") track.artist.contains("Srikanth", true) || track.artist.contains("Udit", true)
                                    else track.artist.contains("Anirudh", true)
                                }
                                if (match == null) put("status", "NO_MATCH") else {
                                    val stream = withTimeout(45_000) { manager.stream(provider, match.id, lossless = true) }
                                    put("offeredCodec", stream.codec); put("offeredDepth", stream.bitDepth)
                                    withContext(Dispatchers.IO) {
                                        val decoder = DesktopAudioDecoder()
                                        try {
                                            decoder.open(stream.url, requested = DesktopPcmFormat(48_000, 2, 4, true)).getOrThrow()
                                            check(decoder.read()?.isNotEmpty() == true)
                                            put("receivedCodec", decoder.measuredFormat?.codec)
                                            put("receivedDepth", decoder.measuredFormat?.bitDepth)
                                            put("status", "DECODED_AUDIO")
                                        } finally { decoder.close() }
                                    }
                                }
                            } catch (error: Exception) {
                                put("status", "FAILED"); put("type", error.javaClass.simpleName)
                                val safe = error.message.orEmpty().replace(Regex("https?://[^\\s\"'<>]+"), "<address>")
                                    .replace(Regex("(?i)(token|secret|password|authorization|grant|key)\\s*[:=]\\s*[^\\s,;]+"), "<credential>")
                                    .replace(Regex("eyJ[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){1,2}"), "<token>").take(250)
                                put("reason", safe)
                            }
                        }
                        reports += report
                        File(build, "current-provider-results.json").writeText(buildJsonObject {
                            put("results", JsonArray(reports)); put("httpFailures", JsonArray(network.toList()))
                        }.toString())
                    }
                }
                // Read the protected source list directly: sourceConfigs() also migrates saved settings.
                val persistence = DesktopPersistence()
                val protected = DesktopPreferenceChunks.read(persistence.preferences, "source_configs_dpapi_v1")
                val raw = protected?.let { DesktopWindowsCrypto.unprotect(java.util.Base64.getDecoder().decode(it)) }
                    ?.toString(Charsets.UTF_8)
                val configs = raw?.let { Json { ignoreUnknownKeys = true }.decodeFromString<List<DesktopSourceConfig>>(it) }.orEmpty()
                for (config in configs.filter { it.enabled && (it.displayName.contains("Tidal", true) || it.displayName.contains("YouTube Music", true)) &&
                    it.kind in setOf(DesktopSourceKind.MODULE, DesktopSourceKind.CUSTOM_MODULE) }) {
                    for (title in listOf("Ayyo Ayyo", "Aathi")) {
                        val row = buildJsonObject {
                            put("provider", config.displayName); put("song", title); put("adapter", "module")
                            try {
                                val song = com.cosmotune.music.data.model.Song("probe", title,
                                    if (title == "Ayyo Ayyo") "Srikanth Deva, Udit Narayanan & Shalini Singh" else "Anirudh Ravichander, Vishal Dadlani & Yugabharathi", null)
                                val match = withTimeout(20_000) { DesktopModuleSource.matches(config, song).firstOrNull() }
                                if (match == null) put("status", "NO_MATCH") else {
                                    val stream = withTimeout(45_000) { DesktopModuleSource.stream(config, match, "LOSSLESS").getOrThrow() }
                                    put("status", if (stream == null) "NO_STREAM" else "STREAM_RESOLVED")
                                    put("codec", stream?.format?.codec)
                                }
                            } catch (error: Exception) {
                                put("status", "FAILED"); put("type", error.javaClass.simpleName)
                                put("reason", error.message.orEmpty().replace(Regex("https?://[^\\s\"'<>]+"), "<address>")
                                    .replace(Regex("(?i)(token|secret|password|authorization|grant|key)\\s*[:=]\\s*[^\\s,;]+"), "<credential>").take(200))
                            }
                        }
                        reports += row
                        File(build, "current-provider-legacy-results.json").writeText(JsonArray(reports).toString())
                    }
                }
                val ayyo = com.cosmotune.music.data.model.Song("probe", "Ayyo Ayyo", "Srikanth Deva, Udit Narayanan & Shalini Singh", null, durationText = "5:47")
                val saavn = buildJsonObject {
                    put("provider", "JioSaavn"); put("song", ayyo.title)
                    val matches = withTimeout(20_000) { DesktopJioSaavn.matches(ayyo) }
                    put("matches", matches.size)
                    val stream = matches.firstOrNull()?.let { DesktopJioSaavn.stream(it.videoId).getOrThrow() }
                    if (stream == null) put("status", "NO_STREAM") else withContext(Dispatchers.IO) {
                        val decoder = DesktopAudioDecoder()
                        try {
                            decoder.open(stream.url, requested = DesktopPcmFormat(48_000, 2, 4, true)).getOrThrow()
                            check(decoder.read()?.isNotEmpty() == true)
                            put("status", "DECODED_AUDIO"); put("codec", decoder.measuredFormat?.codec)
                        } finally { decoder.close() }
                    }
                }
                reports += saavn
                File(build, "current-provider-legacy-results.json").writeText(JsonArray(reports).toString())
            }
        } finally {
            // Only this newly created directory is removed; the installed profile is untouched.
            check(profile.toPath().startsWith(build.toPath()) && profile.name.startsWith("provider-profile-"))
            profile.deleteRecursively()
        }
    }
}
