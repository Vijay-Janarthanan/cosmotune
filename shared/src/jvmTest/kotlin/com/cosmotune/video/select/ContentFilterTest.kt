package com.cosmotune.video.select

import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentFilterTest {
    private fun video(title: String, channel: String? = "UC1") =
        VideoItem("v", title, channel, "c", null, null, null, null, null, null)

    private fun comment(text: String, likes: Long? = 5, author: String = "Ana", channel: String? = "UCa", pinned: Boolean = false) =
        Comment("c", author, channel, null, text, likes, null, pinned, false, false, false, 0, null)

    @Test fun aWordMatchesAsAWholeWordInAnyCase() {
        val filter = ContentFilter(listOf("spoiler"), emptySet())
        assertFalse(filter.keep(video("Big SPOILER inside")))
        assertTrue(filter.keep(video("Spoilers ahead")), "a longer word is a different word")
    }

    @Test fun aSlashedWordIsARegularExpression() {
        val filter = ContentFilter(listOf("/react(s|ion)/"), emptySet())
        assertFalse(filter.keep(video("My reaction to it")))
        assertTrue(filter.keep(video("React")))
    }

    @Test fun aBrokenExpressionMatchesNothingAndIsReported() {
        val filter = ContentFilter(listOf("/(oops/"), emptySet())
        assertTrue(filter.keep(video("(oops")))
        assertTrue(ContentFilter.isBrokenPattern("/(oops/"))
        assertFalse(ContentFilter.isBrokenPattern("plain"))
    }

    @Test fun aHiddenChannelHidesItsVideosAndComments() {
        val filter = ContentFilter(emptyList(), setOf("UC1", "UCa"))
        assertFalse(filter.keep(video("Anything", "UC1")))
        assertTrue(filter.keep(video("Anything", "UC2")))
        assertFalse(filter.keepComment(comment("hi")))
    }

    @Test fun commentsUnderTheLikeFloorAreHiddenButPinnedOnesStay() {
        val filter = ContentFilter(emptyList(), emptySet(), commentMinLikes = 10)
        assertFalse(filter.keepComment(comment("hi", likes = 3)))
        assertFalse(filter.keepComment(comment("hi", likes = null)))
        assertTrue(filter.keepComment(comment("hi", likes = 12)))
        assertTrue(filter.keepComment(comment("hi", likes = 0, pinned = true)))
    }

    @Test fun wordsAlsoMatchACommentsAuthor() {
        val filter = ContentFilter(listOf("bot"), emptySet())
        assertFalse(filter.keepComment(comment("great", author = "Free bot")))
    }

    @Test fun filterKeepsEntriesThatAreNoVideo() {
        val filter = ContentFilter(listOf("x"), emptySet())
        val items = listOf<Any>("a channel", video("x marks"), video("fine"))
        assertEquals(2, filter.filter(items) { it as? VideoItem }.size)
    }

    @Test fun wordsAreCleaned() {
        assertEquals(listOf("Cat", "dog"), ContentFilter.cleanWords(listOf(" Cat ", "", "cat", "dog")))
    }

    @Test fun membersOnlyVideosGoWhenAsked() {
        val paid = com.cosmotune.video.model.VideoItem("p", "Behind the scenes", "UCx", "X", null, null, 60, null, null, null, isPaid = true)
        val free = paid.copy(id = "f", isPaid = false)
        val filter = ContentFilter(emptyList(), emptySet(), hidePaid = true)
        kotlin.test.assertFalse(filter.keep(paid))
        kotlin.test.assertTrue(filter.keep(free))
        kotlin.test.assertTrue(ContentFilter.NONE.keep(paid))
        kotlin.test.assertFalse(com.cosmotune.video.settings.VideoSettings(hidePaid = true).contentFilter().isEmpty)
    }
}
