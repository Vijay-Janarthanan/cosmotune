package com.cosmotune.watch

import com.cosmotune.watch.model.Subtitle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadExtrasTest {
    @Test fun keepsOneSubtitleFileALanguageBesideTheVideo() {
        val subs = listOf(
            Subtitle("1", "https://s/en-sdh.srt", "en", "English SDH", "srt", hearingImpaired = true),
            Subtitle("2", "https://s/en.srt", "en", "English", "srt"),
            Subtitle("3", "https://s/ta.vtt?x=1", "ta", "Tamil"),
            Subtitle("4", null, "fr", "French", trackId = "7"),
            Subtitle("5", "https://s/odd.sub", "pt-BR", "Português"),
        )
        assertEquals(listOf("2", "3", "5"), DownloadExtras.pick(subs).map { it.id })
        val dir = Files.createTempDirectory("dl-extras").toFile().also { it.deleteOnExit() }
        val video = java.io.File(dir, "Ember Road (2021).mkv")
        assertEquals("Ember Road (2021).poster.jpg", DownloadExtras.posterFile(video).name)
        val names = DownloadExtras.pick(subs).map { DownloadExtras.subtitleFile(video, it).also { f -> f.writeText("1\n00:00:01,000 --> 00:00:02,000\nHi\n") }.name }
        assertEquals(listOf("Ember Road (2021).en.srt", "Ember Road (2021).ta.vtt", "Ember Road (2021).pt-br.srt"), names)
        assertEquals(listOf("en", "pt-br", "ta"), DownloadExtras.subtitleFiles(video).map { it.second })
        dir.deleteRecursively()
    }
}
