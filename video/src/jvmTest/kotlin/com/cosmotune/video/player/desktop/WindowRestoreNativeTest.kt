package com.cosmotune.video.player.desktop

import com.cosmotune.video.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Opt-in native/network accounting with locally generated 24 fps video, no listener credentials. */
class WindowRestoreNativeTest {
    private fun until(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $label" }
            Thread.sleep(10)
        }
    }

    @Test fun hidingAndRestoringTheSurfaceKeepsTheTimelineAndReportsNetworkReuse() {
        val folder = File("../build/video-playback-review")
        val videoFile = File(folder, "video24.mp4")
        val audioFile = File(folder, "audio24.m4a")
        assumeTrue("Local generated media required", videoFile.isFile && audioFile.isFile)
        val mpv = testMpv()
        assumeTrue("libmpv required", mpv != null)
        val media = mapOf("/video" to videoFile.readBytes(), "/audio" to audioFile.readBytes())
        val requests = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                val bytes = media[path] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.removePrefix("bytes=")
                val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
                val end = minOf(range?.substringAfter('-')?.toIntOrNull() ?: bytes.lastIndex, bytes.lastIndex)
                if (start > end) return MockResponse().setResponseCode(416)
                requests += "$path:$start-$end"
                return MockResponse().setResponseCode(if (range != null) 206 else 200)
                    .setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setBody(Buffer().write(bytes, start, end - start + 1))
            }
        }
        server.start()
        val proxy = LoopbackProxy()
        val snapshots = MutableStateFlow(PlayerSnapshot.Idle)
        val exchange = FrameExchange()
        val player = MpvPlayer(mpv!!, snapshots, MutableStateFlow(emptyList()), MpvPlayer.DEFAULT_OPTIONS + ("ao" to "null"), latestLoad = { 1 }, onVideoSize = exchange::setVideoSize)
        val renderer = MpvRenderer(mpv, player.handle, exchange)
        try {
            assertTrue(renderer.start())
            val key = Any()
            exchange.request(key, 640, 360, VideoScale.FIT)
            val url = Edl.join(proxy.register(server.url("/video").toString(), emptyMap(), videoFile.length()),
                proxy.register(server.url("/audio").toString(), emptyMap(), audioFile.length()))
            player.load(1, url, 3_000, false, true, false, null, emptyList())
            until("ready") { snapshots.value.status == PlayerStatus.READY }
            player.set("pause", "no")
            until("initial motion") { player.positionMs() > 4_000 && exchange.frames.value > 12 }
            val before = player.positionMs()
            val beforeFrames = exchange.frames.value
            val beforeRequests = requests.size
            player.set("vid", "no")
            exchange.forget(key)
            Thread.sleep(700)
            player.set("vid", "auto")
            exchange.request(key, 640, 360, VideoScale.FIT)
            until("restored frames") { exchange.frames.value > beforeFrames + 6 && snapshots.value.playing }
            val after = player.positionMs()
            val repeatedRanges = requests.groupingBy { it }.eachCount().filterValues { it > 1 }
            val report = "before_ms=$before\nafter_ms=$after\nrequests_before=$beforeRequests\nrequests_after=${requests.size}\nrepeated_ranges=$repeatedRanges\nframes_after=${exchange.frames.value}\ndrops=${snapshots.value.droppedFrames}\n"
            File(folder, "native-restore-report.txt").writeText(report)
            println(report)
            assertTrue(after >= before - 250, "Restoring must not restart playback: $before -> $after")
            assertTrue(repeatedRanges.isEmpty(), "Restoring must reuse complete buffered ranges rather than download them again: $repeatedRanges")
        } finally { renderer.close(); player.close(); proxy.close(); server.shutdown() }
    }
}
