package com.cosmotune.video.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Finding the subtitle files that belong to a video by name. */
class LocalSubtitlesTest {

    @Test
    fun theFileWithTheVideosNameComesFirstThenTheLanguagesByName() {
        val found = LocalSubtitles.siblings(
            "Movie.mkv",
            listOf("Movie.fr.srt", "Movie.srt", "Movie.en.srt", "Movie.mkv", "notes.txt", "Other.srt"),
        )
        assertEquals(listOf("Movie.srt", "Movie.en.srt", "Movie.fr.srt"), found.map { it.fileName })
        assertEquals(listOf("und", "en", "fr"), found.map { it.language })
    }

    @Test
    fun theNamesAreComparedWithoutRegardToCaseAndAssOrVttAreSubtitlesToo() {
        val found = LocalSubtitles.siblings("clip.MP4", listOf("CLIP.EN.VTT", "clip.pt-BR.ass", "clip.ssa"))
        assertEquals(setOf("en", "pt-BR", "und"), found.map { it.language }.toSet())
    }

    @Test
    fun aNameThatOnlyStartsLikeTheVideosIsNotItsSubtitle() {
        assertTrue(LocalSubtitles.siblings("Movie.mkv", listOf("Movie 2.srt", "Movies.srt", "Movie-en.srt", "Movie.x.srt", "Mov.srt")).isEmpty())
    }

    @Test
    fun onlyTheFirstFileOfALanguageIsKept() {
        val found = LocalSubtitles.siblings("a.mp4", listOf("a.en.srt", "a.en.vtt", "a.en.forced.srt"))
        assertEquals(1, found.size)
        assertEquals("en", found.single().language)
    }

    @Test
    fun aVideoWithNoNameHasNoSubtitles() {
        assertTrue(LocalSubtitles.siblings("", listOf("a.srt")).isEmpty())
    }

    @Test
    fun aPickedFileGivesItsLanguageFromItsNameElseUndetermined() {
        assertEquals("en", LocalSubtitles.forPickedFile("Movie.en.srt").language)
        assertEquals("pt-BR", LocalSubtitles.forPickedFile("Movie.pt_BR.srt").language)
        val plain = LocalSubtitles.forPickedFile("Movie.srt")
        assertEquals(LocalSubtitles.UNDETERMINED, plain.language)
        assertEquals("Movie.srt", plain.label, "a file with no language is shown by its name")
        assertEquals("und", LocalSubtitles.forPickedFile("12345.srt").language, "a number is not a language")
    }
}
