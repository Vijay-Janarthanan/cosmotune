package com.cosmotune.watch

import com.cosmotune.video.player.FakeVideoPlayerEngine
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.UrlKind
import com.cosmotune.watch.model.Container
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProgress
import com.cosmotune.watch.player.EngineWatchPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineWatchPlayerTest {
    private val film = TitleSummary(WatchKey.tmdbFilm(2), TitleType.FILM, "Ember Road", 2021, runtimeMin = 112, ids = ExternalIds(tmdb = 2))

    private fun stream(plugin: String, url: String, protocol: StreamProtocol = StreamProtocol.PROGRESSIVE) =
        Stream(StreamSource(plugin, plugin, plugin), "1080p", url = url, protocol = protocol, container = Container.MKV, height = 1080,
            headers = mapOf("Referer" to "https://ref"), filename = "ember.mkv",
            subtitles = listOf(Subtitle("s1", "https://subs/en.srt", "eng", "English", "srt", SubtitleOrigin.STREAM)))

    /** The fake repository with a fixed list of copies and saved progress. */
    private class Repo(val list: List<Stream>, val saved: WatchProgress? = null) : WatchRepository by FakeWatchRepository() {
        val savedProgress = mutableListOf<WatchProgress>()
        override fun sources(target: PlayTarget, hint: StreamHint?): Flow<SourcesState> = flowOf(SourcesState(target, list, emptySet()))
        override suspend fun progress(key: WatchKey) = saved
        override suspend fun saveProgress(progress: WatchProgress) { savedProgress += progress }
    }

    private suspend fun until(what: String, check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(20) }.also { assertTrue(check(), what) }

    @Test fun resumesTheBestCopyAsASingleAddressWithItsHeadersAndSubtitles() = runBlocking<Unit> {
        val engine = FakeVideoPlayerEngine()
        val repo = Repo(listOf(stream("jf", "https://jf/ember.mkv"), stream("plex", "https://plex/ember.m3u8", StreamProtocol.HLS)),
            WatchProgress(film.key, 2_412_000, 6_720_000, 1, film))
        val player = EngineWatchPlayer(repo, engine, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default), settleMs = 0, tickMs = 20)
        player.open(repo.targetOf(film), film)
        until("loaded") { engine.lastRequest != null }
        val request = engine.lastRequest!!
        val input = assertIs<MediaInput.Url>(request.input)
        assertEquals("https://jf/ember.mkv", input.url)
        assertEquals(UrlKind.PROGRESSIVE, input.kind)
        assertEquals("video/x-matroska", input.mimeHint)
        assertEquals(2_412_000, request.startMs, "resumes where it was left")
        assertEquals("https://ref", request.headers["Referer"])
        assertEquals("application/x-subrip", request.subtitles.single().mimeType)
        assertEquals("Ember Road", request.title)
        // The copy fails: the next one plays, from where it was.
        engine.setPosition(2_500_000)
        engine.fail(PlayerErrorKind.NETWORK)
        until("failed over") { (engine.lastRequest?.input as? MediaInput.Url)?.url == "https://plex/ember.m3u8" }
        assertEquals(UrlKind.HLS, (engine.lastRequest!!.input as MediaInput.Url).kind)
        assertEquals(2_500_000, engine.lastRequest!!.startMs)
        player.stop()
        assertTrue(engine.unloadCount >= 1)
    }

    @Test fun savesProgressWithTheReleaseAndEndsOnTheLastCopysError() = runBlocking<Unit> {
        val engine = FakeVideoPlayerEngine()
        val repo = Repo(listOf(stream("jf", "https://jf/ember.mkv")))
        val player = EngineWatchPlayer(repo, engine, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default), settleMs = 0, tickMs = 20, saveEveryMs = 0)
        player.open(repo.targetOf(film), film, startMs = 0)
        until("loaded") { engine.lastRequest != null }
        engine.setSnapshot { it.copy(status = PlayerStatus.READY, playing = true, durationMs = 6_720_000) }
        engine.setPosition(60_000)
        until("saved") { repo.savedProgress.isNotEmpty() }
        val saved = repo.savedProgress.last()
        assertEquals(film.key, saved.key)
        assertEquals("ember.mkv", saved.lastHint?.filename)
        assertEquals(6_720_000, saved.durationMs)
        engine.fail(PlayerErrorKind.DECODER, "no decoder")
        until("error shown") { player.state.value.status == WatchPlayerStatus.ERROR }
        assertEquals("no decoder", player.state.value.error)
    }

    @Test fun nothingFoundSaysSo() = runBlocking<Unit> {
        val player = EngineWatchPlayer(Repo(emptyList()), FakeVideoPlayerEngine(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Default), settleMs = 0)
        player.open(PlayTarget.Film(film.key, film.ids, film.name), film)
        until("not found") { player.state.value.status == WatchPlayerStatus.NOT_FOUND }
    }

    @Test fun youTubeCopiesGoToVideoModesPlayer() = runBlocking<Unit> {
        var handed: Pair<String, Long>? = null
        val repo = Repo(listOf(stream("youtube", "https://www.youtube.com/watch?v=abc123", StreamProtocol.YOUTUBE)))
        val engine = FakeVideoPlayerEngine()
        val player = EngineWatchPlayer(repo, engine, onYouTube = { id, at -> handed = id to at }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default), settleMs = 0)
        player.open(repo.targetOf(film), film, startMs = 5_000)
        until("handed over") { handed != null }
        assertEquals("abc123" to 5_000L, handed)
        assertEquals(null, engine.lastRequest)
    }
}
