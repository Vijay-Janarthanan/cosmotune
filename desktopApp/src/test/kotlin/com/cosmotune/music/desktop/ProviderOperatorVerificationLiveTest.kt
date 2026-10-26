package com.cosmotune.music.desktop

import com.cosmotune.music.data.sources.SourceTrackKeys
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.providers.ProviderManager
import com.cosmotune.providers.ProviderStore
import com.cosmotune.providers.ProviderVerificationException
import com.cosmotune.providers.ProviderResolutionException
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in human verification. No browser challenge is solved, skipped or bypassed by this test. */
class ProviderOperatorVerificationLiveTest {
    @Test(timeout = 270_000) fun aHumanVerifiedAmazonSessionCanBeCheckedForActualAudio() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_OPERATOR_VERIFY_AMAZON") == "1")
        com.cosmotune.providers.ProviderMediaTools.installed = DesktopProviderMediaTools
        val build = File("../build").canonicalFile
        val root = File(build, "operator-verification-amazon").apply { mkdirs() }
        val handoff = File(root, "pending-url.txt").also { it.delete() }
        val report = File(root, "result.json")
        val result = mutableMapOf<String, JsonElement>("scope" to JsonPrimitive("Isolated Amazon provider; human browser verification and bounded native decode"))
        fun checkpoint(status: String) { result["status"] = JsonPrimitive(status); report.writeText(JsonObject(result).toString()) }
        val archive = requireNotNull(File(build, "provider-live-probe-2026-10-26/profiles/amazon/packages")
            .listFiles()?.firstOrNull { it.extension == "sflx" })
        val httpClient = com.cosmotune.music.data.Http.client.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.host == "api.zarz.moe" && response.code >= 400) {
                val body = runCatching { Json.parseToJsonElement(response.peekBody(32_768).string()).jsonObject }.getOrNull()
                result["gatewayFailurePath"] = JsonPrimitive(chain.request().url.encodedPath)
                result["gatewayFailureStatus"] = JsonPrimitive(response.code)
                result["gatewayFailureFields"] = JsonArray(body?.keys.orEmpty().take(20).map(::JsonPrimitive))
                result["gatewayFailureIsJson"] = JsonPrimitive(body != null)
                result["gatewayBrowserChallenge"] = JsonPrimitive(response.header("cf-mitigated") == "challenge")
                for (key in listOf("code", "origin", "action")) {
                    (body?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
                        ?.let { result["gatewayFailure_$key"] = JsonPrimitive(it) }
                }
                val detail = listOf("error", "message").mapNotNull { (body?.get(it) as? JsonPrimitive)?.contentOrNull }.joinToString(" ").lowercase()
                result["gatewayFailureTerms"] = JsonArray(listOf("capability", "signature", "session", "provider", "denied", "not allowed", "expired", "unavailable", "ticket")
                    .filter { it in detail }.map(::JsonPrimitive))
            }
            if (chain.request().url.encodedPath.endsWith("/session/exchange")) {
                val body = runCatching { Json.parseToJsonElement(response.peekBody(32_768).string()).jsonObject }.getOrNull()
                result["exchangeHttpStatus"] = JsonPrimitive(response.code)
                result["exchangeFields"] = JsonArray(body?.keys.orEmpty().take(20).map(::JsonPrimitive))
                val expiry = (body?.get("expires_at") as? JsonPrimitive)?.contentOrNull
                val parsed = runCatching { java.time.Instant.parse(expiry).toEpochMilli() }.getOrNull()
                result["exchangeExpiryParseable"] = JsonPrimitive(parsed != null)
                parsed?.let { result["exchangeExpiryFuture"] = JsonPrimitive(it > System.currentTimeMillis()) }
            }
            response
        }.build()
        ProviderManager(ProviderStore(File(root, "profile")), httpClient = httpClient).use { manager ->
            try {
                manager.trust(manager.inspect { archive.inputStream() })
                if (System.getenv("COSMOTUNE_OPERATOR_FRESH_VERIFICATION") == "1") manager.clearAuthentication("amazon")
                val manual = File(root, "manual-grant.txt")
                if (manual.isFile) {
                    val grant = manual.readText().trim()
                    manual.delete()
                    withTimeout(25_000) { manager.completeAuthentication("amazon", grant) }
                }
                if (!withTimeout(25_000) { manager.verifyProviderAccess("amazon") }) {
                    manager.prepareBrowserAuthentication("amazon").use { browser ->
                        check(browser.automaticCallback)
                        handoff.writeText(browser.url)
                        checkpoint("WAITING_FOR_HUMAN_VERIFICATION")
                        coroutineScope {
                            val manualEntry = launch(Dispatchers.IO) {
                                while (isActive) {
                                    if (manual.isFile) {
                                        val grant = manual.readText().trim(); manual.delete()
                                        try { withTimeout(25_000) { manager.completeAuthentication("amazon", grant) } }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: ProviderVerificationException) {
                                            synchronized(result) {
                                                result["operatorHttpStatus"] = JsonPrimitive(error.httpStatus)
                                                error.operatorCode?.let { result["operatorCode"] = JsonPrimitive(it) }
                                                checkpoint("MANUAL_GRANT_REJECTED")
                                            }
                                        }
                                    }
                                    delay(250)
                                }
                            }
                            try { withTimeout(170_000) { manager.store.state.first { state ->
                                val auth = state.auth["amazon"]
                                auth?.get("pending") == null &&
                                    ((auth?.get("signedSession") as? JsonObject)?.get("session_secret") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
                            } } } finally { manualEntry.cancelAndJoin() }
                        }
                    }
                }
                checkpoint("SESSION_VERIFIED")
                val target = TrackMatcher.Target("Blinding Lights", "The Weeknd", 200, "After Hours")
                val tracks = withTimeout(25_000) { manager.search("amazon", "${target.title} ${target.artist}", 20) }
                val candidates = TrackMatcher.ranked(tracks.map { it.song() }, target).take(3).map { song ->
                    if (song.durationText != null) song else SourceTrackKeys.parse(song.videoId)?.second?.let { id ->
                        withTimeout(15_000) { manager.track("amazon", id)?.song() }
                    } ?: song
                }
                val match = requireNotNull(candidates.firstOrNull { TrackMatcher.withinSeconds(it, target, 3) && TrackMatcher.sharesArtist(target.artist, it.artist) })
                result["song"] = JsonPrimitive(match.title)
                val stream = withTimeout(125_000) { manager.stream("amazon", requireNotNull(SourceTrackKeys.parse(match.videoId)).second, lossless = true) }
                checkpoint("STREAM_RESOLVED")
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
                checkpoint("DECODED_AUDIO")
            } catch (error: Exception) {
                result["errorType"] = JsonPrimitive(error.javaClass.simpleName)
                result["failureAt"] = JsonArray(error.stackTrace.filter { it.className.startsWith("com.cosmotune.") }.take(5)
                    .map { JsonPrimitive("${it.className}.${it.methodName}:${it.lineNumber}") })
                if (error is ProviderResolutionException) {
                    error.errorType?.let { result["providerErrorType"] = JsonPrimitive(it) }
                    error.httpStatus?.let { result["providerHttpStatus"] = JsonPrimitive(it) }
                }
                if (error is ProviderVerificationException) {
                    result["operatorHttpStatus"] = JsonPrimitive(error.httpStatus)
                    error.operatorCode?.let { result["operatorCode"] = JsonPrimitive(it) }
                }
                checkpoint(if (error is TimeoutCancellationException) "VERIFICATION_OR_REQUEST_TIMED_OUT" else "ACCESS_OR_AUDIO_REQUEST_FAILED")
            } finally { handoff.delete() }
        }
    }
}
