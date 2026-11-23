package com.cosmotune.ui.library

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryGroupsTest {
    private fun song(id: String) = Song(videoId = id, title = id, artist = "A", thumbnailUrl = null)
    private val songs = listOf(song("a1234567890"), song("b1234567890"), song("c1234567890"), song("d1234567890"), song("e1234567890"))
    private val days = mapOf("a1234567890" to "Today", "b1234567890" to "Yesterday", "c1234567890" to "This week", "d1234567890" to "Last week", "e1234567890" to "October")

    @Test fun daysGatherIntoWeeksAndMonths() {
        assertEquals(listOf("Today", "Yesterday", "This week", "Last week", "October"), historyGroups(songs, days).map { it.label })
        assertEquals(listOf("This week" to 3, "Last week" to 1, "October" to 1), historyGroups(songs, days, HistorySpan.WEEKS).map { it.label to it.count })
        assertEquals(listOf("The last few weeks" to 4, "October" to 1), historyGroups(songs, days, HistorySpan.MONTHS).map { it.label to it.count })
    }

    @Test fun eachSongSaysWhereItCameFrom() {
        val mixed = listOf(song("a1234567890"), song("local:/music/x.flac"), song("podio:station:1"))
        assertEquals(listOf(HistorySource.YOUTUBE, HistorySource.DEVICE, HistorySource.RADIO), mixed.map(HistorySource::of))
        assertEquals(listOf(HistorySource.YOUTUBE, HistorySource.DEVICE, HistorySource.RADIO), HistorySource.present(mixed))
    }
}
