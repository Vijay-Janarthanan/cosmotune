package com.cosmotune.music.data.covers

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cutting the placed square out of the photo's pixels and making it the size that is kept. */
class CoverPixelsTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test fun theSquareIsCutOutAsItIs() {
        // 4 x 2: the left half red, the right half blue.
        val pixels = intArrayOf(red, red, blue, blue, red, red, blue, blue)
        assertContentEquals(intArrayOf(blue, blue, blue, blue), CoverPixels.crop(pixels, 4, 2, CropSquare(2, 0, 2), 2))
    }

    @Test fun shrinkingAveragesWhatEachPixelCovers() {
        val pixels = intArrayOf(red, blue, red, blue)
        val one = CoverPixels.crop(pixels, 2, 2, CropSquare(0, 0, 2), 1)
        assertEquals(1, one.size)
        assertEquals(0xFF800080.toInt(), one[0])
    }

    @Test fun aSeeThroughPixelIsLaidOnWhite() {
        val clear = 0x00000000
        assertContentEquals(intArrayOf(0xFFFFFFFF.toInt()), CoverPixels.crop(intArrayOf(clear), 1, 1, CropSquare(0, 0, 1), 1))
    }

    @Test fun theKeptSizeIsNeverBiggerThanThePhotoGives() {
        assertEquals(1200, CoverPixels.outputSide(CropSquare(0, 0, 3000), 1200))
        assertEquals(640, CoverPixels.outputSide(CropSquare(0, 0, 640), 1200))
        assertEquals(1, CoverPixels.outputSide(CropSquare(0, 0, 0), 1200))
    }

    @Test fun theSmallestQualityThatFitsIsUsedForTheAccountCopy() {
        val sizes = mapOf(82 to 70_000, 74 to 52_000, 66 to 40_000, 58 to 31_000, 50 to 25_000)
        val tried = mutableListOf<Int>()
        val picked = CoverJpeg.fitWithin(45_000) { quality -> tried += quality; ByteArray(sizes.getValue(quality)) }
        assertEquals(40_000, picked?.size)
        assertEquals(listOf(82, 74, 66), tried, "stops at the first that fits")
        assertNull(CoverJpeg.fitWithin(10_000) { ByteArray(sizes.getValue(it)) }, "nothing fits: no account copy")
    }
}
