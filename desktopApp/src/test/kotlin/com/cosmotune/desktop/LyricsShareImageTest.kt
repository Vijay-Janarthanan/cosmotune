package com.cosmotune.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.LyricsShareLine
import com.music.bitchord.ui.player.LyricsShareRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class LyricsShareImageTest {
    @Test fun mixedScriptCardExportsAsReadablePortraitPng() {
        val image = renderLyricsCard(LyricsShareRequest(
            Song("id", "மாலை — Evening", "கலைஞர் · Artist", null), null,
            listOf(LyricsShareLine("தமிழ் பாடல்", "A song at dusk"), LyricsShareLine("తెలుగు పాట · हिन्दी गीत", null), LyricsShareLine("مرحبا بالعالم", null))),
            0xff171719.toInt(), 0xfff9eadf.toInt(), 0xffefb083.toInt())
        val bytes = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "png", bytes))
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(1080, decoded.width); assertEquals(1920, decoded.height)
        val background = image.getRGB(0, 0)
        assertTrue((300 until 1550 step 4).any { y -> (96 until 984 step 4).any { x -> image.getRGB(x, y) != background } })
        assertEquals(background, image.getRGB(1079, 1919))
    }
}
