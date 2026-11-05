package com.cosmotune.video.select

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Hide Shorts filter (plan task Y-16, step 1; spec 7.4): which items are Shorts, and what a list keeps. */
class ShortsFilterTest {
    private fun item(
        id: String = "abcdefghijk",
        durationSec: Long? = 600,
        thumbnailUrl: String? = "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg",
        isShort: Boolean = false,
    ) = VideoItem(
        id = id, title = "A title", channelId = "UC0000000000000000000001", channelName = "A channel", channelAvatarUrl = null,
        thumbnailUrl = thumbnailUrl, durationSec = durationSec, viewCount = 10, uploadedAtMs = null, uploadedText = null, isShort = isShort,
    )

    @Test fun anItemTheSourceMarksIsAShortWhateverItsLengthAndPicture() {
        assertTrue(ShortsFilter.isShort(item(isShort = true)))
        assertTrue(ShortsFilter.isShort(item(isShort = true, durationSec = null, thumbnailUrl = null)))
        assertFalse(ShortsFilter.keep(item(isShort = true), hideShorts = true))
    }

    @Test fun aMinuteOrLessWithAShortsAddressInThePictureIsAShort() {
        val short = item(durationSec = 45, thumbnailUrl = "https://i.ytimg.com/shorts/abcdefghijk/frame0.jpg")
        assertTrue(ShortsFilter.isShort(short))
        assertFalse(ShortsFilter.keep(short, hideShorts = true))
    }

    @Test fun aMinuteOrLessWithAVerticalPictureNameIsAShort() {
        assertTrue(ShortsFilter.isShort(item(durationSec = 30, thumbnailUrl = "https://i.ytimg.com/vi/abcdefghijk/oardefault.jpg")))
        assertTrue(ShortsFilter.isShort(item(durationSec = 60, thumbnailUrl = "https://i.ytimg.com/vi/abcdefghijk/oar2.jpg?sqp=x")))
        assertTrue(ShortsFilter.isShort(item(durationSec = 1, thumbnailUrl = "https://i.ytimg.com/vi/abcdefghijk/OARDEFAULT.jpg")))
    }

    @Test fun lengthAloneOrPictureAloneIsNotEnough() {
        assertFalse(ShortsFilter.isShort(item(durationSec = 45)), "a short video with an ordinary picture is a video")
        assertFalse(ShortsFilter.isShort(item(durationSec = 61, thumbnailUrl = "https://i.ytimg.com/vi/abcdefghijk/oardefault.jpg")), "61 s is over the minute")
        assertFalse(ShortsFilter.isShort(item(durationSec = 0, thumbnailUrl = "https://i.ytimg.com/vi/abcdefghijk/oar2.jpg")), "no length known")
        assertFalse(ShortsFilter.isShort(item(durationSec = null, thumbnailUrl = "https://i.ytimg.com/vi/abcdefghijk/oar2.jpg")), "a live stream or a feed item")
        assertFalse(ShortsFilter.isShort(item(durationSec = 30, thumbnailUrl = null)))
    }

    @Test fun withTheSwitchOffEverythingIsKept() {
        val short = item(isShort = true)
        assertTrue(ShortsFilter.keep(short, hideShorts = false))
        assertTrue(ShortsFilter.keep(item(), hideShorts = false))
        assertTrue(ShortsFilter.keep(item(), hideShorts = true), "a video is kept with the switch on")
    }

    @Test fun aListLosesItsShortsWhenTheSwitchIsOnAndKeepsItsOrder() {
        val a = item(id = "a")
        val b = item(id = "b", isShort = true)
        val c = item(id = "c", durationSec = 20, thumbnailUrl = "https://i.ytimg.com/vi/c/oar2.jpg")
        val d = item(id = "d")
        assertEquals(listOf(a, d), ShortsFilter.filter(listOf(a, b, c, d), hideShorts = true) { it })
    }

    @Test fun withTheSwitchOffTheListIsReturnedAsItIs() {
        val list = listOf(item(id = "a"), item(id = "b", isShort = true))
        assertSame(list, ShortsFilter.filter(list, hideShorts = false) { it })
        val none = listOf(item(id = "a"), item(id = "b"))
        assertSame(none, ShortsFilter.filter(none, hideShorts = true) { it }, "nothing removed: the same list, so nothing recomposes")
    }

    @Test fun entriesThatAreNoVideoAreKept() {
        val channel = ListItem.Channel(ChannelItem("UC0000000000000000000002", "A channel", null, null, null, null))
        val short = ListItem.Video(item(id = "s", isShort = true))
        val video = ListItem.Video(item(id = "v"))
        val kept = ShortsFilter.filter(listOf(channel, short, video), hideShorts = true) { (it as? ListItem.Video)?.item }
        assertEquals(listOf(channel, video), kept)
    }
}
