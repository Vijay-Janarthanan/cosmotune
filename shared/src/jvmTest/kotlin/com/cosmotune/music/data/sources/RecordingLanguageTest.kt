package com.cosmotune.music.data.sources
import com.cosmotune.music.data.model.Song
import kotlin.test.*
class RecordingLanguageTest {
    private fun row(title: String, album: String? = null) = Song("fixture",title,"Artist",null,albumName=album,durationText="3:00")
    @Test fun matchingTitleArtistAndDurationCannotSubstituteAnotherDub() {
        val target = TrackMatcher.Target("A Song (Hindi Version)","Artist",180)
        assertNotNull(TrackMatcher.score(row("A Song (Hindi Audio)"),target))
        assertNull(TrackMatcher.score(row("A Song (Tamil Version)"),target))
        assertNull(TrackMatcher.score(row("A Song"),target))
        assertTrue(JioSaavnMatcher.ranked(listOf(row("A Song (Tamil Version)")),target).isEmpty())
    }
    @Test fun albumLanguageAndRepriseCannotDisappearDuringMatching() {
        val target = TrackMatcher.Target("A Song","Artist",180)
        val dub = row("A Song","A Film (Telugu Version)")
        assertNull(TrackMatcher.score(dub,target))
        assertTrue(JioSaavnMatcher.ranked(listOf(dub),target).isEmpty())
        assertNotNull(TrackMatcher.score(dub,target.copy(album="A Film (Telugu Version)")))
        assertEquals(listOf(dub),JioSaavnMatcher.ranked(listOf(dub),target.copy(album="A Film (Telugu Version)")))
        assertNull(TrackMatcher.score(row("A Song (Reprise)"),target))
        assertNotNull(TrackMatcher.score(row("A Song (From A Film)"),target))
    }
}
