package com.cosmotune.video.source.newpipe

import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.model.StreamKind
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The adapter on the real network (plan task Y-4, step 7). Skipped unless it is asked for: `-Dvideo.live=true` reaches the
 * test JVM only if the build forwards it (an init script that sets `systemProperty("video.live", ...)` on the test task does),
 * so the environment variable `VIDEO_LIVE=true` is read too. Run by hand, once, and report; never part of a build.
 *
 * It proves what the offline tests cannot: that the extractor still understands the source today, and that Video's calls reach
 * the real `next` endpoint (the related videos of a watch page come from it) while Music's downloader, which answers `next`
 * with an empty envelope, is never asked.
 */
class NewPipeLiveTest {

    private class MusicMustNotBeAsked : Downloader() {
        val asked = mutableListOf<String>()
        override fun execute(request: Request): Response {
            asked += request.url()
            throw AssertionError("Music's downloader was asked for ${request.url()} by a Video call")
        }
    }

    private val music = MusicMustNotBeAsked()

    private fun live(): Boolean = System.getProperty("video.live") == "true" || System.getenv("VIDEO_LIVE") == "true"

    @BeforeTest
    fun setUp() {
        assumeTrue("the live test is opt-in: -Dvideo.live=true (or VIDEO_LIVE=true)", live())
        NewPipeHost.resetForTests()
        NewPipeHost.install(music)
    }

    @AfterTest
    fun tearDown() {
        if (live()) NewPipeHost.resetForTests()
    }

    @Test
    fun theWholeWatchPathWorksAgainstTheRealSource() = runBlocking {
        val source = NewPipeVideoSource()
        val report = StringBuilder()
        fun note(line: String) {
            report.appendLine(line)
            println("LIVE: $line")
        }

        val kiosks = source.kiosks()
        assertEquals(listOf("music", "gaming", "movies", "podcasts", "live"), kiosks.map { it.id })
        note("kiosks: ${kiosks.map { it.id }}")

        val liveKiosk = source.kiosk("live")
        assertTrue(liveKiosk.items.isNotEmpty(), "the live chart has videos")
        note("kiosk live: ${liveKiosk.items.size} videos, next=${liveKiosk.next != null}")

        val search = source.search("kurzgesagt", SearchFilter.ALL)
        assertTrue(search.items.isNotEmpty(), "a search finds something")
        note("search all: ${search.items.size} items (${search.items.count { it is ListItem.Channel }} channels)")
        val suggestions = source.suggestions("kurzges")
        note("suggestions: ${suggestions.take(3)}")

        val details = source.details("jNQXAC9IVRw")
        assertEquals("jNQXAC9IVRw", details.item.id)
        assertEquals("Me at the zoo", details.item.title)
        assertEquals(StreamKind.VIDEO, details.kind)
        assertTrue(details.streams.videoOnly.isNotEmpty() || details.streams.muxed.isNotEmpty(), "streams are not empty")
        assertTrue(details.streams.audio.isNotEmpty(), "audio streams are not empty")
        assertTrue(details.related.isNotEmpty(), "the related videos come from the real next endpoint")
        assertNotNull(details.item.channelId)
        assertNotNull(details.streams.expiresAtMs)
        assertTrue(details.streams.requestHeaders.getValue("User-Agent").isNotBlank())
        note(
            "details: ${details.item.title}, ${details.item.durationSec} s, video-only ${details.streams.videoOnly.size}, " +
                "muxed ${details.streams.muxed.size}, audio ${details.streams.audio.size}, related ${details.related.size}, " +
                "subtitles ${details.subtitles.size}, chapters ${details.chapters.size}, storyboard ${details.storyboard != null}, " +
                "likes ${details.likeCount}, client ${details.streams.requestHeaders["User-Agent"]?.substringBefore(' ')}",
        )
        details.streams.videoOnly.groupBy { it.height }.keys.sortedDescending().let { note("heights: $it") }

        val fresh = source.streams("jNQXAC9IVRw")
        assertTrue(fresh.videoOnly.isNotEmpty() || fresh.muxed.isNotEmpty())

        val channelId = assertNotNull(details.item.channelId)
        val channel = source.channel(channelId)
        assertEquals(channelId, channel.channel.id)
        assertTrue(ChannelTab.VIDEOS in channel.tabs, "the channel has a Videos tab")
        note("channel: ${channel.channel.name}, tabs ${channel.tabs}, subscribers ${channel.channel.subscriberCount}")
        val videos = source.channelTab(channelId, ChannelTab.VIDEOS)
        assertTrue(videos.items.isNotEmpty(), "the Videos tab has videos")
        note("channel videos: ${videos.items.size}, next=${videos.next != null}")

        val comments = source.comments("jNQXAC9IVRw")
        assertFalse(comments.disabled)
        assertTrue(comments.items.isNotEmpty(), "one comments page")
        assertFalse(comments.sortable)
        note("comments: ${comments.items.size}, total ${comments.totalCount}, next=${comments.next != null}")
        comments.items.firstOrNull { it.replies != null }?.let { withReplies ->
            val replies = source.replies(withReplies.replies!!)
            note("replies of ${withReplies.id}: ${replies.items.size}")
        }

        assertTrue(music.asked.isEmpty(), "Music's downloader was never asked")
        println("LIVE-REPORT-BEGIN\n$report LIVE-REPORT-END")
    }
}
