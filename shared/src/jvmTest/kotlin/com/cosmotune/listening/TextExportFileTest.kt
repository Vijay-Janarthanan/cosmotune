package com.cosmotune.listening

import java.io.File
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TextExportFileTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun failedExportPreservesTheExistingFileAndRemovesOnlyItsTemporaryOutput() {
        val folder = temporary.newFolder()
        val target = File(folder, "backup.json").apply { writeText("original backup") }
        assertFailsWith<IOException> { publishTextExport(target, "replacement".toByteArray()) { file, _ ->
            file.writeText("incomplete"); throw IOException("Storage interrupted")
        } }
        assertEquals("original backup", target.readText())
        assertEquals(listOf("backup.json"), folder.listFiles().orEmpty().map { it.name })
        writeTextExportFile(target, "தமிழ்", "json")
        assertEquals("தமிழ்", target.readText())
    }
    @Test fun choosingAnAudioFilenameCannotOverwriteTheSourceOrItsLocalManifest() {
        val folder = temporary.newFolder()
        val audio = File(folder, "song.mp3").apply { writeText("original audio") }
        val export = exportLocalM3u8("Playlist", listOf(com.cosmotune.music.data.model.Song("song", "Song", "Artist", null, localPath = audio.path)))
        val target = writeTextExportFile(audio, export.text, "m3u8")
        assertEquals("song.mp3.m3u8", target.name); assertEquals("original audio", audio.readText())
        val manifest = File(folder, "song.m3u8").apply { writeText("original manifest") }
        assertFailsWith<IllegalArgumentException> { writeTextExportFile(manifest, "#EXTM3U\n${manifest.toURI()}\n", "m3u8") }
        assertEquals("original manifest", manifest.readText())
    }
}
