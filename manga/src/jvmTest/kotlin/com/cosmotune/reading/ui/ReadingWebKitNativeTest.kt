package com.cosmotune.reading.ui

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.web.WebView
import javafx.util.Duration
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** Opt-in desktop integration test: real WebKit pixels, never an Android-device claim. */
class ReadingWebKitNativeTest {
    @Test fun nativeWebKitRendersIllustratedEpubAndAppSearch() {
        assumeTrue("Enable only with a native desktop runtime", System.getenv("COSMOTUNE_READING_WEBKIT_TEST") == "1")
        System.setProperty("java.awt.headless", "false")
        val root = Files.createTempDirectory("reading-webkit-").toFile()
        try {
            ReadingEpubServer(ReadingRendererTest().publication(root)).use { server ->
                var panel: JFXPanel? = null
                SwingUtilities.invokeAndWait { panel = JFXPanel().apply { setSize(800, 600) } }
                Platform.setImplicitExit(false)
                val finished = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>(null)
                Platform.runLater {
                    try {
                        val web = WebView().apply { setPrefSize(800.0, 600.0) }
                        panel!!.scene = Scene(web, 800.0, 600.0)
                        web.engine.loadWorker.stateProperty().addListener { _, _, state ->
                            if (state == Worker.State.SUCCEEDED) {
                                PauseTransition(Duration.millis(700.0)).apply {
                                    setOnFinished {
                                        try {
                                            val count = web.engine.executeScript(ReadingEpubScripts.search("Once upon")) as Number
                                            assertEquals(1, count.toInt())
                                            web.applyCss(); web.layout()
                                            val image = SwingFXUtils.fromFXImage(web.snapshot(null, null), null)
                                            var red = 0; var blue = 0
                                            for (y in 0 until image.height) for (x in 0 until image.width) {
                                                val pixel = java.awt.Color(image.getRGB(x, y))
                                                if (pixel.red > 220 && pixel.green < 40 && pixel.blue < 40) red++
                                                if (pixel.blue > 220 && pixel.green < 40 && pixel.red < 40) blue++
                                            }
                                            val output = File(System.getenv("COSMOTUNE_READING_ARTIFACT_DIR") ?: "build/reports/reading-native").apply { mkdirs() }
                                            ImageIO.write(image, "png", File(output, "epub-colour-webkit.png"))
                                            assertTrue(red > 1000 && blue > 1000, "Native SVG colour pixels missing: red=$red blue=$blue")
                                        } catch (e: Throwable) { failure.set(e) }
                                        finally { web.engine.load(null); panel!!.scene = null; finished.countDown() }
                                    }
                                    play()
                                }
                            } else if (state == Worker.State.FAILED) {
                                failure.set(web.engine.loadWorker.exception ?: IllegalStateException("WebKit failed to load EPUB")); finished.countDown()
                            }
                        }
                        web.engine.load(server.content.url("OPS/chapter.xhtml"))
                    } catch (e: Throwable) { failure.set(e); finished.countDown() }
                }
                assertTrue(finished.await(25, TimeUnit.SECONDS), "Native EPUB rendering timed out")
                failure.get()?.let { throw it }
            }
        } finally { root.deleteRecursively() }
    }
}
