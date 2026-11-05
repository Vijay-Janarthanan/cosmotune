package com.cosmotune.desktop

import com.cosmotune.music.data.canvas.OpenCanvasProvider
import com.cosmotune.music.desktop.DesktopCanvasDecoder
import com.opencanvas.core.filter.*
import com.opencanvas.core.onnx.OnnxPeopleDetector
import com.opencanvas.core.reframer.SmartFrameReframer
import com.opencanvas.core.stream.ProgressiveRangeSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class CanvasFramingLiveTest {
    @Test fun measuredFilmVideoProducesActorAwareFrameComparisons() = runBlocking {
        assumeTrue(System.getenv("VIDEO_LIVE") == "true")
        OpenCanvasProvider.configure(null)
        val previousSink = com.cosmotune.music.data.DebugLog.sink
        com.cosmotune.music.data.DebugLog.sink = com.cosmotune.music.data.DebugLog.Sink { _, _, message, _ -> println(message) }
        try {
        val clip = assertNotNull(OpenCanvasProvider.search("Tippulu Tappulu", "Shreya Ghoshal", "Godavari", "ZDAT8DZi0jo", "720p", 263))
        assertTrue(clip.videoId != "RRIYowOPF6s")
        val folder = File("build/reports/canvas-framing").apply { mkdirs() }
        ProgressiveRangeSource(clip.url, OkHttpClient(), clip.headers).use { source ->
            val decoder = DesktopCanvasDecoder()
            try {
                decoder.open(source).getOrThrow()
                val bytes = ByteArray(decoder.width * decoder.height * 4)
                val pixels = IntArray(decoder.width * decoder.height)
                val image = BufferedImage(decoder.width, decoder.height, BufferedImage.TYPE_INT_ARGB)
                val report = StringBuilder("# Actual film-frame analysis\n\nVideo: ${clip.videoId}\n\n")
                var detections = 0
                OnnxPeopleDetector().use { detector ->
                    SmartFrameReframer(sourceAspect = decoder.width.toFloat()/decoder.height).use { reframer ->
                        for (time in (0..12).map { 30_000L + it * 250 } + listOf(60_000L,120_000L,160_000L)) {
                            assertTrue(decoder.seekToMs(time))
                            assertTrue(decoder.nextFrame(bytes, loop = false))
                            for (i in pixels.indices) {
                                val at=i*4
                                pixels[i]=(255 shl 24) or ((bytes[at+2].toInt() and 255) shl 16) or
                                    ((bytes[at+1].toInt() and 255) shl 8) or (bytes[at].toInt() and 255)
                            }
                            image.setRGB(0,0,decoder.width,decoder.height,pixels,0,decoder.width)
                            val began=System.nanoTime()
                            val subjects=detector.detect(pixels,decoder.width,decoder.height)
                            detections+=subjects.faces.size+subjects.people.size
                            val decision=reframer.analyze(pixels,decoder.width,decoder.height,decoder.lastPtsMs/1000.0)
                            report.append("$time ms: faces=${subjects.faces.size}, people=${subjects.people.size}, cx=${decision.cx}, fit=${decision.fitFullFrame}, two analyses=${(System.nanoTime()-began)/1e6} ms\n\n")
                            for ((label, frame) in listOf("center" to FramingDecision(fitFullFrame=false), "subject" to decision)) {
                                val output=BufferedImage(360,640,BufferedImage.TYPE_INT_RGB)
                                output.createGraphics().apply {
                                    color=Color.BLACK;fillRect(0,0,360,640)
                                    val p=videoViewport(decoder.width.toFloat(),decoder.height.toFloat(),360f,640f,frame)
                                    drawImage(image,p.left.toInt(),p.top.toInt(),p.width.toInt(),p.height.toInt(),null)
                                    dispose()
                                }
                                ImageIO.write(output,"png",File(folder,"$time-$label.png"))
                            }
                        }
                    }
                }
                assertTrue(detections>0,"Actual film frames must produce positive subject detections")
                File(folder,"analysis.md").writeText(report.toString())
                println(report)
            } finally { decoder.close() }
        }
        } finally { com.cosmotune.music.data.DebugLog.sink = previousSink }
    }
}
