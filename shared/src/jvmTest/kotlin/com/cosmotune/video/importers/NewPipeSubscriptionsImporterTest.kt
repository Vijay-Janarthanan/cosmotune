package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.C0
import com.cosmotune.video.importers.ImportFixtures.C1
import com.cosmotune.video.importers.ImportFixtures.C2
import com.cosmotune.video.importers.ImportFixtures.C3
import com.cosmotune.video.importers.ImportFixtures.C4
import com.cosmotune.video.importers.ImportFixtures.NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewPipeSubscriptionsImporterTest {
    @Test
    fun readsTheExportOfNewPipe() {
        val preview = NewPipeSubscriptionsImporter.parse(ImportFixtures.text("newpipe-subscriptions.json"), NOW)
        assertEquals(listOf(C0, C1, C2, C3), preview.subscriptions.map { it.channelId }, "service 0 only, the first of a repeated channel")
        assertEquals(
            listOf("Northern Lights Workshop", "Paper, Scissors & Stone", "Cooking \"Slow\", Daily", "Tëst Chännel 中文"),
            preview.subscriptions.map { it.name },
        )
        assertTrue(preview.subscriptions.all { it.subscribedAtMs == NOW && it.lastUploadMs == null && it.avatarUrl == null })
        assertEquals(2, preview.skipped, "the PeerTube channel and the repeated channel")
        assertTrue(preview.unresolvedChannels.isEmpty())
        assertNull(preview.reason)
        assertTrue(preview.history.isEmpty() && preview.playlists.isEmpty() && preview.savedPlaylists.isEmpty() && preview.searches.isEmpty())
    }

    @Test
    fun userCAndHandleAddressesAreLeftForTheImportScreenToResolve() {
        val preview = NewPipeSubscriptionsImporter.parse(ImportFixtures.text("newpipe-subscriptions-user.json"), NOW)
        assertEquals(listOf(C4), preview.subscriptions.map { it.channelId })
        assertEquals(
            listOf("https://www.youtube.com/user/OldTimersTV", "https://www.youtube.com/c/WorkshopClips", "https://www.youtube.com/@lantern-notes"),
            preview.unresolvedChannels,
            "addresses the source can take as they are, over https, once each",
        )
        assertEquals(2, preview.skipped, "the repeated /user/ address and the playlist address")
        assertTrue(!preview.isEmpty)
    }

    @Test
    fun allThreeTopLevelKeysAreOptional() {
        val noKeys = NewPipeSubscriptionsImporter.parse("{}", NOW)
        assertTrue(noKeys.isEmpty)
        assertEquals(ImportReason.NOTHING_FOUND, noKeys.reason)

        val onlySubscriptions = NewPipeSubscriptionsImporter.parse("""{"subscriptions":[{"url":"https://www.youtube.com/channel/$C0"}]}""", NOW)
        assertEquals(listOf(C0), onlySubscriptions.subscriptions.map { it.channelId }, "no service_id: read by its address")
        assertEquals(C0, onlySubscriptions.subscriptions.single().name, "no name: the id")

        val noSubscriptions = NewPipeSubscriptionsImporter.parse("""{"app_version":"0.1","app_version_int":1}""", NOW)
        assertEquals(ImportReason.NOTHING_FOUND, noSubscriptions.reason)
    }

    @Test
    fun anEntryThatIsNotUsableCountsAsSkipped() {
        val json = """{"subscriptions":[1, {"service_id":0}, {"service_id":0,"url":"not an address"}, {"service_id":"3","url":"https://www.youtube.com/channel/$C0"}, {"service_id":0,"url":"https://www.youtube.com/channel/UCtooshort"}]}"""
        val preview = NewPipeSubscriptionsImporter.parse(json, NOW)
        assertTrue(preview.subscriptions.isEmpty())
        assertEquals(5, preview.skipped)
    }

    @Test
    fun aBrokenFileIsRefused() {
        assertEquals(ImportReason.UNREADABLE, NewPipeSubscriptionsImporter.parse("not json at all", NOW).reason)
        assertEquals(ImportReason.UNREADABLE, NewPipeSubscriptionsImporter.parse("[]", NOW).reason)
        assertEquals(ImportReason.UNREADABLE, NewPipeSubscriptionsImporter.parse("", NOW).reason)
    }

    @Test
    fun aByteOrderMarkIsAccepted() {
        val preview = NewPipeSubscriptionsImporter.parse("\uFEFF" + ImportFixtures.text("newpipe-subscriptions.json"), NOW)
        assertEquals(4, preview.subscriptions.size)
    }
}
