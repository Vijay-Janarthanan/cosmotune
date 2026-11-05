package com.cosmotune.video.segments

import com.cosmotune.video.settings.TitleCase
import kotlin.test.Test
import kotlin.test.assertEquals

/** DeArrow titles (spec 7.2): the `>` markers come out, and the title-case setting is applied. */
class DeArrowTitlesTest {

    private fun asGiven(s: String) = DeArrowTitles.clean(s, TitleCase.AS_GIVEN)
    private fun sentence(s: String) = DeArrowTitles.clean(s, TitleCase.SENTENCE)
    private fun title(s: String) = DeArrowTitles.clean(s, TitleCase.TITLE)

    // ---- the markers ----

    @Test
    fun aMarkerAtTheStartOrAfterWhitespaceIsRemoved() {
        assertEquals("Hello World", asGiven("Hello >World"))
        assertEquals("Hello world", asGiven(">Hello world"))
        assertEquals("a b c", asGiven("a >b >c"))
        assertEquals("a\tb\nc", asGiven("a\t>b\n>c"))
    }

    @Test
    fun aMarkerNotBeforeAWordIsLeftAlone() {
        assertEquals("x > y", asGiven("x > y"), "a lone > is not a marker")
        assertEquals("a>b", asGiven("a>b"), "not preceded by whitespace")
        assertEquals("(>b)", asGiven("(>b)"))
        assertEquals("5 > 3", asGiven("5 > 3"))
        assertEquals(">", asGiven(">"))
        assertEquals("tail >", asGiven("tail >"))
    }

    @Test
    fun aDoubledMarkerLosesOne() {
        assertEquals(">x", asGiven(">>x"), "the pattern takes one > and the character after it")
    }

    @Test
    fun theMarkerGoesInEveryCase() {
        for (case in TitleCase.entries) {
            assertEquals("iPhone 17 review", DeArrowTitles.clean("iPhone 17 >review", case), "$case")
            assertEquals("", DeArrowTitles.clean("", case), "$case")
        }
    }

    @Test
    fun asGivenChangesNothingElse() {
        assertEquals("THE   WORLD'S best  chili", asGiven("THE   WORLD'S best  chili"))
        assertEquals("  padded ", asGiven("  padded "))
    }

    // ---- sentence case ----

    @Test
    fun sentenceCaseLowercasesAllCapsAndTitleCase() {
        assertEquals("The world's hottest chili", sentence("THE WORLD'S HOTTEST CHILI"))
        assertEquals("How to build a gaming PC", sentence("How To Build A Gaming PC"))
        assertEquals("First part. Second part! Third part? Fourth part", sentence("first part. second part! third part? fourth part"))
    }

    @Test
    fun anAllCapsTitleHasNoAcronymsToProtect() {
        assertEquals("I tried the world's hottest chili", sentence("I TRIED THE WORLD'S HOTTEST CHILI"))
        assertEquals("Nasa launches new rocket", sentence("NASA LAUNCHES NEW ROCKET"))
    }

    @Test
    fun allCapsWordsOfTwoToFiveLettersAreLeftAlone() {
        assertEquals("NASA launches the NEW rocket", sentence("NASA launches the NEW rocket"))
        assertEquals("Why USA and EU differ", sentence("why USA and EU differ"))
        assertEquals("Build a PC with AMD", sentence("Build a PC with AMD"))
        assertEquals("This is hugely good", sentence("this is HUGELY good"), "six letters is a shout")
        assertEquals("This is GOOD", sentence("this is GOOD"), "four letters is still an acronym")
    }

    @Test
    fun aSingleCapitalLetterIsKeptExceptTheArticle() {
        assertEquals("Which is bigger, a or B", sentence("which is bigger, A or B"))
        assertEquals("Vitamin D and plan B", sentence("VITAMIN D AND PLAN B"))
    }

    @Test
    fun anAcronymKeepsItsCaseAroundPunctuationAndPossessives() {
        assertEquals("NASA's new rocket", sentence("NASA's new rocket"))
        assertEquals("Why (USA) matters", sentence("why (USA) matters"))
        assertEquals("Ask the FBI: what happened", sentence("ask the FBI: what happened"))
    }

    @Test
    fun wordsWithDigitsStayAsTyped() {
        assertEquals("The new 4K PS5 review", sentence("THE NEW 4K PS5 REVIEW"))
        assertEquals("Testing the RTX4090 and 8GB cards", sentence("testing the RTX4090 and 8GB cards"))
        assertEquals("3rd place finish", sentence("3rd Place Finish"), "a digit does not start a sentence")
    }

