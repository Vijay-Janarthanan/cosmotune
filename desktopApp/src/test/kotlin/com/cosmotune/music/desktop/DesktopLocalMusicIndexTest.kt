package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import java.io.File
import java.nio.file.attribute.FileTime
import kotlin.test.*

class DesktopLocalMusicIndexTest {
    @Test fun unchangedFilesReuseDurableMetadataAndOnlyAChangedFileReadsItsTagsAgain() {
        val directory = kotlin.io.path.createTempDirectory("local-index-").toFile()
        try {
            val file = File(directory, "music.mp3").apply { writeBytes(ByteArray(100)) }.toPath()
            val indexFile = File(directory, "index.json")
            var reads = 0
            fun read(path: java.nio.file.Path): Song {
                reads++
                return Song("local:$path", "Title $reads", "Artist", null, localPath = path.toString())
            }
            val first = DesktopLocalMusicIndex(indexFile)
            val original = first.song(file, ::read)
            first.finish(setOf(file.toString()))
            val reopened = DesktopLocalMusicIndex(indexFile)
            assertEquals(original, reopened.song(file, ::read))
            assertEquals(1, reads)
            java.nio.file.Files.setLastModifiedTime(file, FileTime.fromMillis(java.nio.file.Files.getLastModifiedTime(file).toMillis() + 10_000))
            assertEquals("Title 2", reopened.song(file, ::read).title)
            assertEquals(2, reads)
            reopened.finish(setOf(file.toString()))
            assertEquals("Title 2", DesktopLocalMusicIndex(indexFile).song(file, ::read).title)
            assertEquals(2, reads)
        } finally { directory.listFiles().orEmpty().forEach(File::delete); directory.delete() }
    }
}
