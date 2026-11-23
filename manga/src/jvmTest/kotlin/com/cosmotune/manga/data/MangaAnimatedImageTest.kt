package com.cosmotune.manga.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MangaAnimatedImageTest {
    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { part ->
        when (part) { is String -> part.toByteArray(Charsets.ISO_8859_1).toList(); is Int -> listOf(part.toByte()); is ByteArray -> part.toList(); else -> error(part) }
    }.toByteArray()

    @Test fun aGifWithTwoFramesMoves() {
        val frame = bytes(0x21, 0xF9, 0x04, 0, 10, 0, 0, 0, 0x2C)
        assertTrue(MangaAnimatedImage.isAnimated(bytes("GIF89a", ByteArray(7), frame, ByteArray(20), frame)))
        assertFalse(MangaAnimatedImage.isAnimated(bytes("GIF89a", ByteArray(7), frame, ByteArray(20))))
    }

    @Test fun anAnimatedWebpSaysSoInItsHeader() {
        assertTrue(MangaAnimatedImage.isAnimated(bytes("RIFF", ByteArray(4), "WEBPVP8X", ByteArray(4), 0x02, ByteArray(9))))
        assertFalse(MangaAnimatedImage.isAnimated(bytes("RIFF", ByteArray(4), "WEBPVP8X", ByteArray(4), 0x10, ByteArray(9))))
        assertFalse(MangaAnimatedImage.isAnimated(bytes("RIFF", ByteArray(4), "WEBPVP8 ", ByteArray(14))))
    }

    @Test fun anAnimatedPngHasItsControlChunkBeforeTheImage() {
        val sig = bytes(0x89, "PNG", 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(MangaAnimatedImage.isAnimated(bytes(sig, ByteArray(4), "IHDR", ByteArray(17), ByteArray(4), "acTL", ByteArray(12), "IDAT")))
        assertFalse(MangaAnimatedImage.isAnimated(bytes(sig, ByteArray(4), "IHDR", ByteArray(17), "IDAT", ByteArray(8), "acTL")))
    }

    @Test fun aJpegNeverMoves() {
        assertFalse(MangaAnimatedImage.isAnimated(bytes(0xFF, 0xD8, 0xFF, ByteArray(40))))
        assertFalse(MangaAnimatedImage.isAnimated(ByteArray(0)))
    }
}
