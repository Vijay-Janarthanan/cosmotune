package com.cosmotune.music.data.recognition

import com.cosmotune.music.data.Http
import com.cosmotune.music.data.NetworkPolicy
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.settings.Extras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * "What's playing?": ten seconds from the microphone, a Shazam signature made on the device ([ShazamSignatureGenerator]),
 * and that signature sent to **Shazam's unofficial endpoint** (`amp.shazam.com`, the one its Android app uses), the way Mochy
 * Music's `providers/shazamkit` does (GPL-3.0). Only the signature is sent, never the recording; the location in the request
 * is random, as Mochy sends it. Off until the listener turns it on in Extras, and refused while offline mode is on.
 *
 * A match is then looked up on YouTube Music through [TrackMatcher], the same exact-recording judgement imports use, so
 * "Play" plays that recording or nothing.
 */
object SongRecognition {

    data class Match(
        val title: String,
        val artist: String,
        val album: String?,
        val isrc: String?,
        val coverUrl: String?,
        val shazamUrl: String?,
        val youtubeVideoId: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object Asking : State
        data class Found(val match: Match, val song: Song?) : State
        data object NoMatch : State
        data class Failed(val reason: String) : State
    }

    /** Mono 16-bit PCM from the microphone. */
    class Recording(val samples: ShortArray, val sampleRate: Int)

    /** Records [millis] of the microphone, or null when it cannot (no permission, no device). Installed by each app. */
    fun interface Microphone {
        suspend fun record(millis: Long): Recording?
    }

    @Volatile var microphone: Microphone? = null

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    fun reset() {
        stateFlow.value = State.Idle
    }

    suspend fun identify(): State {
        val result = runCatching { identifyOnce() }.getOrElse { State.Failed(it.message ?: "Recognition failed") }
        stateFlow.value = result
        return result
    }

    private suspend fun identifyOnce(): State {
        if (!Extras.settings.songRecognition.value) return State.Failed("Song recognition is off.")
        if (NetworkPolicy.offline) return State.Failed("Offline mode is on.")
        val mic = microphone ?: return State.Failed("This device has no microphone the app can use.")
        stateFlow.value = State.Listening
        val recording = mic.record(RECORD_MS) ?: return State.Failed("The microphone could not be opened.")
        stateFlow.value = State.Asking
        val pcm = resampleTo16k(recording.samples, recording.sampleRate)
        if (pcm.size < ShazamSignatureGenerator.SAMPLE_RATE_HZ * 3) return State.Failed("Too little was recorded.")
        val signature = ShazamSignatureGenerator.fromPcm16k(pcm)
        val durationMs = pcm.size * 1_000L / ShazamSignatureGenerator.SAMPLE_RATE_HZ
        val body = withContext(Dispatchers.IO) { ask(signature, durationMs) } ?: return State.NoMatch
        val match = parse(body) ?: return State.NoMatch
        val song = runCatching { findOnYouTubeMusic(match) }.getOrNull()
        return State.Found(match, song)
    }

