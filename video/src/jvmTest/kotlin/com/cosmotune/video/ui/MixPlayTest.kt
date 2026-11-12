package com.cosmotune.video.ui

import com.cosmotune.video.account.FakeVideoAccount
import com.cosmotune.video.account.VideoAccount
import com.cosmotune.video.model.PlaylistRef
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.actions.MixFollower
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixPlayTest {
    private val v = FakeVideoSource.videos
    private val ref = PlaylistRef("RD${v[0].id}", "Mix", v[0].id)

    private class Account(val stretches: List<List<VideoItem>>) : VideoAccount by FakeVideoAccount() {
        val asked = mutableListOf<Pair<String, String?>>()
        override suspend fun mix(playlistId: String, videoId: String?): Result<List<VideoItem>> {
            asked += playlistId to videoId
            return Result.success(stretches.getOrElse(asked.size - 1) { emptyList() })
        }
    }

    private fun playing(first: String) = FakeVideoPlayback().apply { play(first) }

    @Test fun theFirstStretchIsQueuedWithoutPlayingTheFirstVideoAgain() = runTest {
        val playback = playing(v[0].id)
        val follower = MixFollower(Account(listOf(listOf(v[0], v[1], v[2]))), ref, playback)
        assertTrue(follower.start())
        assertEquals(listOf(v[1].id, v[2].id), playback.state.value.queue.map { it.id })
        assertEquals(1, playback.calls.count { it.startsWith("play(") }, "the first video is not started again: ${playback.calls}")
    }

    @Test fun theMixKeepsGoingWhileItPlays() = runTest {
        val playback = playing(v[0].id)
        val account = Account(listOf(listOf(v[0], v[1], v[2]), listOf(v[2], v[3]), listOf(v[3], v[4])))
        val follower = MixFollower(account, ref, playback)
        follower.start()
        assertTrue(follower.onState(playback.state.value), "the mix is playing")
        assertEquals(listOf(v[1].id, v[2].id, v[3].id), playback.state.value.queue.map { it.id }, "a short queue is topped up")
        assertEquals<List<Pair<String, String?>>>(listOf(ref.id to v[0].id, ref.id to v[2].id), account.asked, "each ask goes on from the last video")
        follower.onState(playback.state.value)
        assertEquals(listOf(v[1].id, v[2].id, v[3].id, v[4].id), playback.state.value.queue.map { it.id })
    }

    @Test fun nextWalksTheMix() = runTest {
        val playback = playing(v[0].id)
        MixFollower(Account(listOf(listOf(v[0], v[1], v[2]))), ref, playback).start()
        playback.playNext()
        assertEquals(listOf(v[2].id), playback.state.value.queue.map { it.id })
    }

    @Test fun playingSomethingElseEndsTheMix() = runTest {
        val playback = playing(v[0].id)
        val follower = MixFollower(Account(listOf(listOf(v[0], v[1]))), ref, playback)
        follower.start()
        playback.play(v[5].id)
        assertFalse(follower.onState(playback.state.value))
    }

    @Test fun aFailedAskLeavesTheFirstVideoAlone() = runTest {
        val playback = playing(v[0].id)
        val follower = MixFollower(object : VideoAccount by FakeVideoAccount() {
            override suspend fun mix(playlistId: String, videoId: String?): Result<List<VideoItem>> = Result.failure(RuntimeException("offline"))
        }, ref, playback)
        assertFalse(follower.start())
        assertEquals(emptyList<VideoItem>(), playback.state.value.queue)
    }
}
