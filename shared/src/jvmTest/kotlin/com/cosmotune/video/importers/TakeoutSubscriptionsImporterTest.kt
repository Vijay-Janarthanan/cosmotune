package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.C0
import com.cosmotune.video.importers.ImportFixtures.C1
import com.cosmotune.video.importers.ImportFixtures.C2
import com.cosmotune.video.importers.ImportFixtures.C3
import com.cosmotune.video.importers.ImportFixtures.C4
import com.cosmotune.video.importers.ImportFixtures.C5
import com.cosmotune.video.importers.ImportFixtures.NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TakeoutSubscriptionsImporterTest {
    @Test
    fun readsTheCsvByPositionAndSkipsTheHeader() {
        val preview = TakeoutSubscriptionsImporter.parseCsv(ImportFixtures.text("takeout-subscriptions.csv"), NOW)
        assertEquals(listOf(C0, C1, C2, C3), preview.subscriptions.map { it.channelId })
        assertEquals(
            listOf("Northern Lights Workshop", "Paper, Scissors & Stone", "Cooking \"Slow\", Daily", "Tëst Chännel 中文"),
            preview.subscriptions.map { it.name },
            "titles with commas and quotes come through the CSV reader whole",
        )
        assertTrue(preview.subscriptions.all { it.subscribedAtMs == NOW && it.avatarUrl == null })
        assertEquals(0, preview.skipped)
        assertNull(preview.reason)
    }

    @Test
    fun theHeaderIsSkippedWhateverItsLanguageAndAByteOrderMarkIsHarmless() {
        val preview = TakeoutSubscriptionsImporter.parseCsv(ImportFixtures.text("takeout-subscriptions-es.csv"), NOW)
        assertEquals(listOf(C4, C5), preview.subscriptions.map { it.channelId })
        assertEquals(listOf("Coleccionistas, S.L.", "Café y Cuentos"), preview.subscriptions.map { it.name })
        assertEquals(0, preview.skipped)
    }

    @Test
    fun aFileWithoutAHeaderLosesNothing() {
        val preview = TakeoutSubscriptionsImporter.parseCsv("$C0,http://www.youtube.com/channel/$C0,One\r\n$C1,http://www.youtube.com/channel/$C1,Two\r\n", NOW)
        assertEquals(listOf(C0, C1), preview.subscriptions.map { it.channelId })
    }

    @Test
    fun aRowThatIsNotAChannelCountsAsSkippedAndAMissingTitleFallsBackToTheId() {
        val text = "Channel Id,Channel Url,Channel Title\r\n$C0\r\nnot-an-id,http://x,Bad\r\n$C0,http://x,Again\r\n\r\n$C1,http://x,\r\n"
        val preview = TakeoutSubscriptionsImporter.parseCsv(text, NOW)
        assertEquals(listOf(C0, C1), preview.subscriptions.map { it.channelId })
        assertEquals(listOf(C0, C1), preview.subscriptions.map { it.name }, "no title: the id")
        assertEquals(2, preview.skipped, "the bad id and the repeated channel")
    }

    @Test
    fun aFileWithNoChannelsGivesAReason() {
        assertEquals(ImportReason.UNREADABLE, TakeoutSubscriptionsImporter.parseCsv("", NOW).reason)
        assertEquals(ImportReason.NOTHING_FOUND, TakeoutSubscriptionsImporter.parseCsv("Channel Id,Channel Url,Channel Title\r\n", NOW).reason)
        assertEquals(ImportReason.NOTHING_FOUND, TakeoutSubscriptionsImporter.parseCsv("a,b,c\r\n1,2,3\r\n", NOW).reason)
    }

    @Test
    fun readsTheOlderJsonArrayAndTakesTheChannelFollowedNotTheAccountsOwn() {
        val preview = TakeoutSubscriptionsImporter.parseJson(ImportFixtures.text("takeout-subscriptions-old.json"), NOW)
        assertEquals(listOf(C0, C1), preview.subscriptions.map { it.channelId }, "snippet.resourceId.channelId, never snippet.channelId")
        assertEquals(listOf("Northern Lights Workshop", "Paper, Scissors & Stone"), preview.subscriptions.map { it.name })
        assertEquals(
            listOf(ImportFixtures.ms("2020-11-01T17:24:34.498Z"), ImportFixtures.ms("2021-02-03T04:05:06Z")),
            preview.subscriptions.map { it.subscribedAtMs },
            "snippet.publishedAt is when the channel was followed",
        )
        assertEquals("https://images.example/$C0/high.jpg", preview.subscriptions[0].avatarUrl)
        assertEquals(1, preview.skipped, "the object without a resource id")
    }

    @Test
    fun theJsonReaderRefusesWhatIsNotAnArray() {
        assertEquals(ImportReason.UNREADABLE, TakeoutSubscriptionsImporter.parseJson("{}", NOW).reason)
        assertEquals(ImportReason.UNREADABLE, TakeoutSubscriptionsImporter.parseJson("nope", NOW).reason)
        assertEquals(ImportReason.NOTHING_FOUND, TakeoutSubscriptionsImporter.parseJson("[]", NOW).reason)
    }

    private fun csv(vararg channels: Pair<String, String>): ByteArray =
        ("Channel Id,Channel Url,Channel Title\r\n" + channels.joinToString("") { "${it.first},http://www.youtube.com/channel/${it.first},${it.second}\r\n" }).encodeToByteArray()

    @Test
    fun theWholeTakeoutZipYieldsTheChannelsOfTheSubscriptionsFolder() {
        val zip = ImportFixtures.zip(
            "Takeout/archive_browser.html" to ByteArray(0),
            "Takeout/YouTube and YouTube Music/playlists/Road trip-videos.csv" to ImportFixtures.bytes("playlist-current.csv"),
            "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv" to csv(C0 to "One", C1 to "Two"),
        )
        val preview = TakeoutSubscriptionsImporter.parseZip(ZipInput(zip), NOW)
        assertEquals(listOf(C0, C1), preview.subscriptions.map { it.channelId })
        assertEquals(listOf("One", "Two"), preview.subscriptions.map { it.name })
    }

    @Test
    fun aFolderNamedInAnotherLanguageIsFoundToo() {
        val zip = ImportFixtures.zip(
            "Takeout/YouTube y YouTube Music/suscripciones/suscripciones.csv" to ImportFixtures.bytes("takeout-subscriptions-es.csv"),
        )
        assertEquals(listOf(C4, C5), TakeoutSubscriptionsImporter.parseZip(ZipInput(zip), NOW).subscriptions.map { it.channelId })
    }

    @Test
    fun anyCsvThatYieldsChannelsIsGoodEnoughWhenNoFolderLooksRight() {
        val zip = ImportFixtures.zip(
            "Takeout/Something/other.csv" to "a,b\r\n1,2\r\n".encodeToByteArray(),
            "Takeout/Something/list.csv" to csv(C2 to "Three"),
            "__MACOSX/Takeout/Something/._list.csv" to csv(C0 to "Junk"),
        )
        assertEquals(listOf(C2), TakeoutSubscriptionsImporter.parseZip(ZipInput(zip), NOW).subscriptions.map { it.channelId })
    }

    @Test
    fun theFolderThatLooksRightIsTriedFirst() {
        val zip = ImportFixtures.zip(
            "Takeout/Misc/aaa.csv" to csv(C0 to "Not this one"),
            "Takeout/YouTube/subscriptions/subscriptions.csv" to csv(C1 to "This one"),
        )
        assertEquals(listOf(C1), TakeoutSubscriptionsImporter.parseZip(ZipInput(zip), NOW).subscriptions.map { it.channelId })
    }

    @Test
    fun aZipWithNoChannelsGivesAReason() {
        val zip = ImportFixtures.zip("Takeout/YouTube/playlists/List-videos.csv" to ImportFixtures.bytes("playlist-current.csv"))
        val preview = TakeoutSubscriptionsImporter.parseZip(ZipInput(zip), NOW)
        assertTrue(preview.isEmpty)
        assertEquals(ImportReason.NOTHING_FOUND, preview.reason)
    }

    @Test
    fun theOlderJsonInsideAZipIsReadWhenNoCsvHasChannels() {
        val zip = ImportFixtures.zip("Takeout/YouTube/subscriptions/subscriptions.json" to ImportFixtures.bytes("takeout-subscriptions-old.json"))
        assertEquals(listOf(C0, C1), TakeoutSubscriptionsImporter.parseZip(ZipInput(zip), NOW).subscriptions.map { it.channelId })
    }
}
