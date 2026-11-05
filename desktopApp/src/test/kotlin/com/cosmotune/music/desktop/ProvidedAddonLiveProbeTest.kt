package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional validation of the user's previously supplied HTTP add-on. No access URL is retained in results. */
class ProvidedAddonLiveProbeTest {
    @Test(timeout = 180_000) fun providedAddonCanMatchResolveAndDecode() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_PROVIDED_ADDON_PROBE") == "1")
        val source = File("../build/upstream/addon-review/fetch_manifest.py")
        assumeTrue(source.isFile)
        val address = Regex("https://soundfean-music\\.vercel\\.app/[^\\s\"']+").find(source.readText())?.value
        assumeTrue(address != null)
        val base = requireNotNull(address).removeSuffix("/manifest.json").trimEnd('/')
        val config = DesktopSourceConfig("provided-addon-probe", DesktopSourceKind.ADDON, "Provided Qobuz add-on", base)
        val rows = mutableListOf<JsonObject>()
        val targets = listOf(Song("probe-global", "Blinding Lights", "The Weeknd", null, durationText = "3:20", albumName = "After Hours"),
            Song("probe-regional", "Tippulu Tappulu", "Shreya Ghoshal", null, durationText = "4:22", albumName = "Godavari"))
        for (target in targets) {
            var phase = "search"
            rows += buildJsonObject {
                put("song", target.title)
                try {
                    val matches = withTimeout(25_000) { DesktopAddonSource.matches(config, target) }
                    put("matchingRecordings", matches.size)
                    val match = matches.firstOrNull() ?: run { put("status", "NO_MATCH"); return@buildJsonObject }
                    phase = "stream"
                    val stream = withTimeout(30_000) { DesktopAddonSource.stream(config, match, "LOSSLESS").getOrThrow() }
                        ?: error("No stream")
                    put("offeredCodec", stream.format.codec); put("offeredDepth", stream.format.bitDepth); put("offeredRate", stream.format.sampleRateHz)
                    phase = "decode"
                    val decoder = DesktopAudioDecoder()
                    try {
                        decoder.open(stream.url, stream.headers, DesktopPcmFormat(44_100, 2, 4, true), transport = stream.transport).getOrThrow()
                        val pcm = decoder.read()
                        check(pcm != null && pcm.isNotEmpty())
                        put("decodedBytes", pcm.size); put("receivedCodec", decoder.measuredFormat?.codec)
                        put("receivedRate", decoder.measuredFormat?.sampleRateHz); put("receivedDepth", decoder.measuredFormat?.bitDepth)
                        put("status", "DECODED_AUDIO")
                    } finally { decoder.close() }
                } catch (error: Exception) {
                    put("status", "UNAVAILABLE"); put("failedPhase", phase); put("errorType", error.javaClass.simpleName)
                }
            }
        }
        val output = File("../build/provider-live-probe-2026-10-26/provided-addon-results.json").apply { parentFile.mkdirs() }
        output.writeText(buildJsonObject { put("scope", "Previously supplied add-on; no access address or account data in this report"); put("songs", JsonArray(rows)) }.toString())
    }
}
