package com.cosmotune.listening

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NowPlayingOutTest {
    private val song = NowPlayingEvent("abcdefghijk", "Night Drive", "The Singer", album = "Roads", playing = true, durationMs = 200_000)

    @AfterTest fun tearDown() { NowPlayingOut.reset(); NowPlayingOut.post = { _, _ -> } }

    @Test fun theWebhookGetsTheSongAsJsonOncePerChange() {
        val sent = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()
        NowPlayingOut.post = { url, body -> sent += url to body }
        assertTrue(NowPlayingOut.publish(song, " https://home.example/hook ", null))
        assertFalse(NowPlayingOut.publish(song.copy(durationMs = 1), "https://home.example/hook", null), "the same song and state: nothing new")
        assertTrue(NowPlayingOut.publish(song.copy(playing = false), "https://home.example/hook", null))
        val first = sent.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("https://home.example/hook", first.first)
        assertTrue("\"state\":\"playing\"" in first.second && "\"title\":\"Night Drive\"" in first.second)
        assertTrue("\"link\":\"https://music.youtube.com/watch?v=abcdefghijk\"" in first.second)
        assertTrue("\"state\":\"paused\"" in sent.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!.second)
    }

    @Test fun onlyAWebAddressIsAWebhook() {
        assertTrue(NowPlayingOut.validWebhook("http://192.168.1.4:8123/api/webhook/x"))
        assertFalse(NowPlayingOut.validWebhook("file:///etc/passwd"))
        assertFalse(NowPlayingOut.validWebhook("https://user:pw@host/x"))
        assertFalse(NowPlayingOut.validWebhook(""))
    }

    @Test fun theFileNamesTheSongWhilePlayingAndEmptiesWhenPaused() {
        assertEquals("The Singer – Night Drive", NowPlayingOut.fileText(song))
        assertEquals("", NowPlayingOut.fileText(song.copy(playing = false)))
        assertEquals("", NowPlayingOut.fileText(null))
        val file = Files.createTempDirectory("np").resolve("now-playing.txt").toFile()
        NowPlayingOut.publish(song, "", file)
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("The Singer – Night Drive", file.readText())
        file.parentFile.deleteRecursively()
    }
}
