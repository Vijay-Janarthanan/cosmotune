package com.cosmotune.video.downloads

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class FileDownloadsIoTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun separateReadersObserveWholeSnapshotsDuringAtomicReplacement() {
        val root = temporary.newFolder()
        val writer = FileDownloadsIo(root)
        val reader = FileDownloadsIo(root)
        writer.write(DownloadsFile.NAME, "record-0\n")
        val complete = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reading = pool.submit<Int> {
                var count = 0
                while (!complete.get() || count < 100) {
                    val value = requireNotNull(reader.read(DownloadsFile.NAME))
                    assertTrue(value.matches(Regex("record-[0-9]+\\n")), "A reader must never see a partial or missing replacement")
                    count++
                }
                count
            }
            val writing = pool.submit {
                try { repeat(100) { at ->
                    writer.write("downloads-test.tmp", "record-${at + 1}\n")
                    writer.rename("downloads-test.tmp", DownloadsFile.NAME)
                } } finally { complete.set(true) }
            }
            writing.get(10, TimeUnit.SECONDS)
            assertTrue(reading.get(10, TimeUnit.SECONDS) >= 100)
            assertEquals("record-100\n", reader.read(DownloadsFile.NAME))
        } finally { complete.set(true); pool.shutdownNow() }
    }
}
