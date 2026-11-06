package com.cosmotune.podio.ui

import com.cosmotune.music.data.model.Song
import com.cosmotune.podio.RadioStation
import com.cosmotune.ui.orbit.topSongLine
import kotlin.test.Test
import kotlin.test.assertEquals

class PodioLocalTest {
    private fun station(id: String, code: String, language: String) = RadioStation(id, id, "https://radio.example/$id", countryCode = code, language = language)

    @Test fun theListenersCountryAndLanguageComeFirstEachInTheDirectorysOrder() {
        val directory = listOf(
            station("RTL", "FR", "french"), station("BBC", "GB", "english"), station("Vividh", "IN", "hindi"),
            station("Sirpi", "IN", "tamil"), station("Tamil Canada", "CA", "tamil,english"), station("Suryan", "IN", "Tamil"),
        )
        assertEquals(listOf("Sirpi", "Suryan", "Vividh", "Tamil Canada", "RTL", "BBC"), PodioLocal.rank(directory, PodioPlace("IN", "Tamil")).map { it.id })
        assertEquals(directory, PodioLocal.rank(directory, PodioPlace("", "")), "no place: the directory's order")
    }

    @Test fun aTopSongsSecondLineNeverRepeatsItsTitle() {
        val single = Song("a", "Ala Bolelo (From \"Jailer 2\")", "Anirudh Ravichander", null, albumName = "Ala Bolelo (From \"Jailer 2\")")
        assertEquals("Single · Anirudh Ravichander", topSongLine(single))
        val track = Song("b", "Bindaas", "Anirudh Ravichander", null, albumName = "Jailer 2 (Original Motion Picture Soundtrack)")
        assertEquals("Jailer 2 (Original Motion Picture Soundtrack)", topSongLine(track))
        assertEquals("Single", topSongLine(Song("c", "Anirudh", "Anirudh", null)))
    }
}
