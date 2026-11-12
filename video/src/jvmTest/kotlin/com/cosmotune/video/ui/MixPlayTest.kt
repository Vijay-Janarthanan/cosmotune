package com.cosmotune.video.ui

import com.cosmotune.video.account.FakeVideoAccount
import com.cosmotune.video.account.VideoAccount
import com.cosmotune.video.model.PlaylistRef
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.actions.playMix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test fun theMixIsHandedToThePlayerInItsOrderWithoutItsFirstVideo() = runTest {
        val playback = FakeVideoPlayback()
        val account = Account(listOf(listOf(v[0], v[1], v[2]), listOf(v[2], v[3])))
        playMix(account, ref, playback)
        assertEquals(listOf(v[1].id, v[2].id, v[3].id), playback.state.value.queue.map { it.id })
        assertEquals<List<Pair<String, String?>>>(listOf(ref.id to v[0].id, ref.id to v[2].id, ref.id to v[3].id), account.asked, "each ask goes on from the last video")
    }

    @Test fun nextWalksTheMix() = runTest {
        val playback = FakeVideoPlayback()
        playMix(Account(listOf(listOf(v[0], v[1], v[2]))), ref, playback)
        playback.playNext()
        assertEquals(listOf(v[2].id), playback.state.value.queue.map { it.id })
    }

    @Test fun aFailedAskLeavesTheFirstVideoAlone() = runTest {
        val playback = FakeVideoPlayback()
        playMix(object : VideoAccount by FakeVideoAccount() {
            override suspend fun mix(playlistId: String, videoId: String?): Result<List<VideoItem>> = Result.failure(RuntimeException("offline"))
        }, ref, playback)
        assertEquals(emptyList<VideoItem>(), playback.state.value.queue)
    }
}
