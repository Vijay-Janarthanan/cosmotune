package com.cosmotune.video.host

import com.cosmotune.video.ui.PendingVideo
import com.cosmotune.music.playback.LinkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which host serves a link that arrives while Video is on screen (plan task Y-3, step 2): a link to one video is Video's, a page, a
 * search and "play music" are Music's.
 */
class PhoneLinkRoutingTest {

    @Test
    fun aLinkToOneVideoIsOpenedByVideoAtItsStart() {
        assertEquals(PendingVideo("dQw4w9WgXcQ", null), videoOf(LinkRequest.Track("dQw4w9WgXcQ")))
    }

    @Test
    fun aLinkThatIsNotOneVideoIsNothingForVideoToOpen() {
        assertNull(videoOf(null))
        assertNull(videoOf(LinkRequest.Page("VLPL123")))
        assertNull(videoOf(LinkRequest.Search("blinding lights", play = true)))
        assertNull(videoOf(LinkRequest.Resume))
    }

    @Test
    fun aPageASearchAndPlayMusicBelongToMusicSoTheHostLeavesVideo() {
        assertTrue(belongsToMusic(LinkRequest.Page("MPREb_abc")))
        assertTrue(belongsToMusic(LinkRequest.Search("lofi", play = false)))
        assertTrue(belongsToMusic(LinkRequest.Search("lofi", play = true)))
        assertTrue(belongsToMusic(LinkRequest.Resume))
    }

    @Test
    fun aVideoLinkAndNoLinkDoNotTakeVideoAwayFromTheUser() {
        assertFalse(belongsToMusic(LinkRequest.Track("dQw4w9WgXcQ")))
        assertFalse(belongsToMusic(null))
    }

    @Test
    fun everyRequestIsVideosOrMusicsAndNeverBoth() {
        val all = listOf(
            LinkRequest.Track("a"),
            LinkRequest.Page("b"),
            LinkRequest.Search("c", play = false),
            LinkRequest.Search("c", play = true),
            LinkRequest.Resume,
        )
        for (request in all) {
            assertTrue("$request is exactly one host's", (videoOf(request) != null) != belongsToMusic(request))
        }
    }
}
