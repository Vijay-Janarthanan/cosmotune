package com.cosmotune.music.desktop

import com.cosmotune.music.data.sources.*
import com.cosmotune.providers.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opted-in public-source probe. Never loads the user's source/account profile. */
class ProviderResolutionLiveProbeTest {
    @Test(timeout = 1_200_000) fun publicPackagesCanResolveAndDecodeRealRecordings() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_PROVIDER_LIVE_PROBE") == "1")
        val root = File(requireNotNull(System.getenv("COSMOTUNE_PROVIDER_PROBE_DIR"))).canonicalFile
        require(root.toPath().startsWith(File("..").canonicalFile.toPath().resolve("build")))
        root.mkdirs()
        val results = mutableListOf<JsonObject>()
        fun checkpoint() {
            File(root, "results.json").writeText(buildJsonObject {
                put("scope", "Isolated public packages; no user accounts or profile loaded")
                put("complete", false)
                put("providers", JsonArray(results.toList()))
            }.toString())
        }
        val registry = ProviderManager(ProviderStore(File(root, "registry"))).use { manager ->
            withTimeout(30_000) { manager.repositories.fetch("https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/registry.json") }
        }
        val targets = listOf(
            TrackMatcher.Target("Blinding Lights", "The Weeknd", 200, "After Hours"),
            TrackMatcher.Target("Tippulu Tappulu", "Shreya Ghoshal", 262, "Godavari"),
        )
        suspend fun probe(id: String, entry: ProviderRepositoryEntry?, local: File? = null) {
            var phase = "install"
            val samples = mutableListOf<JsonObject>()
            val result = buildJsonObject {
                put("provider", id)
                ProviderManager(ProviderStore(File(root, "profiles/$id"))).use { manager ->
                    try {
                        val review = withTimeout(30_000) {
                            if (entry != null) manager.repositories.download(entry) else manager.inspect { requireNotNull(local).inputStream() }
                        }
                        val install = manager.trust(review)
                        put("name", install.manifest.title)
                        put("types", JsonArray(install.manifest.type.map(::JsonPrimitive)))
                        put("declaredQualities", JsonArray(install.manifest.qualityOptions.map { JsonPrimitive(it.label.ifBlank { it.id }) }))
                        for (target in targets) {
                            phase = "search"
                            val row = buildJsonObject {
                                put("song", target.title)
                                val found = withTimeout(18_000) { manager.search(id, "${target.title} ${target.artist}", 20) }
                                put("searchResults", found.size)
                                put("sampleResults", JsonArray(found.take(3).map { track -> buildJsonObject {
                                    put("title", track.title.take(150)); put("artist", track.artist.take(150)); put("durationMs", track.durationMs)
                                } }))
                                val candidates = TrackMatcher.ranked(found.map { it.song() }, target).take(3).map { song ->
                                    if (song.durationText != null) song else {
                                        val trackId = SourceTrackKeys.parse(song.videoId)?.second
                                        trackId?.let { manager.track(id, it)?.song() } ?: song
                                    }
                                }
                                val ranked = candidates.filter { TrackMatcher.withinSeconds(it, target, 3) && TrackMatcher.sharesArtist(target.artist, it.artist) }
                                val match = ranked.firstOrNull()
                                if (match == null) { put("status", "NO_MATCH"); return@buildJsonObject }
                                put("matchedTitle", match.title.take(200)); put("matchedArtist", match.artist.take(200))
                                if ("download_provider" !in install.manifest.type) {
                                    put("status", "METADATA_MATCH_ONLY"); return@buildJsonObject
                                }
                                phase = "stream"
                                val track = requireNotNull(SourceTrackKeys.parse(match.videoId)).second
                                val stream = withTimeout(25_000) { manager.stream(id, track, lossless = true) }
                                put("offeredCodec", stream.codec); put("offeredKbps", stream.kbps)
                                put("offeredRate", stream.sampleRateHz); put("offeredDepth", stream.bitDepth)
                                phase = "decode"
                                withContext(Dispatchers.IO) {
                                    val decoder = DesktopAudioDecoder()
                                    try {
                                        decoder.open(stream.url, requested = DesktopPcmFormat(44_100, 2, 4, true)).getOrThrow()
                                        val pcm = decoder.read()
                                        check(pcm != null && pcm.isNotEmpty()) { "No decoded PCM" }
                                        put("decodedBytes", pcm.size)
                                        put("receivedCodec", decoder.measuredFormat?.codec)
                                        put("receivedRate", decoder.measuredFormat?.sampleRateHz)
                                        put("receivedDepth", decoder.measuredFormat?.bitDepth)
                                        put("receivedChannels", decoder.measuredFormat?.channels)
                                    } finally { decoder.close() }
                                }
                                put("status", "DECODED_AUDIO")
                            }
                            samples += row
                        }
                        put("status", "COMPLETED")
                    } catch (error: Exception) {
                        val ticket = AudioSourceComparisons.start(targets.first(), "probe-$id", id)
                        if (error is kotlinx.coroutines.TimeoutCancellationException) AudioSourceComparisons.finish(ticket, AudioSourceComparisons.Status.TIMEOUT)
                        else AudioSourceComparisons.failed(ticket, error)
                        val status = AudioSourceComparisons.state.value[AudioSourceComparisons.key(targets.first())]?.offers?.firstOrNull { it.id == "probe-$id" }?.status
                        put("status", status?.name ?: "UNAVAILABLE")
                        put("failedPhase", phase)
                        put("errorType", error.javaClass.simpleName)
                        put("diagnostic", error.message.orEmpty()
                            .replace(Regex("https?://[^\\s\"'<>]+"), "<address>")
                            .replace(Regex("(?i)(token|secret|password|authorization|key)\\s*[:=]\\s*[^\\s,;]+"), "<credential redacted>")
                            .replace(Regex("eyJ[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){1,2}"), "<token redacted>").take(500))
                    }
                }
                put("songs", JsonArray(samples))
            }
            results += result
            checkpoint()
        }
        val filter = System.getenv("COSMOTUNE_PROVIDER_PROBE_FILTER")?.split(',')?.toSet()
        for (entry in registry.extensions.filter { filter == null || it.identity in filter }) probe(entry.identity, entry)
        val yandex = File("../build/upstream/Lokal/addons/yandex-music.sflx")
        if (yandex.isFile && (filter == null || "yandex-music" in filter)) probe("yandex-music", null, yandex)
        File(root, "results.json").writeText(buildJsonObject {
            put("scope", "Isolated public packages; no user accounts or profile loaded")
            put("complete", true); put("providers", JsonArray(results))
        }.toString())
    }
}
