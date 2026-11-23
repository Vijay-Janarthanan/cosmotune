package com.cosmotune.manga.data

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals

class MangaCbzTest {
    @Test fun namesAreSafeOnEverySystem() {
        assertEquals("Berserk_ Vol. 1", MangaCbz.safeName("Berserk: Vol. 1"))
        assertEquals("a_b_c", MangaCbz.safeName("a/b\\c"))
        assertEquals("Untitled", MangaCbz.safeName(" .. "))
        assertEquals(120, MangaCbz.safeName("x".repeat(300)).length)
    }

    @Test fun onlyVeryTallPagesAreCutAndThePiecesCoverTheWholePage() {
        assertEquals(listOf(0 until 2000), MangaCbz.splitPlan(800, 2000))
        val pieces = MangaCbz.splitPlan(800, 10_000)
        assertEquals(7, pieces.size)
        assertEquals(0, pieces.first().first)
        assertEquals(10_000, pieces.last().last + 1)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test fun pagesKeepTheirOrderAndKind() {
        val dir = kotlin.io.path.createTempDirectory("cbz").toFile()
        try {
            val jpeg = File(dir, "p1").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)) }
            val png = File(dir, "p2").apply { writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 2)) }
            val target = File(dir, "out/Series/Chapter 1.cbz")
            MangaCbz.write(target, listOf(jpeg, png), split = false, sizeOf = { null }, slice = { _, _, _, _ -> false })
            ZipFile(target).use { zip -> assertEquals(listOf("001.jpg", "002.png"), zip.entries().toList().map { it.name }) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun aTallPageBecomesNumberedPieces() {
        val dir = kotlin.io.path.createTempDirectory("cbz").toFile()
        try {
            val tall = File(dir, "p1").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)) }
            val target = File(dir, "c.cbz")
            MangaCbz.write(target, listOf(tall), split = true, sizeOf = { 100 to 700 }, slice = { _, top, _, out -> out.writeText("$top"); true })
            ZipFile(target).use { zip -> assertEquals(listOf("001-1.jpg", "001-2.jpg", "001-3.jpg", "001-4.jpg"), zip.entries().toList().map { it.name }) }
        } finally { dir.deleteRecursively() }
    }
}
