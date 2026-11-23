package com.cosmotune.video.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoOpenLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun theAddOnsLinkCarriesTheAddress() {
        assertEquals(OpenVideo(id, 42_000), VideoOpenLinks.parse("cosmotune://video?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$id%26t%3D42s"))
        assertEquals(OpenVideo(id, null), VideoOpenLinks.parse("cosmotune://video?v=$id"))
    }

    @Test fun youTubeAddressesOpenToo() {
        assertEquals(OpenVideo(id, null), VideoOpenLinks.parse("https://youtu.be/$id"))
        assertEquals(OpenVideo(id, 3_723_000), VideoOpenLinks.parse("https://m.youtube.com/watch?v=$id&t=1h2m3s"))
        assertEquals(OpenVideo(id, null), VideoOpenLinks.parse("https://www.youtube.com/shorts/$id"))
        assertEquals(OpenVideo(id, null), VideoOpenLinks.parse("www.youtube.com/live/$id?si=x"))
    }

    @Test fun otherLinksAreNotVideos() {
        assertNull(VideoOpenLinks.parse("cosmotune://watch/pack?d=x"))
        assertNull(VideoOpenLinks.parse("https://music.youtube.com/watch?v=$id"))
        assertNull(VideoOpenLinks.parse("https://www.youtube.com/watch?v=short"))
        assertNull(VideoOpenLinks.parse("stremio://addon"))
        assertNull(VideoOpenLinks.parse("file:///C:/x.mp4"))
    }
}
