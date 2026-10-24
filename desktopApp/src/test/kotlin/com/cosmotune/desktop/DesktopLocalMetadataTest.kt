package com.cosmotune.desktop

import com.cosmotune.listening.LocalTagEditor
import com.cosmotune.listening.LocalTags
import com.cosmotune.music.desktop.DesktopLocalMusic
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Local files keep their tags; missing artists fall back to album artist, file name, then folders. Temporary files only. */
class DesktopLocalMetadataTest {
    private val root = Files.createTempDirectory("cosmotune-local-").toFile()
    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun song(file: File) = with(DesktopLocalMusic) { file.toPath().toSong() }
    private fun mp3(file: File) = file.apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(2048)) }

    @Test fun tagsAndFallbacksNameLocalFiles() {
        val tagged = mp3(File(root, "Music/whatever/02 track.mp3"))
        LocalTagEditor.write(tagged, LocalTags(title = "Kannalane", albumArtist = "A. R. Rahman", album = "Bombay"))
        val a = song(tagged)
        assertEquals("Kannalane", a.title); assertEquals("A. R. Rahman", a.artist); assertEquals("Bombay", a.albumName)
        assertEquals("local:${tagged.toPath().toAbsolutePath().normalize()}", a.videoId) // identity unchanged by tags

        val byName = song(mp3(File(root, "Chitra - Kannalane.mp3")))
        assertEquals("Chitra" to "Kannalane", byName.artist to byName.title)

        val byFolder = song(mp3(File(root, "Ilaiyaraaja/Thalapathi/03 Rakkamma.mp3")))
        assertEquals("Ilaiyaraaja", byFolder.artist); assertEquals("03 Rakkamma", byFolder.title)

        val unknown = song(File(root, "x.opus").apply { writeBytes(ByteArray(64)) })
        assertEquals("On This Computer", unknown.artist)
    }
}
