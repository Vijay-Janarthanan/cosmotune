package com.cosmotune.video.account

import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.video.model.PageToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InnerTubeVideoAccountTest {
    /** Answers saved JSON in order per endpoint and records every call with the body it was built with. */
    private class FakeWeb : YoutubeWeb {
        class Call(val endpoint: String, val body: JsonObject)

        val calls = mutableListOf<Call>()
        private val replies = mutableMapOf<String, ArrayDeque<Any>>()
        var delayMs = 0L
        var inFlight = 0
        var maxInFlight = 0

        fun reply(endpoint: String, vararg answers: Any) {
            replies.getOrPut(endpoint) { ArrayDeque() }.addAll(answers)
        }

        override suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
            calls += Call(endpoint, buildJsonObject(body))
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                if (delayMs > 0) delay(delayMs)
                val queue = replies[endpoint]
                // The last answer repeats, so a test that asks again gets the same.
                val answer = if (queue == null || queue.isEmpty()) error("no reply for $endpoint") else if (queue.size > 1) queue.removeFirst() else queue.first()
                if (answer is Throwable) throw answer
                return answer as JsonObject
            } finally {
                inFlight -= 1
            }
        }

        fun browseIds() = calls.filter { it.endpoint == "browse" }.map { it.body["browseId"]?.jsonPrimitive?.content }
    }

    private class FakeHost : AccountHost {
        override val identity = MutableStateFlow<HostIdentity?>(
            HostIdentity("Asha Raman", "@asharaman", "https://avatar", listOf(AccountChoice("a", "Asha Raman", "@asharaman", null), AccountChoice("b", "Club", null, null)), "a"),
        )
        var signInOpened = 0
        var switchedTo: String? = null
        var signedOut = false

        override fun openSignIn() { signInOpened += 1 }
        override fun switchAccount(id: String): Boolean { switchedTo = id; return id == "a" || id == "b" }
        override fun signOut() { signedOut = true }
    }

    private val signedInCookie = "SID=1; SAPISID=abc123; other=x"

    private class Rig(scope: CoroutineScope, cookie: String?) {
        val web = FakeWeb()
        val cookie = MutableStateFlow(cookie)
        val host = FakeHost()
        val hosts = MutableStateFlow<AccountHost?>(host)
        var now = 1_000_000L
        val account = InnerTubeVideoAccount(web, this.cookie, hosts, scope, { now })
    }

    private fun TestScope.rig(cookie: String? = "SID=1; SAPISID=abc123; other=x") = Rig(backgroundScope, cookie)

    private fun fixture(name: String) = AccountFixtures.json(name)

    // ---- State ---------------------------------------------------------------------------------------------------------

    @Test fun noCookieOrNoSigningSecretIsSignedOutAndNothingIsSent() = runTest(UnconfinedTestDispatcher()) {
        val none = rig(cookie = null)
        assertEquals(AccountState.SignedOut, none.account.state.value)
        assertIs<AccountFailure.NotSignedIn>(none.account.home().exceptionOrNull())
        assertIs<AccountFailure.NotSignedIn>(none.account.setSubscribed("UCx", true).exceptionOrNull())
        assertTrue(none.web.calls.isEmpty())
        val noSecret = rig(cookie = "SID=1; HSID=2")
        assertEquals(AccountState.SignedOut, noSecret.account.state.value)
    }

    @Test fun signedInStateCarriesTheHostsIdentityAndFollowsTheCookie() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        val state = assertIs<AccountState.SignedIn>(r.account.state.value)
        assertEquals("Asha Raman", state.displayName)
        assertEquals("@asharaman", state.handle)
        assertEquals(2, state.accounts.size)
        assertEquals("a", state.active?.id)
        r.cookie.value = null
        assertEquals(AccountState.SignedOut, r.account.state.value)
        r.cookie.value = signedInCookie
        assertIs<AccountState.SignedIn>(r.account.state.value)
    }

    @Test fun withoutAHostASignedInStateHasNoNameAndTheActionsDoNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.hosts.value = null
        val state = assertIs<AccountState.SignedIn>(r.account.state.value)
        assertEquals("", state.displayName)
        assertNull(state.active)
        r.account.signInRequest()
        assertIs<AccountFailure.Changed>(r.account.switchAccount("a").exceptionOrNull())
    }

    @Test fun signInSwitchAndSignOutAreHandedToTheHost() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.account.signInRequest()
        assertEquals(1, r.host.signInOpened)
        assertTrue(r.account.switchAccount("b").isSuccess)
        assertEquals("b", r.host.switchedTo)
        assertIs<AccountFailure.Changed>(r.account.switchAccount("nope").exceptionOrNull())
        r.account.signOut()
        assertTrue(r.host.signedOut)
    }

    @Test fun anAccountChangeDropsTheCachedPages() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("continuation_last.json"))
        r.account.home()
        r.account.home()
        assertEquals(1, r.web.calls.size, "the second first page came from memory")
        r.host.identity.value = r.host.identity.value!!.copy(activeId = "b")
        r.account.home()
        assertEquals(2, r.web.calls.size, "another account sees its own home")
    }

    // ---- Reading -------------------------------------------------------------------------------------------------------

    @Test fun homeAsksForTheWhatToWatchFeedAndPagesThroughContinuations() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("subscriptions_rich_grid.json"), fixture("continuation_actions.json"), fixture("continuation_last.json"))
        val first = r.account.home().getOrThrow()
        assertEquals(4, first.items.size)
        assertEquals(PageToken("SUBS_TOKEN_1"), first.next)
        val second = r.account.home(first.next).getOrThrow()
        assertEquals(listOf("subVideo0002"), second.items.map { it.id })
        assertEquals(PageToken("SUBS_TOKEN_2"), second.next)
        val third = r.account.home(second.next).getOrThrow()
        assertEquals(listOf("subVideo0003"), third.items.map { it.id })
        assertNull(third.next)
        assertEquals(listOf("FEwhat_to_watch", null, null), r.web.browseIds())
        assertEquals("SUBS_TOKEN_1", r.web.calls[1].body["continuation"]?.jsonPrimitive?.content)
        assertNull(r.web.calls[1].body["browseId"], "a continuation carries the token alone")
        assertEquals(PageToken("SUBS_TOKEN_2"), second.next)
    }

    @Test fun eachFeedUsesItsOwnBrowseId() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("continuation_last.json"))
        r.account.subscriptionsFeed()
        r.account.history()
        r.account.watchLater()
        r.account.liked()
        r.account.playlist("PLabc")
        r.account.playlist("VLPLdef")
        assertEquals(listOf("FEsubscriptions", "FEhistory", "VLWL", "VLLL", "VLPLabc", "VLPLdef"), r.web.browseIds())
    }

    @Test fun firstPagesAreCachedForAMinuteAndInvalidateClearsThem() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("continuation_last.json"))
        r.account.home()
        r.now += InnerTubeVideoAccount.CACHE_MS - 1
        r.account.home()
        assertEquals(1, r.web.calls.size)
        r.now += 2
        r.account.home()
        assertEquals(2, r.web.calls.size, "a minute later it asks again")
        r.account.invalidate()
        r.account.home()
        assertEquals(3, r.web.calls.size)
        r.account.home(PageToken("T"))
        r.account.home(PageToken("T"))
        assertEquals(5, r.web.calls.size, "continuations are never cached")
    }

    private fun adsPage(token: String) = Json.parseToJsonElement(
        """{"responseContext":{},"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[""" +
            """{"richItemRenderer":{"content":{"adSlotRenderer":{}}}},""" +
            """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}]}}]}""",
    ) as JsonObject

    @Test fun aPageOfAdsFollowsItsTokenAndTheFollowingIsBounded() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("continuation_ads_only.json"), fixture("continuation_last.json"))
        val page = r.account.home().getOrThrow()
        assertEquals(listOf("subVideo0003"), page.items.map { it.id })
        assertEquals(2, r.web.calls.size)

        val endless = rig()
        endless.web.reply("browse", adsPage("A1"), adsPage("A2"), adsPage("A3"), adsPage("A4"), adsPage("A5"))
        val empty = endless.account.home().getOrThrow()
        assertTrue(empty.items.isEmpty())
        assertEquals(1 + InnerTubeVideoAccount.EMPTY_HOPS, endless.web.calls.size, "a feed of ads is followed a bounded number of times")
        assertEquals(PageToken("A3"), empty.next, "and the caller may ask for more")

        val stuck = rig()
        stuck.web.reply("browse", fixture("continuation_ads_only.json"))
        val same = stuck.account.home().getOrThrow()
        assertEquals(2, stuck.web.calls.size, "a token that comes back unchanged stops the following")
        assertNull(same.next)
    }

    @Test fun aTokenThatPointsAtItselfEndsThePage() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("continuation_actions.json"))
        val page = r.account.home(PageToken("SUBS_TOKEN_2")).getOrThrow()
        assertEquals(1, page.items.size)
        assertNull(page.next)
    }

    @Test fun aSignedOutAnswerIsASessionExpired() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("home_signed_out.json"))
        assertIs<AccountFailure.SessionExpired>(r.account.home().exceptionOrNull())
        assertIs<AccountFailure.SessionExpired>(r.account.subscriptionsFeed().exceptionOrNull())
    }

    @Test fun anUnreadableAnswerIsChangedAndAnEmptyListIsNot() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("changed_shape.json"))
        assertIs<AccountFailure.Changed>(r.account.home().exceptionOrNull())
        val e = rig()
        e.web.reply("browse", fixture("empty_list.json"))
        val page = e.account.watchLater().getOrThrow()
        assertTrue(page.items.isEmpty())
        assertNull(page.next)
    }

    @Test fun networkAndHttpFailuresKeepTheirTypes() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", IOException("reset"))
        assertIs<AccountFailure.Network>(r.account.home().exceptionOrNull())
        val s = rig()
        s.web.reply("browse", AccountFailure.RateLimited(30))
        assertEquals(30L, (s.account.home().exceptionOrNull() as AccountFailure.RateLimited).retryAfterSec)
        val m = rig()
        m.web.reply("browse", IllegalStateException("bad json"))
        assertIs<AccountFailure.Changed>(m.account.home().exceptionOrNull())
        val body = rig()
        body.web.reply("browse", buildJsonObject { put("error", buildJsonObject { put("code", JsonPrimitive(401)); put("status", JsonPrimitive("UNAUTHENTICATED")) }) })
        assertIs<AccountFailure.SessionExpired>(body.account.home().exceptionOrNull())
    }

    @Test fun subscribedChannelsReadEveryPageAndFeedSubscribedIds() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("channels_page.json"), fixture("empty_list.json"))
        val channels = r.account.subscribedChannels().getOrThrow()
        assertEquals(3, channels.size)
        assertEquals(setOf("UCkitchen00000000000001", "UCtrails0000000000000001", "UCcircuit000000000000001"), r.account.subscribedIds.value)
        assertEquals(listOf("FEchannels", null), r.web.browseIds())
        assertEquals("CHANNELS_TOKEN_1", r.web.calls[1].body["continuation"]?.jsonPrimitive?.content)
    }

    @Test fun whenTheChannelsPageHasNoChannelsTheGuideIsUsed() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("empty_list.json"))
        r.web.reply("guide", fixture("guide_subscriptions.json"))
        val channels = r.account.subscribedChannels().getOrThrow()
        assertEquals(listOf("Kitchen Table", "Trail Notes"), channels.map { it.name })
        assertEquals(1, r.web.calls.count { it.endpoint == "guide" })
    }

    @Test fun playlistsDropWatchLaterAndLikedAndFallBackToTheLibrary() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse", fixture("library_playlists.json"))
        assertEquals(listOf("PLweekend0000001", "PLlonglist000002"), r.account.playlists().getOrThrow().map { it.id })
        assertEquals(listOf("FEplaylist_aggregation"), r.web.browseIds())

        val f = rig()
        f.web.reply("browse", fixture("empty_list.json"), fixture("library_playlists.json"))
        assertEquals(2, f.account.playlists().getOrThrow().size)
        assertEquals(listOf("FEplaylist_aggregation", "FElibrary"), f.web.browseIds())
    }

    @Test fun requestsInFlightAreBounded() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.delayMs = 100
        r.web.reply("browse", fixture("continuation_last.json"))
        val jobs = (1..6).map { n -> async { r.account.playlist("PL$n") } }
        jobs.awaitAll()
        assertEquals(6, r.web.calls.size)
        assertEquals(InnerTubeVideoAccount.CONCURRENCY, r.web.maxInFlight)
    }

    // ---- Writing -------------------------------------------------------------------------------------------------------

    @Test fun ratingsUseTheLikeEndpoints() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        for (endpoint in listOf("like/like", "like/dislike", "like/removelike")) r.web.reply(endpoint, JsonObject(emptyMap()))
        assertTrue(r.account.rate("vid1", Rating.LIKE).isSuccess)
        assertTrue(r.account.rate("vid1", Rating.DISLIKE).isSuccess)
        assertTrue(r.account.rate("vid1", Rating.NONE).isSuccess)
        assertEquals(listOf("like/like", "like/dislike", "like/removelike"), r.web.calls.map { it.endpoint })
        assertEquals("vid1", r.web.calls[0].body["target"]!!.jsonObject["videoId"]!!.jsonPrimitive.content)
    }

    @Test fun aRefusedWriteIsReportedNotSwallowed() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("like/like", buildJsonObject { put("error", buildJsonObject { put("code", JsonPrimitive(400)); put("message", JsonPrimitive("nope")) }) })
        assertIs<AccountFailure.Changed>(r.account.rate("vid1", Rating.LIKE).exceptionOrNull())
    }

    @Test fun subscribingSendsTheChannelUpdatesTheKnownIdsAndDropsTheCaches() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("subscription/subscribe", JsonObject(emptyMap()))
        r.web.reply("subscription/unsubscribe", JsonObject(emptyMap()))
        r.web.reply("browse", fixture("continuation_last.json"))
        r.account.subscriptionsFeed()
        assertTrue(r.account.setSubscribed("UCnew", true).isSuccess)
        assertEquals(setOf("UCnew"), r.account.subscribedIds.value)
        val call = r.web.calls.single { it.endpoint == "subscription/subscribe" }
        assertEquals("UCnew", call.body["channelIds"]!!.jsonArray.single().jsonPrimitive.content)
        assertNotNull(call.body["params"])
        r.account.subscriptionsFeed()
        assertEquals(2, r.web.browseIds().size, "the feed is read again after a subscription change")
        assertTrue(r.account.setSubscribed("UCnew", false).isSuccess)
        assertTrue(r.account.subscribedIds.value.isEmpty())
        assertEquals(1, r.web.calls.count { it.endpoint == "subscription/unsubscribe" })
    }

    @Test fun playlistEditsSendTheActionsAndCheckTheStatus() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.web.reply("browse/edit_playlist", buildJsonObject { put("status", JsonPrimitive("STATUS_SUCCEEDED")) })
        assertTrue(r.account.addToPlaylist("VLPLabc", "vid1").isSuccess)
        assertTrue(r.account.addToWatchLater("vid2").isSuccess)
        assertTrue(r.account.removeFromPlaylist("PLabc", "vid1").isSuccess)
        val calls = r.web.calls
        assertEquals("PLabc", calls[0].body["playlistId"]!!.jsonPrimitive.content)
        val add = calls[0].body["actions"]!!.jsonArray.single().jsonObject
        assertEquals("ACTION_ADD_VIDEO", add["action"]!!.jsonPrimitive.content)
        assertEquals("vid1", add["addedVideoId"]!!.jsonPrimitive.content)
        assertEquals("WL", calls[1].body["playlistId"]!!.jsonPrimitive.content)
        val remove = calls[2].body["actions"]!!.jsonArray.single().jsonObject
        assertEquals("ACTION_REMOVE_VIDEO_BY_VIDEO_ID", remove["action"]!!.jsonPrimitive.content)
        assertEquals("vid1", remove["removedVideoId"]!!.jsonPrimitive.content)

        val refused = rig()
        refused.web.reply("browse/edit_playlist", buildJsonObject { put("status", JsonPrimitive("STATUS_FAILED")) })
        assertIs<AccountFailure.Changed>(refused.account.addToPlaylist("PLabc", "vid1").exceptionOrNull())
    }

    @Test fun writesWhileSignedOutNeverReachTheNetwork() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(cookie = null)
        assertIs<AccountFailure.NotSignedIn>(r.account.rate("v", Rating.LIKE).exceptionOrNull())
        assertIs<AccountFailure.NotSignedIn>(r.account.addToWatchLater("v").exceptionOrNull())
        assertIs<AccountFailure.NotSignedIn>(r.account.playlists().exceptionOrNull())
        assertIs<AccountFailure.NotSignedIn>(r.account.subscribedChannels().exceptionOrNull())
        assertTrue(r.web.calls.isEmpty())
    }

    // ---- Request signing -----------------------------------------------------------------------------------------------

    @Test fun theWebAuthorizationIsSapisidhashOverTheYouTubeOrigin() {
        val header = Innertube.webAuthorization("SID=1; SAPISID=abc123; x=y", nowSeconds = 1_700_000_000)
        val digest = sha1("1700000000 abc123 https://www.youtube.com")
        assertEquals("SAPISIDHASH 1700000000_$digest", header)
        assertFalse(header!!.endsWith(sha1("1700000000 abc123 https://music.youtube.com")), "Music's origin is a different hash")
    }

    @Test fun theSecureCookieFormsSignWhenThePlainOneIsMissing() {
        assertEquals("SAPISIDHASH 5_" + sha1("5 third https://www.youtube.com"), Innertube.webAuthorization("__Secure-3PAPISID=third; __Secure-1PAPISID=first", 5))
        assertEquals("SAPISIDHASH 5_" + sha1("5 first https://www.youtube.com"), Innertube.webAuthorization("__Secure-1PAPISID=first", 5))
        assertNull(Innertube.webAuthorization("SID=1; HSID=2", 5))
    }

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
