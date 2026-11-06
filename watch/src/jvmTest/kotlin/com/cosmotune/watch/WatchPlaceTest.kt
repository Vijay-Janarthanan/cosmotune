package com.cosmotune.watch

import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugins.archive.ArchiveTitles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPlaceTest {
    private fun channel(id: String, name: String, country: String, language: String, plugin: String = "iptv", labels: List<String> = emptyList()) =
        Channel(WatchKey.channel(plugin, id), plugin, name, countries = listOf(country), languages = listOf(language), logoUrl = "https://logo/$id", epgId = id, labels = labels)

    @Test fun channelsOfTheViewersCountryAndLanguageComeFirstNeverAlphabetical() {
        val place = WatchPlace("in", "ta")
        val amp = channel("andTV.in", "&TV", "in", "hin")
        val black = channel("black.pt", ".black", "pt", "por")
        val bbc = channel("BBCOne.uk", "BBC One", "gb", "eng")
        val sun = channel("SunTV.in", "Sun TV", "in", "tam")
        val ktv = channel("KTV.in", "KTV", "in", "tam", labels = listOf("Not 24/7"))
        val tamilAbroad = channel("TamilOne.ca", "Tamil One", "ca", "tam")
        val list = listOf(amp, black, bbc, sun, ktv, tamilAbroad)
        val ordered = list.sortedWith(ChannelRank.order(emptySet(), place) { listOf(it) }).map { it.name }
        assertEquals(listOf("Sun TV", "KTV", "&TV", "Tamil One", "BBC One", ".black"), ordered)
        val withFavourite = list.sortedWith(ChannelRank.order(setOf(bbc.key.value), place) { listOf(it) }).map { it.name }
        assertEquals("BBC One", withFavourite.first(), "favourites stay first")
        assertTrue(WatchPlace("gb", "").isCountry("uk"))
        assertTrue(WatchPlace("in", "ta").isLanguage("tam"))
        assertFalse(WatchPlace.NONE.isCountry("in"))
    }

    @Test fun archiveTitlesLoseYearsAndUploaderNoise() {
        assertEquals("CUKESIM's New Job!" to 1915, ArchiveTitles.clean("CUKESIM's New Job! ( Charles Chaplin-1915 )"))
        assertEquals("His New Job" to null, ArchiveTitles.clean("His New Job ( Charles Chaplin-19"))
        assertEquals("Detour" to 1945, ArchiveTitles.clean("Detour (1945)"))
        assertEquals("Nosferatu" to 1922, ArchiveTitles.clean("Nosferatu [1922] (HD)"))
        assertEquals("His Girl Friday" to 1940, ArchiveTitles.clean("His Girl Friday - 1940"))
        assertEquals("The Little Shop of Horrors" to null, ArchiveTitles.clean("The Little Shop of Horrors - Full Movie"))
        assertEquals("1984" to null, ArchiveTitles.clean("1984"), "a title that is only a year stays")
    }

    @Test fun adultAndExploitationItemsAreKnownByTitleAndTags() {
        assertTrue(ArchiveTitles.adult("Sex Madness", emptyList()))
        assertTrue(ArchiveTitles.adult("Dr. Sex", emptyList()))
        assertTrue(ArchiveTitles.adult("Some Film", listOf("Sexploitation")))
        assertTrue(ArchiveTitles.adult("Some Film", listOf("adult")))
        assertFalse(ArchiveTitles.adult("His Girl Friday", listOf("Comedy", "feature_films")))
        assertFalse(ArchiveTitles.adult("Sussex Story", emptyList()), "whole words only")
    }
}
