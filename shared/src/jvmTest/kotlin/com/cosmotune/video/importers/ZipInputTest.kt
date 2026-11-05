package com.cosmotune.video.importers

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipInputTest {
    private val work: File = Files.createTempDirectory("zip-input-test").toFile()

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    private val sample = ImportFixtures.zip("a.txt" to "alpha".encodeToByteArray(), "folder/b.txt" to "bravo".encodeToByteArray(), "folder/c.csv" to ByteArray(0))

    @Test
    fun listsTheFilesInArchiveOrderAndReadsOneByName() {
        val zip = ZipInput(sample)
        assertEquals(listOf("a.txt", "folder/b.txt", "folder/c.csv"), zip.names())
        assertEquals("bravo", zip.read("folder/b.txt")?.decodeToString())
        assertEquals("alpha", zip.read("a.txt")?.decodeToString())
        assertContentEquals(ByteArray(0), zip.read("folder/c.csv"))
        assertNull(zip.read("missing.txt"))
        assertNull(zip.read("folder"), "a folder is not a file")
    }

    @Test
    fun aFileIsReadThroughItsDirectoryWithTheSameResults() {
        val file = File(work, "sample.zip").also { it.writeBytes(sample) }
        val zip = ZipInput(file)
        assertEquals(listOf("a.txt", "folder/b.txt", "folder/c.csv"), zip.names())
        assertEquals("bravo", zip.read("folder/b.txt")?.decodeToString())
        assertContentEquals(ByteArray(0), zip.read("folder/c.csv"))
        assertNull(zip.read("missing.txt"))

        val backslashes = File(work, "backslashes.zip").also { it.writeBytes(ImportFixtures.zip("folder\\inner.txt" to "x".encodeToByteArray())) }
        assertEquals(listOf("folder/inner.txt"), ZipInput(backslashes).names())
        assertEquals("x", ZipInput(backslashes).read("folder/inner.txt")?.decodeToString())

        val latin = File(work, "latin.zip").also {
            it.writeBytes(ImportFixtures.zip("Takeout/YouTube y YouTube\u00A0Music/suscripciones/x.csv" to "y".encodeToByteArray(), charset = Charsets.ISO_8859_1))
        }
        val named = ZipInput(latin)
        assertEquals("y", named.read(named.names().single())?.decodeToString())
    }

    @Test
    fun foldersAreNotListed() {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("dir/"))
            it.closeEntry()
            it.putNextEntry(ZipEntry("dir/file.txt"))
            it.write(1)
            it.closeEntry()
        }
        assertEquals(listOf("dir/file.txt"), ZipInput(out.toByteArray()).names())
    }

    @Test
    fun readsFromAFileAndFromAStreamThatIsOpenedEachTime() {
        val file = File(work, "sample.zip").also { it.writeBytes(sample) }
        assertEquals("bravo", ZipInput(file).read("folder/b.txt")?.decodeToString())
        var opened = 0
        val zip = ZipInput { opened += 1; sample.inputStream() }
        zip.names()
        zip.names()
        assertEquals(1, opened, "the listing is made once")
        zip.read("a.txt")
        assertEquals(2, opened, "one more pass for one entry")
    }

    @Test
    fun backslashesInNamesBecomeSlashes() {
        val zip = ZipInput(ImportFixtures.zip("folder\\inner.txt" to "x".encodeToByteArray()))
        assertEquals(listOf("folder/inner.txt"), zip.names())
        assertEquals("x", zip.read("folder/inner.txt")?.decodeToString())
    }

    @Test
    fun namesThatAreNotUtf8AreStillFoundByTheSameName() {
        // an older tool wrote the name in a single-byte code page without the UTF-8 flag: 0xA0 (no-break space) then "Music"
        val bytes = ImportFixtures.zip("Takeout/YouTube y YouTube\u00A0Music/suscripciones/suscripciones.csv" to "x".encodeToByteArray(), charset = Charsets.ISO_8859_1)
        val zip = ZipInput(bytes)
        val name = zip.names().single()
        assertTrue(name.startsWith("Takeout/YouTube y YouTube") && name.endsWith("Music/suscripciones/suscripciones.csv"), name)
        assertEquals("x", zip.read(name)?.decodeToString())
    }

    @Test
    fun somethingThatIsNotAZipHasNoEntries() {
        assertEquals(emptyList(), ZipInput("this is not a zip".encodeToByteArray()).names())
        assertEquals(emptyList(), ZipInput(ByteArray(0)).names())
        assertNull(ZipInput(ByteArray(0)).read("a"))
        assertEquals(emptyList(), ZipInput(File(work, "missing.zip")).names())
    }

    @Test
    fun theTempFileOpenerWritesOpensAndDeletesWithItsSiblings() {
        val cache = File(work, "cache")
        var path = ""
        val opener = TempFileSqliteOpener(cache) { given ->
            path = given
            File("$given-wal").writeText("x") // what SQLite may leave beside a database
            File("$given-shm").writeText("x")
            ImportFixtures.JdbcTestRows(File(given).also { it.writeBytes(ImportFixtures.newPipeDatabase(work) { }) }.path)
        }
        val rows = opener.open(ByteArray(1))
        assertTrue(File(path).isFile && path.startsWith(cache.path))
        assertEquals(0, rows.query("SELECT * FROM subscriptions").size)
        rows.close()
        assertEquals(emptyList(), cache.listFiles()?.toList().orEmpty())
    }
}
