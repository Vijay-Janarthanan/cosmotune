package com.cosmotune.music.desktop

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.ProgressiveRangeSource
import com.opencanvas.core.stream.InnerTubeBackend
import com.opencanvas.core.sync.SyncAligner
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Anonymous opt-in diagnostic; never reads or changes the installed app profile. */
class CurrentSongVideoProbeTest {
    @Test(timeout = 180_000) fun exactAadungadaWaveformCanBeCompared() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_CURRENT_SONG_PCM_PROBE") == "1")
        check(com.cosmotune.music.data.innertube.Innertube.cookie == null)
        val backend = InnerTubeBackend.android()
        fun envelope(url: String, headers: Map<String, String>): FloatArray {
            val decoder = DesktopAudioDecoder()
            try {
                decoder.open(url, headers, DesktopPcmFormat(8_000, 1, 4, true), windowed = true).getOrThrow()
                val binSamples = decoder.outputFormat.sampleRate / 50
                val bins = ArrayList<Double>()
                var sum = 0.0; var count = 0
                while (bins.size < 50 * 600) {
                    val samples = decoder.readSamples() ?: break
                    for (index in 0 until decoder.sampleCount) {
                        val sample = samples[index]
                        sum += sample * sample; count++
                        if (count == binSamples) { bins += kotlin.math.ln(1e-8 + sum / count); count = 0; sum = 0.0 }
                    }
                }
                val prefix = DoubleArray(bins.size + 1)
                for (i in bins.indices) prefix[i + 1] = prefix[i] + bins[i]
                return FloatArray(bins.size) { i ->
                    val start = (i - 75).coerceAtLeast(0); val end = (i + 76).coerceAtMost(bins.size)
                    (bins[i] - (prefix[end] - prefix[start]) / (end - start)).toFloat()
                }
            } finally { decoder.close() }
        }
        val song = requireNotNull(backend.resolveAudio("1KM8Nxma9sk"))
        val video = requireNotNull(backend.resolveAudio("Uz9ivzBQnBo"))
        val a = envelope(song.url, song.headers); val b = envelope(video.url, video.headers)
        check(a.size in 50 * 240..50 * 300 && b.size in 50 * 240..50 * 320) { "unexpected decoded duration" }
        val match = SyncAligner.align(a, b, 50)
        File("../build/current-song-video/pcm-result.json").writeText(buildJsonObject {
            put("confidence", match.confidence); put("coverage", match.coverage); put("offsetMs", match.offsetMs)
            put("songSeconds", a.size / 50.0); put("videoSeconds", b.size / 50.0)
            put("error", match.error); put("segments", JsonArray(match.segments.map { buildJsonObject {
                put("songStartMs", it.songStartMs); put("songEndMs", it.songEndMs); put("offsetMs", it.offsetMs); put("ncc", it.ncc)
            } }))
        }.toString())
    }
    @Test(timeout = 480_000) fun theReportedSongsAndTheirExactVideosCanBeChecked() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_CURRENT_SONG_VIDEO_PROBE") == "1")
        check(com.cosmotune.music.data.innertube.Innertube.cookie == null)
        val root = File("../build/current-song-video").canonicalFile.apply { mkdirs() }
        val logs = File(root, "stages.log").apply { writeText("") }
        OpenCanvas.configure(OpenCanvasConfig(cacheDirectory = File(root, "cache"), log = {
            logs.appendText(it.replace(Regex("https?://\\S+"), "[url]") + "\n")
        }))
        val reports = mutableListOf<JsonObject>()
        val songs = listOf(
            listOf("Idhazhin Oram (The Innocence of Love)", "Anirudh Ravichander, Ajesh & Aishwarya R Dhanush", "3 (Original Motion Picture Soundtrack)", "JzU99mOHP0A", "207", "lZORMUufA_Y"),
            listOf("Aadungada Enna Suthi", "Mani Sharma, Naveen & Kabilan", "Pokkiri", "1KM8Nxma9sk", "267", "Uz9ivzBQnBo"),
        )
        for (song in songs) for (direct in listOf(false, true)) {
            val report = buildJsonObject {
                put("song", song[0]); put("lookup", if (direct) "EXACT_USER_VIDEO" else "AUTOMATIC")
                val track = withTimeoutOrNull(95_000) {
                    OpenCanvas.resolve(song[0], song[1], song[4].toLong(),
                        mode = OpenCanvasMode.FULL_SYNCED_VIDEO, resolution = OpenCanvasResolution.STANDARD_480P,
                        trackVideoId = song[3], videoId = song[5].takeIf { direct }, album = song[2])
                }
                if (track == null) put("status", "NO_RESULT") else {
                    put("videoId", track.videoId); put("syncStage", track.syncStage.name)
                    if (track.syncStage == SyncStage.MEASURED) {
                        ProgressiveRangeSource(track.videoStreamUrl, OkHttpClient(), track.videoHeaders).use { source ->
                            val decoder = DesktopCanvasDecoder()
                            try {
                                decoder.open(source).getOrThrow()
                                val pixels = ByteArray(decoder.width * decoder.height * 4)
                                put("decodedAt45s", decoder.seekToMs(45_000) && decoder.nextFrame(pixels, loop = false))
                                put("framePtsMs", decoder.lastPtsMs)
                            } finally { decoder.close() }
                        }
                    }
                }
            }
            reports += report
            File(root, "result.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(reports)))
        }
    }
}