    private fun ask(signature: String, durationMs: Long): String? {
        val now = System.currentTimeMillis()
        val payload = buildJsonObject {
            putJsonObject("geolocation") {
                put("altitude", Random.nextDouble() * 400 + 100)
                put("latitude", Random.nextDouble() * 180 - 90)
                put("longitude", Random.nextDouble() * 360 - 180)
            }
            putJsonObject("signature") {
                put("samplems", durationMs)
                put("timestamp", now / 1000)
                put("uri", signature)
            }
            put("timestamp", now / 1000)
            put("timezone", TimeZone.getDefault().id)
        }.toString()
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}"
            .toHttpUrl().newBuilder()
            .addQueryParameter("sync", "true")
            .addQueryParameter("webv3", "true")
            .addQueryParameter("sampling", "true")
            .addQueryParameter("connected", "")
            .addQueryParameter("shazamapiversion", "v3")
            .addQueryParameter("sharehub", "true")
            .addQueryParameter("video", "v3")
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001)")
            .header("Content-Language", "en_US")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                response.code == 429 -> error("Shazam is limiting requests; try again in a minute.")
                !response.isSuccessful -> error("Shazam answered ${response.code}.")
                else -> response.body?.string()
            }
        }
    }

    /** The track in a Shazam answer, or null when it matched nothing. */
    fun parse(body: String): Match? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val track = root["track"] as? JsonObject ?: return null
        val title = track.string("title")?.takeIf { it.isNotBlank() } ?: return null
        val sections = (track["sections"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val metadata = sections.firstOrNull { it.string("type") == "SONG" }?.get("metadata") as? JsonArray
        val album = metadata.orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.string("title") == "Album" }?.string("text")
        val images = track["images"] as? JsonObject
        val video = ((track["hub"] as? JsonObject)?.get("options") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("type")?.contains("video", ignoreCase = true) == true }
            ?.let { (it["actions"] as? JsonArray)?.firstOrNull() as? JsonObject }?.string("uri")
        val videoId = video?.let { uri ->
            uri.substringAfterLast("v=", "").takeIf { it.length == 11 } ?: uri.substringAfterLast('/').takeIf { it.length == 11 }
        }
        return Match(
            title = title,
            artist = track.string("subtitle").orEmpty(),
            album = album,
            isrc = track.string("isrc"),
            coverUrl = images?.string("coverarthq") ?: images?.string("coverart"),
            shazamUrl = track.string("url"),
            youtubeVideoId = videoId,
        )
    }

    /** The YouTube Music recording that is [match], judged by [TrackMatcher]; null when none is clearly it. */
    suspend fun findOnYouTubeMusic(match: Match): Song? {
        val query = listOf(match.title, match.artist).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            .orEmpty().filterIsInstance<SearchResult.Track>().map { it.song }
        return TrackMatcher.best(candidates, TrackMatcher.Target(title = match.title, artist = match.artist, album = match.album))
    }

    /** Mono 16-bit PCM at any rate as 16 kHz, low-passed first so nothing above 8 kHz folds down. */
    fun resampleTo16k(samples: ShortArray, sampleRate: Int): ShortArray {
        val target = ShazamSignatureGenerator.SAMPLE_RATE_HZ
        if (sampleRate == target) return samples
        val step = sampleRate.toDouble() / target
        val filtered = if (sampleRate > target) lowPass(samples, sampleRate, cutoffHz = 7_000.0) else FloatArray(samples.size) { samples[it].toFloat() }
        val out = ShortArray(((samples.size - 1) / step).toInt().coerceAtLeast(0))
        for (i in out.indices) {
            val at = i * step
            val index = at.toInt()
            val t = (at - index).toFloat()
            val a = filtered[index]
            val b = filtered[(index + 1).coerceAtMost(filtered.lastIndex)]
            out[i] = (a + (b - a) * t).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** A 4th-order Butterworth low-pass: two biquads. */
    private fun lowPass(samples: ShortArray, rate: Int, cutoffHz: Double): FloatArray {
        val out = FloatArray(samples.size) { samples[it].toFloat() }
        for (q in doubleArrayOf(0.5412, 1.3066)) {
            val k = kotlin.math.tan(Math.PI * cutoffHz / rate)
            val norm = 1.0 / (1.0 + k / q + k * k)
            val b0 = (k * k * norm).toFloat()
            val b1 = 2f * b0
            val a1 = (2.0 * (k * k - 1.0) * norm).toFloat()
            val a2 = ((1.0 - k / q + k * k) * norm).toFloat()
            var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
            for (i in out.indices) {
                val x = out[i]
                val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x; y2 = y1; y1 = y
                out[i] = y
            }
        }
        return out
    }

    private val client by lazy { Http.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Shazam's own app listens for about this long. */
    const val RECORD_MS = 10_000L
}
