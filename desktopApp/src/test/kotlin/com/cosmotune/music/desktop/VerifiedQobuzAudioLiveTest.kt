package com.cosmotune.music.desktop

import com.cosmotune.providers.*
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.music.data.sources.SourceTrackKeys
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reuses only the isolated session from the user-operated native-window proof. */
class VerifiedQobuzAudioLiveTest {
    @Test(timeout = 180_000) fun verifiedPackageCanBeCheckedForActualLosslessAudio() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_VERIFIED_QOBUZ_AUDIO") == "1")
        ProviderMediaTools.installed = DesktopProviderMediaTools
        val root = File("../build/provider-embedded-browser-proof").canonicalFile
        val result = mutableMapOf<String, JsonElement>("provider" to JsonPrimitive("qobuz-web"))
        val requests = java.util.Collections.synchronizedList(mutableListOf<JsonElement>())
        val client = com.cosmotune.music.data.Http.client.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.host == "api.zarz.moe" && requests.size < 80) requests.add(buildJsonObject {
                put("path", chain.request().url.encodedPath); put("status", response.code)
                put("browserChallenge", response.header("cf-mitigated") == "challenge")
            })
            response
        }.build()
        ProviderManager(ProviderStore(File(root, "profile")), httpClient = client).use { manager ->
            val provider = "qobuz-web"
            try {
                val target = TrackMatcher.Target("Blinding Lights", "The Weeknd", 200, "After Hours")
                val tracks = withTimeout(20_000) { manager.search(provider, "${target.title} ${target.artist}", 20) }
                result["searchResults"] = JsonPrimitive(tracks.size)
                val matches = TrackMatcher.ranked(tracks.map { it.song() }, target).take(3).map { song ->
                    if (song.durationText != null) song else SourceTrackKeys.parse(song.videoId)?.second?.let { manager.track(provider, it)?.song() } ?: song
                }
                val match = requireNotNull(matches.firstOrNull { TrackMatcher.withinSeconds(it, target, 3) && TrackMatcher.sharesArtist(target.artist, it.artist) })
                val stream = withTimeout(125_000) { manager.stream(provider, requireNotNull(SourceTrackKeys.parse(match.videoId)).second, lossless = true) }
                withContext(Dispatchers.IO) {
                    val decoder = DesktopAudioDecoder()
                    try {
                        decoder.open(stream.url, requested = DesktopPcmFormat(44_100, 2, 4, true)).getOrThrow()
                        check(decoder.read()?.isNotEmpty() == true)
                        decoder.measuredFormat?.let { format ->
                            format.codec?.let { result["receivedCodec"] = JsonPrimitive(it) }
                            format.sampleRateHz?.let { result["receivedRate"] = JsonPrimitive(it) }
                            format.bitDepth?.let { result["receivedDepth"] = JsonPrimitive(it) }
                        }
                    } finally { decoder.close() }
                }
                result["status"] = JsonPrimitive("DECODED_AUDIO")
            } catch (error: Exception) {
                result["status"] = JsonPrimitive("UNAVAILABLE")
                result["errorType"] = JsonPrimitive(error.javaClass.simpleName)
                if (error is org.graalvm.polyglot.PolyglotException) {
                    result["runtimeCancelled"] = JsonPrimitive(error.isCancelled)
                    result["runtimeLimitExceeded"] = JsonPrimitive(error.isResourceExhausted)
                    result["runtimeHostFailure"] = JsonPrimitive(error.isHostException)
                }
                if (error is ProviderResolutionException) {
                    error.hostApi?.let { result["failedHostApi"] = JsonPrimitive(it) }
                    error.hostFailureKind?.let { result["hostFailureKind"] = JsonPrimitive(it) }
                    error.errorType?.let { result["providerErrorType"] = JsonPrimitive(it) }
                    error.httpStatus?.let { result["providerHttpStatus"] = JsonPrimitive(it) }
                }
            }
        }
        result["gatewayRequests"] = JsonArray(requests.toList())
        File(root, "audio-result.json").writeText(JsonObject(result).toString())
    }
}
