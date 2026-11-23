package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodioCommentsTest {
    @Test fun aMastodonPostHasAPublicThread() {
        assertEquals("https://podcastindex.social/api/v1/statuses/109/context", PodioComments.contextUrl("https://podcastindex.social/@dave/109"))
        assertEquals("https://example.social/api/v1/statuses/77/context", PodioComments.contextUrl("https://example.social/users/show/statuses/77"))
        assertNull(PodioComments.contextUrl("http://example.social/@dave/109"))
        assertNull(PodioComments.contextUrl("https://example.social/notes/abc/def/ghi"))
    }

    @Test fun repliesComeOutAsTextUnderWhatTheyAnswer() {
        val json = """{"ancestors":[],"descendants":[
            {"id":"2","in_reply_to_id":"1","visibility":"public","content":"<p>Great episode!</p><p>Loved it</p>","created_at":"2026-11-20T10:00:00Z",
             "url":"https://ex.social/@ann/2","account":{"display_name":"Ann","username":"ann","acct":"ann@ex.social"}},
            {"id":"3","in_reply_to_id":"2","visibility":"public","content":"<p>Agreed</p>","created_at":"2026-11-20T11:00:00Z",
             "url":"https://ex.social/@bo/3","account":{"display_name":"","username":"bo","acct":"bo"}},
            {"id":"4","in_reply_to_id":"1","visibility":"direct","content":"<p>secret</p>","account":{"username":"x"}}]}"""
        val comments = PodioComments.parseContext(json)
        assertEquals(listOf("Ann", "bo"), comments.map { it.author })
        assertEquals("Great episode!\nLoved it", comments[0].text)
        assertEquals("@ann@ex.social", comments[0].handle)
        assertEquals(listOf(0, 1), comments.map { it.depth })
    }

    @Test fun theFeedNamesWhereTheEpisodeIsTalkedAbout() {
        val feed = PodioCatalogue.parseFeed("""<rss xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>Show</title>
            <item><title>One</title><enclosure url="https://cdn.example/1.mp3" type="audio/mpeg"/>
            <podcast:socialInteract protocol="activitypub" uri="https://ex.social/@show/42" accountId="@show@ex.social"/></item></channel></rss>""",
            "https://feeds.example/show.xml")
        assertEquals("https://ex.social/@show/42", feed.episodes.single().comments)
    }
}
