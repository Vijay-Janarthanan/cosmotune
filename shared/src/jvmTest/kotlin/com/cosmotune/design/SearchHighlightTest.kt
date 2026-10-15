package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** The bold typed part of a suggestion (`searchTyping`, `screens-more-a.js:77-79`: the typed letters in 700, the rest regular). */
class SearchHighlightTest {

    @Test
    fun theTypedPrefixIsSplitFromTheRest() {
        assertEquals("maz" to "hai", SearchHighlight.split("mazhai", "maz"))
        assertEquals("maz" to "haikku pin", SearchHighlight.split("mazhaikku pin", "maz"))
    }

    @Test
    fun theMatchIgnoresCaseButKeepsTheSuggestionsOwnLetters() {
        assertEquals("Maz" to "hai", SearchHighlight.split("Mazhai", "mAZ"))
        assertEquals("maz" to "e runner soundtrack", SearchHighlight.split("maze runner soundtrack", "MAZ"))
    }

    @Test
    fun theTypedTextIsTrimmedFirst() {
        assertEquals("maz" to "hai", SearchHighlight.split("mazhai", "  maz "))
        assertEquals("maz" to "hai songs tamil", SearchHighlight.split("mazhai songs tamil", "maz\t"))
    }

    @Test
    fun aSpaceInsideTheTypedTextCounts() {
        // a trailing space is trimmed, so only "rain" is bold; a space between two words stays in the match
        assertEquals("rain" to " songs", SearchHighlight.split("rain songs", "rain "))
        assertEquals("rain s" to "ongs", SearchHighlight.split("rain songs", "rain s"))
    }

    @Test
    fun theWholeSuggestionTypedIsAllBold() {
        assertEquals("mazhai" to "", SearchHighlight.split("mazhai", "mazhai"))
        assertEquals("Mazhai" to "", SearchHighlight.split("Mazhai", "mazhai "))
    }

    @Test
    fun noMatchGivesNothingBoldAndTheWholeSuggestion() {
        assertEquals("" to "mazhai", SearchHighlight.split("mazhai", "rain"))
    }

    @Test
    fun aMatchInTheMiddleIsNotAPrefix() {
        assertEquals("" to "tamil mazhai", SearchHighlight.split("tamil mazhai", "maz"))
    }

    @Test
    fun typedTextLongerThanTheSuggestionDoesNotMatch() {
        assertEquals("" to "maz", SearchHighlight.split("maz", "mazhai"))
    }

    @Test
    fun nothingTypedGivesNothingBold() {
        assertEquals("" to "mazhai", SearchHighlight.split("mazhai", ""))
        assertEquals("" to "mazhai", SearchHighlight.split("mazhai", "   "))
    }

    @Test
    fun anEmptySuggestionStaysEmpty() {
        assertEquals("" to "", SearchHighlight.split("", "maz"))
        assertEquals("" to "", SearchHighlight.split("", ""))
    }

    @Test
    fun otherScriptsSplitOnTheirOwnLetters() {
        assertEquals("மழை" to " பாடல்கள்", SearchHighlight.split("மழை பாடல்கள்", "மழை"))
        assertEquals("" to "மழை பாடல்கள்", SearchHighlight.split("மழை பாடல்கள்", "பாடல்"))
    }

    @Test
    fun thePartsAlwaysAddUpToTheSuggestion() {
        for ((suggestion, typed) in listOf("mazhai" to "ma", "Mazhai" to "MA", "x" to "y", "" to "a", "été" to "ÉT", "abc" to " ")) {
            val (bold, rest) = SearchHighlight.split(suggestion, typed)
            assertEquals(suggestion, bold + rest, "split of \"$suggestion\" for \"$typed\"")
        }
    }
}
