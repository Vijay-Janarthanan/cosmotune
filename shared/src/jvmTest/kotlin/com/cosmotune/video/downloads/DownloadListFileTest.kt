package com.cosmotune.video.downloads

import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadListFileTest {
    private fun item(id: String) = VideoItem(id, "Title $id", "UCx", "Desk Lab", null, "https://example.com/$id.jpg", 600, null, null, null)

    @Test fun aListReadsBackWithWhatWasAsked() {
        val request = DownloadRequest(DownloadQuality.Rung(720, 60), SubtitlePick(true, "ta"))
        val entries = listOf(DownloadEntryFixtures.entry(item("abcdefghijk"), request, DownloadState.Done("/x.mp4", 10)))
        val back = DownloadListFile.read(DownloadListFile.write(entries))!!
        assertEquals(listOf("abcdefghijk"), back.map { it.first.id })
        assertEquals(request, back.single().second)
        assertEquals("Desk Lab", back.single().first.channelName)
    }

    @Test fun onlyWhatIsNotHereIsDownloadedAgain() {
        val list = listOf(item("abcdefghijk") to DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null)), item("bcdefghijkl") to DownloadRequest(DownloadQuality.AudioOnly, SubtitlePick(false, null)))
        val existing = listOf(DownloadEntryFixtures.entry(item("abcdefghijk"), list[0].second, DownloadState.Waiting))
        assertEquals(listOf("bcdefghijkl"), DownloadListFile.missing(list, existing).map { it.first.id })
        val failed = listOf(DownloadEntryFixtures.entry(item("abcdefghijk"), list[0].second, DownloadState.Failed("x")))
        assertEquals(2, DownloadListFile.missing(list, failed).size)
    }

    @Test fun otherFilesAreNotLists() {
        assertNull(DownloadListFile.read("{\"format\":\"cosmotune-video\"}"))
        assertNull(DownloadListFile.read("nonsense"))
    }
}

internal object DownloadEntryFixtures {
    private val details = kotlinx.coroutines.runBlocking { com.cosmotune.video.source.FakeVideoSource().details("fakevideo01") }
    private val choice = DownloadPlanner.choiceFor(
        DownloadRequest(DownloadQuality.Rung(1080, 30), SubtitlePick(false, null)), details,
        DeviceCaps(1080, 60, setOf(com.cosmotune.video.model.VideoCodec.H264, com.cosmotune.video.model.VideoCodec.VP9)),
    )

    fun entry(item: VideoItem, request: DownloadRequest, state: DownloadState) =
        DownloadEntry("download-${item.id}", item, DownloadKind.VIDEO, choice, request, state, null, emptyList(), null, 0)
}