    @Test
    fun brandNamesWithInnerCapitalsAreKept() {
        assertEquals("Why YouTube and eBay love iOS", sentence("Why YouTube And eBay Love iOS"))
        assertEquals("McDonald's new burger", sentence("McDonald's New Burger"))
    }

    @Test
    fun theWordIStaysCapital() {
        assertEquals("I think I'm right. But I'm NOT sure", sentence("I think I'm right. but I'm NOT sure"))
        assertEquals("Why I left, and why I'd return", sentence("WHY I LEFT, AND WHY I'D RETURN"))
    }

    @Test
    fun aSeparatorStartsANewSentence() {
        assertEquals("iPhone 17 review | The best phone", sentence("iPhone 17 review | the best phone"))
        assertEquals("Artist - The song", sentence("artist - the song"))
        assertEquals("Part one – The start", sentence("part one – the start"))
    }

    @Test
    fun aLeadingQuoteOrBracketDoesNotHideTheFirstLetter() {
        assertEquals("\"Hello world\" says everyone", sentence("\"hello world\" says everyone"))
        assertEquals("(Official video)", sentence("(official video)"))
    }

    @Test
    fun hyphenatedWordsAreLowercasedWhole() {
        assertEquals("Well-known facts", sentence("WELL-KNOWN FACTS"))
        assertEquals("A well-known fact", sentence("A Well-Known Fact"))
    }

    @Test
    fun textWithoutCaseIsLeftAlone() {
        assertEquals("日本語のタイトル", sentence("日本語のタイトル"))
        assertEquals("🔥 Best phones 2026", sentence("🔥 best phones 2026"))
        assertEquals("12345", sentence("12345"))
    }

    @Test
    fun whitespaceIsKeptAsItIs() {
        assertEquals("Hello   world", sentence("hello   world"))
        assertEquals(" Hello ", sentence(" hello "))
    }

    // ---- words after the marker ----

    @Test
    fun aWordAfterTheMarkerIsLeftAloneAndTheMarkerGoes() {
        assertEquals("Review of the HELLOWORLD deal and helloworld", sentence("review of the >HELLOWORLD deal and HELLOWORLD"))
        assertEquals("My iPhone Is fine", title("my >iPhone is >fine"))
    }

    @Test
    fun aMarkerProtectsOnlyTheWordItIsBefore() {
        assertEquals("Keep KEEPTHIS but fixthis", sentence("keep >KEEPTHIS but FIXTHIS"))
    }

    // ---- title case ----

    @Test
    fun titleCaseCapitalisesWordsButNotTheSmallOnes() {
        assertEquals("How to Build a PC in 2026", title("How to build a PC in 2026"))
        assertEquals("The Lord of the Rings: The Return of the King", title("the lord of the rings: the return of the king"))
        assertEquals("Salt and Pepper, or Neither", title("salt and pepper, or neither"))
    }

    @Test
    fun theFirstAndLastWordsAreAlwaysCapitalised() {
        assertEquals("What Are You Waiting For", title("what are you waiting for"))
        assertEquals("A Day in the Life", title("a day in the life"))
        assertEquals("To Be or Not to Be", title("to be or not to be"))
    }

    @Test
    fun titleCaseFixesAnAllCapsTitle() {
        assertEquals("I Tried the World's Hottest Chili", title("I TRIED THE WORLD'S HOTTEST CHILI"))
    }

    @Test
    fun titleCaseHandlesHyphensApostrophesAndBrackets() {
        assertEquals("A Well-Known Fact", title("a well-known fact"))
        assertEquals("Don't Stop", title("don't stop"))
        assertEquals("(Official Video)", title("(official video)"))
        assertEquals("3rd Place", title("3rd place"))
        assertEquals("I'm Here", title("i'm here"))
    }

    @Test
    fun titleCaseKeepsAcronymsAndBrands() {
        assertEquals("Why NASA and YouTube Matter", title("why NASA and YouTube matter"))
        assertEquals("Review: The 4K PS5", title("review: the 4K PS5"))
    }

    @Test
    fun titleCaseCapitalisesAfterAColonOrASeparator() {
        assertEquals("Review: The Best Phone | The Verdict", title("review: the best phone | the verdict"))
    }

    @Test
    fun titleCaseKeepsWhitespace() {
        assertEquals("Two   Words", title("two   words"))
    }
}
