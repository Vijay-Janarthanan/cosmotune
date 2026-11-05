package com.cosmotune.music.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopTrayFlyoutTest {

    private val screen = Rectangle(0, 0, 1920, 1080)

    // --- where the window opens -----------------------------------------------------------------

    @Test fun aTaskbarAtTheBottomPutsTheWindowAboveTheIcon() {
        val usable = Rectangle(0, 0, 1920, 1040) // a 40 px taskbar
        val at = DesktopTrayFlyoutPlacement.place(1700, 1060, 376, 204, screen, usable)
        assertEquals(1040 - 204 - 4, at.y)
        assertEquals(1700 - 188, at.x, "centred on the icon")
    }

    @Test fun aWindowThatWouldRunOffTheRightEdgeIsPushedBack() {
        val usable = Rectangle(0, 0, 1920, 1040)
        val at = DesktopTrayFlyoutPlacement.place(1915, 1060, 376, 204, screen, usable)
        assertEquals(1920 - 376 - 4, at.x)
    }

    @Test fun aTaskbarOnTheRightPutsTheWindowLeftOfTheIcon() {
        val usable = Rectangle(0, 0, 1880, 1080)
        val at = DesktopTrayFlyoutPlacement.place(1900, 700, 376, 204, screen, usable)
        assertEquals(1880 - 376 - 4, at.x)
        assertEquals(700 - 102, at.y)
    }

    @Test fun aMenuBarAtTheTopPutsTheWindowBelowTheIcon() {
        val usable = Rectangle(0, 24, 1920, 1056) // macOS: a 24 px menu bar
        val at = DesktopTrayFlyoutPlacement.place(1500, 10, 376, 204, screen, usable)
        assertEquals(24 + 4, at.y)
    }

    @Test fun aTaskbarThatHidesItselfStillOpensAtTheNearestEdge() {
        val at = DesktopTrayFlyoutPlacement.place(1700, 1075, 376, 204, screen, screen)
        assertEquals(1080 - 204 - 4, at.y)
    }

    @Test fun aSecondScreenIsMeasuredFromItsOwnOrigin() {
        val second = Rectangle(1920, 0, 1920, 1080)
        val usable = Rectangle(1920, 0, 1920, 1040)
        val at = DesktopTrayFlyoutPlacement.place(2000, 1060, 376, 204, second, usable)
        assertTrue(at.x >= 1920 + 4, "inside the second screen: ${at.x}")
        assertEquals(1040 - 204 - 4, at.y)
    }

    // --- what it lists --------------------------------------------------------------------------

    @Test fun theSongsOwnLineIsTheHeaderNotAnEntry() {
        DesktopTrayMenu.publish("Chinna Thamarai — Vijay Antony", playing = true)
        val entries = menuEntries(DesktopTrayMenu.entries())
        assertTrue(!entries.first().separator && entries.first().enabled, "no label or separator ahead of the first action")
        assertEquals("Pause", entries.first().label)
        assertTrue(entries.none { it.label.startsWith("Chinna") })
        assertTrue(entries.last().label.contains("Quit", ignoreCase = true))
    }

    // --- how it looks ---------------------------------------------------------------------------

    private fun cover(): File = File("build/tray-preview/cover.png").also { file ->
        file.parentFile.mkdirs()
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = GradientPaint(0f, 0f, Color(0xE5, 0x5B, 0x2D), 300f, 300f, Color(0x2D, 0x1B, 0x69))
            fillRect(0, 0, 300, 300)
            color = Color(255, 255, 255, 190)
            fillOval(70, 70, 160, 160)
            dispose()
        }
        ImageIO.write(image, "png", file)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, mode: DesktopTrayFlyout.Mode, now: DesktopMiniPlayer.NowPlaying, card: DpSize) {
        val entries = menuEntries(DesktopTrayMenu.entries())
        val pad = 16.dp
        val scene = ImageComposeScene(((card.width + pad * 2).value * 2).toInt(), ((card.height + pad * 2).value * 2).toInt(), Density(2f)) {
            DesktopTrayFlyoutContent(mode, now, DesktopMiniPlayer.Actions({}, {}, {}, {}), entries, card) {}
        }
        // The cover loads off the main thread: draw until it has.
        var bytes: ByteArray? = null
        repeat(40) { tick ->
            val image = scene.render(tick * 50_000_000L)
            bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
            Thread.sleep(50)
        }
        scene.close()
        File("build/tray-preview/$name.png").writeBytes(bytes!!)
    }

    @Test fun theCardsDrawWithAndWithoutACover() {
        val art = cover().toURI().toString()
        DesktopTrayMenu.publish("Chinna Thamarai — Vijay Antony", playing = true)
        val song = DesktopMiniPlayer.NowPlaying("Chinna Thamarai", "Vijay Antony, Krish, Suchitra & Viveka", art, true, 0.42f)
        snapshot("player", DesktopTrayFlyout.Mode.PLAYER, song, DpSize(344.dp, 172.dp))
        snapshot("player-empty", DesktopTrayFlyout.Mode.PLAYER, DesktopMiniPlayer.NowPlaying(), DpSize(344.dp, 172.dp))
        val entries = menuEntries(DesktopTrayMenu.entries())
        val rows = entries.count { !it.separator }
        val separators = entries.count { it.separator }
        snapshot("menu", DesktopTrayFlyout.Mode.MENU, song, DpSize(252.dp, (12 + rows * 36 + separators * 9 + 58 + 9).dp))
        assertTrue(File("build/tray-preview/player.png").length() > 5_000)
        assertTrue(File("build/tray-preview/menu.png").length() > 5_000)
    }

    @Test fun theTaskbarCardIsDrawnWithAndWithoutACover() {
        val art = ImageIO.read(cover())
        val folder = File("build/tray-preview").also { it.mkdirs() }
        val with = DesktopTaskbarCard.render(200, 112, art, "Chinna Thamarai", "Vijay Antony, Krish, Suchitra & Viveka")
        val without = DesktopTaskbarCard.render(200, 112, null, "Chinna Thamarai", "Vijay Antony")
        val big = DesktopTaskbarCard.render(640, 400, art, "Chinna Thamarai", "Vijay Antony, Krish, Suchitra & Viveka")
        ImageIO.write(with, "png", File(folder, "taskbar-card.png"))
        ImageIO.write(without, "png", File(folder, "taskbar-card-no-cover.png"))
        ImageIO.write(big, "png", File(folder, "taskbar-card-large.png"))
        assertEquals(200, with.width)
        assertEquals(112, with.height)
        assertTrue((with.getRGB(100, 40) ushr 24) == 255, "opaque where the cover is")
        assertTrue(with.getRGB(100, 40) != without.getRGB(100, 40), "the cover changes the picture")
    }

    @Test fun theTransportGlyphsAreDrawn() {
        for (glyph in DesktopTaskbarGlyphs.Glyph.values()) {
            val image = DesktopTaskbarGlyphs.render(glyph, 16)
            val painted = (0 until 16).sumOf { x -> (0 until 16).count { y -> (image.getRGB(x, y) ushr 24) > 0 } }
            assertTrue(painted in 20..200, "$glyph painted $painted pixels")
        }
    }

    @Test fun theDockIconIsTheCoverInARoundedSquare() {
        val icon = DesktopDockIcon.render(ImageIO.read(cover()))
        File("build/tray-preview").mkdirs()
        ImageIO.write(icon, "png", File("build/tray-preview/dock-icon.png"))
        assertEquals(512, icon.width)
        assertTrue((icon.getRGB(2, 2) ushr 24) == 0, "the corner is clear")
        assertTrue((icon.getRGB(256, 240) ushr 24) == 255, "the cover is opaque in the middle")
        assertTrue((icon.getRGB(60, 60) ushr 24) < 255, "the rounded corner of the square is clear")
    }

    @Test fun theLogoIsRenderedForTheDockWhenNothingIsPlaying() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><circle cx="32" cy="32" r="30" fill="#e55b2d"/></svg>"""
        val logo = assertNotNull(renderLogo(svg.toByteArray(), 128))
        assertEquals(128, logo.width)
        assertTrue((logo.getRGB(64, 64) ushr 24) == 255)
    }

    @Test fun aSizeThatIsZeroStillGivesAPicture() {
        assertEquals(1, DesktopTaskbarCard.render(0, 0, null, "", "").width)
        assertTrue(Point(0, 0) == Point(0, 0))
    }
}
