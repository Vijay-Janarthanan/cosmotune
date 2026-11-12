package com.cosmotune.video.signed

import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.StreamKind
import com.cosmotune.youtube.account.SapisidAuth
import com.cosmotune.youtube.account.SessionTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The walk over the signed clients, what each request says, and the session's part in it; over fake HTTP. */
class SignedPlayerResolverTest {
    private val clients = SignedPlayerClient.SIGNED

    // ---- The order ----------------------------------------------------------------------------------------------------

    @Test fun theSignedChainIsTheOrderItWasChosenIn() {
        assertEquals(
            listOf(SignedPlayerClient.TV_SAMSUNG, SignedPlayerClient.WEB_EMBEDDED, SignedPlayerClient.TV_OLD, SignedPlayerClient.WEB),
            SignedPlayerClient.SIGNED,
        )
        assertEquals(listOf(SignedPlayerClient.IOS, SignedPlayerClient.ANDROID_VR), SignedPlayerClient.RESCUE)
        assertTrue(SignedPlayerClient.SIGNED.all { it.takesCookies }, "every signed client can use the jar")
        assertTrue(SignedPlayerClient.RESCUE.none { it.takesCookies }, "the app clients cannot")
    }

    @Test fun theFirstClientIsAskedAloneAndWhenItFailsTheOthersRaceForTheAnswer() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.REFUSED)
        http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.NO_STREAMS)
        http.answer(SignedPlayerClient.TV_OLD, PlayerJson.PLAYABLE)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        assertEquals(SignedPlayerClient.TV_OLD, playback.client, "the first with streams")
        assertEquals(SignedPlayerClient.TV_SAMSUNG.userAgent, http.sent.first().userAgent, "the first of the order, alone")
        assertEquals(clients.map { it.userAgent }, http.sent.map { it.userAgent }, "then the rest of it together, in its order")
        assertEquals(0L, testScheduler.currentTime, "nothing waited for the hedge: the first failure started the others")
    }

    @Test fun theFirstToAnswerWinsAndASlowFirstClientIsHedgedAfterAMoment() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        http.takes(SignedPlayerClient.TV_SAMSUNG, 5_000)
        http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.PLAYABLE)
        http.takes(SignedPlayerClient.WEB_EMBEDDED, 200)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        assertEquals(SignedPlayerClient.WEB_EMBEDDED, playback.client)
        assertEquals(SignedPlayerResolver.HEDGE_MS + 200, testScheduler.currentTime, "asked once the first kept it waiting, answered 200 ms later")
    }

    @Test fun aFirstClientThatAnswersAsksNoOther() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        http.takes(SignedPlayerClient.TV_SAMSUNG, SignedPlayerResolver.HEDGE_MS - 1)
        SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        assertEquals(listOf(SignedPlayerClient.TV_SAMSUNG.userAgent), http.sent.map { it.userAgent })
    }

    @Test fun aClientThatKeepsFailingWhileAnotherAnswersIsBenchedForTheSession() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.REFUSED)
        http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.PLAYABLE)
        var now = SignedFixtures.NOW_MS
        val resolver = SignedFixtures.resolver(SignedFixtures.session(), http, clock = { now })
        repeat(ClientRanking.STRIKES) { resolver.resolve("vid$it", clients) }
        assertEquals(setOf(SignedPlayerClient.TV_SAMSUNG), resolver.ranking.benched())
        http.sent.clear()
        assertEquals(SignedPlayerClient.WEB_EMBEDDED, resolver.resolve("next", clients).client)
        assertEquals(SignedPlayerClient.WEB_EMBEDDED.userAgent, http.sent.first().userAgent, "the next video does not wait on it")
        assertTrue(http.sent.none { it.userAgent == SignedPlayerClient.TV_SAMSUNG.userAgent }, "nor asks it while another answers")
        now += ClientRanking.BENCH_MS
        http.sent.clear()
        resolver.resolve("later", clients)
        assertEquals(SignedPlayerClient.TV_SAMSUNG.userAgent, http.sent.first().userAgent, "after the bench it is first again")
    }

    @Test fun aVideoNoClientCanPlayBlamesNoClient() = runTest {
        val resolver = SignedFixtures.resolver(SignedFixtures.session(), FakeSignedHttp())
        repeat(ClientRanking.STRIKES + 1) { assertFailsWith<SignedPlaybackFailure> { resolver.resolve("gone$it", clients) } }
        assertTrue(resolver.ranking.benched().isEmpty())
    }

    @Test fun nothingAnsweringBeforeTheDeadlineLosesTheRace() = runTest {
        val http = FakeSignedHttp()
        for (client in clients) {
            http.answer(client, PlayerJson.PLAYABLE)
            http.takes(client, 60_000)
        }
        val failure = assertFailsWith<SignedPlaybackFailure> { SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients) }
        assertEquals(SignedPlayerResolver.DEADLINE_MS, testScheduler.currentTime)
        assertEquals(clients.map { "${it.name}: no answer within ${SignedPlayerResolver.DEADLINE_MS}ms" }, failure.reasons)
    }

    @Test fun anAnswerWithOnlyTheMuxedStreamWaitsForOneWithTheWholeLadder() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.REFUSED)
        http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.MUXED_ONLY)
        http.answer(SignedPlayerClient.TV_OLD, PlayerJson.PLAYABLE)
        http.takes(SignedPlayerClient.TV_OLD, 1_500)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        assertEquals(SignedPlayerClient.TV_OLD, playback.client, "the 360p alone would leave the quality menu with one rung")
        assertTrue(playback.streams.videoOnly.isNotEmpty())
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test fun whenOnlyAMuxedAnswerCameItIsWhatPlays() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.WEB, PlayerJson.MUXED_ONLY)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        assertEquals(SignedPlayerClient.WEB, playback.client)
        assertEquals(listOf(18), playback.streams.muxed.map { it.itag })
        assertTrue(playback.streams.videoOnly.isEmpty())
    }

    @Test fun aMuxedAnswerInHandWaitsOnlyAMomentForTheOthers() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.MUXED_ONLY)
        for (client in clients.drop(1)) {
            http.answer(client, PlayerJson.PLAYABLE)
            http.takes(client, 60_000)
        }
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        assertEquals(SignedPlayerClient.TV_SAMSUNG, playback.client)
        assertEquals(SignedPlayerResolver.PARTIAL_GRACE_MS, testScheduler.currentTime, "not the whole deadline")
    }

    @Test fun aLastResortIsNeverRacedOnlyAskedAfterTheOthersFailed() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.IOS, PlayerJson.PLAYABLE)
        http.takes(SignedPlayerClient.IOS, 2_000)
        http.answer(SignedPlayerClient.ANDROID_VR, PlayerJson.PLAYABLE)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", SignedPlayerClient.RESCUE)
        assertEquals(SignedPlayerClient.IOS, playback.client, "the VR client's links can stop after a megabyte: it waits")
        assertEquals(listOf(SignedPlayerClient.IOS.userAgent), http.sent.map { it.userAgent })
    }

    @Test fun aServerErrorOrAnUnreadableAnswerOnlyMovesTheWalkOn() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, SignedHttp.Answer(500, emptyList(), "oops"))
        http.answer(SignedPlayerClient.WEB_EMBEDDED, "not json")
        http.answer(SignedPlayerClient.TV_OLD, PlayerJson.PLAYABLE)
        assertEquals(SignedPlayerClient.TV_OLD, SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients).client)
    }

    @Test fun whenEveryClientFailsTheFailureSaysWhyAndNamesNothingOfTheAccount() = runTest {
        val failure = assertFailsWith<SignedPlaybackFailure> {
            SignedFixtures.resolver(SignedFixtures.session(), FakeSignedHttp()).resolve("vid", clients)
        }
        assertEquals(4, failure.reasons.size)
        assertTrue(failure.reasons.first().startsWith("TV_SAMSUNG: LOGIN_REQUIRED"), failure.reasons.first())
        assertFalse("sapi" in failure.message.orEmpty())
    }

    @Test fun clientsSittingOutAreNotAsked() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.PLAYABLE)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients, skip = setOf(SignedPlayerClient.TV_SAMSUNG))
        assertEquals(SignedPlayerClient.WEB_EMBEDDED, playback.client)
        assertEquals(1, http.sent.size)
    }

    @Test fun linksThatCannotBeSolvedAreNoStreams() = runTest {
        val http = FakeSignedHttp()
        val onlyCiphered = """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":399,"signatureCipher":"s=a&url=https%3A%2F%2Fx","mimeType":"video/mp4; codecs=\"av01\""}]}}"""
        http.answer(SignedPlayerClient.TV_SAMSUNG, onlyCiphered)
        http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.PLAYABLE)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http, cipher = FakeCipher(fail = true)).resolve("vid", clients)
        assertEquals(SignedPlayerClient.WEB_EMBEDDED, playback.client)
        assertEquals(listOf(18), playback.streams.muxed.map { it.itag }, "what needs no solving still plays")
    }

    // ---- The signed headers -------------------------------------------------------------------------------------------

    @Test fun aSignedRequestCarriesTheJarTheThreeHashesAndTheAccountScope() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val session = SignedFixtures.session()
        SignedFixtures.resolver(session, http).resolve("vid", clients)
        val headers = http.sent.single().headers
        val seconds = SignedFixtures.NOW_MS / 1000
        val origin = "https://www.youtube.com"
        assertEquals(SignedFixtures.LOGIN, headers["Cookie"])
        assertEquals(
            "SAPISIDHASH ${SapisidAuth.hash(seconds, "sapi", origin)} SAPISID1PHASH ${SapisidAuth.hash(seconds, "first", origin)} SAPISID3PHASH ${SapisidAuth.hash(seconds, "third", origin)}",
            headers["Authorization"],
        )
        assertEquals("2", headers["X-Goog-AuthUser"])
        assertEquals("PAGE1", headers["X-Goog-PageId"])
        assertEquals("VISITOR", headers["X-Goog-Visitor-Id"])
        assertEquals(origin, headers["Origin"])
        assertEquals(origin, headers["X-Origin"])
        assertEquals("7", headers["X-YouTube-Client-Name"])
        assertEquals(SignedPlayerClient.TV_SAMSUNG.version, headers["X-YouTube-Client-Version"])
    }

    @Test fun theBodySaysWhoAndSkipsTheInterstitial() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        val body = http.sent.single().body
        val context = body["context"]!!.jsonObject
        assertEquals(SignedFixtures.ACCOUNT, context["user"]!!.jsonObject["onBehalfOfUser"]!!.jsonPrimitive.content)
        assertEquals("Samsung", context["client"]!!.jsonObject["deviceMake"]!!.jsonPrimitive.content)
        assertEquals("VISITOR", context["client"]!!.jsonObject["visitorData"]!!.jsonPrimitive.content)
        assertEquals("vid", body["videoId"]!!.jsonPrimitive.content)
        assertEquals("true", body["contentCheckOk"]!!.jsonPrimitive.content)
        assertEquals("true", body["racyCheckOk"]!!.jsonPrimitive.content)
        val playback = body["playbackContext"]!!.jsonObject["contentPlaybackContext"]!!.jsonObject
        assertEquals("20400", playback["signatureTimestamp"]!!.jsonPrimitive.content, "ciphered links must be solvable with the current script")
    }

    @Test fun theEmbeddedPlayerSaysItIsEmbedded() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.PLAYABLE)
        SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", listOf(SignedPlayerClient.WEB_EMBEDDED))
        val context = http.sent.single().body["context"]!!.jsonObject
        assertEquals("EMBED", context["client"]!!.jsonObject["clientScreen"]!!.jsonPrimitive.content)
        assertEquals("https://www.youtube.com/embed/vid", context["thirdParty"]!!.jsonObject["embedUrl"]!!.jsonPrimitive.content)
    }

    @Test fun anAppClientSendsNoCredentialAtAll() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.IOS, PlayerJson.PLAYABLE)
        SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", SignedPlayerClient.RESCUE)
        val sent = http.sent.single()
        for (name in listOf("Cookie", "Authorization", "X-Goog-AuthUser", "X-Goog-PageId", "Origin", "X-Goog-Visitor-Id")) {
            assertNull(sent.headers[name], name)
        }
        assertNull(sent.body["context"]!!.jsonObject["user"], "no onBehalfOfUser")
    }

    // ---- The session --------------------------------------------------------------------------------------------------

    @Test fun setCookieValuesGoBackToTheSession() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, SignedHttp.Answer(200, listOf("__Secure-1PSIDTS=ts1; Domain=.youtube.com; Path=/"), PlayerJson.PLAYABLE))
        val session = SignedFixtures.session()
        SignedFixtures.resolver(session, http).resolve("vid", clients)
        assertTrue("__Secure-1PSIDTS=ts1" in session.cookie.orEmpty())
    }

    @Test fun aRefusedRequestIsRetriedOnceWithTheRotatedJar() = runTest {
        val transport = object : SessionTransport {
            override suspend fun rotate(url: String, cookie: String, origin: String) =
                SessionTransport.Answer(200, listOf("__Secure-1PSIDTS=rotated; Domain=.youtube.com; Path=/"))

            override suspend fun page(url: String, cookie: String, authorization: String?) =
                SessionTransport.Answer(200, emptyList(), "ytcfg.set({\"LOGGED_IN\":true});")
        }
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, SignedHttp.Answer(401, emptyList(), ""), SignedHttp.Answer(200, emptyList(), PlayerJson.PLAYABLE))
        val session = SignedFixtures.session(transport)
        val playback = SignedFixtures.resolver(session, http).resolve("vid", clients)
        assertEquals(SignedPlayerClient.TV_SAMSUNG, playback.client)
        assertEquals(2, http.sent.size, "the same client again")
        assertFalse("rotated" in http.sent[0].headers.getValue("Cookie"))
        assertTrue("rotated" in http.sent[1].headers.getValue("Cookie"))
    }

    @Test fun aRefusalWithNoRotationMovesToTheNextClient() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, SignedHttp.Answer(403, emptyList(), ""))
        http.answer(SignedPlayerClient.WEB_EMBEDDED, PlayerJson.PLAYABLE)
        assertEquals(SignedPlayerClient.WEB_EMBEDDED, SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients).client)
    }

    // ---- The session-bound tokens -------------------------------------------------------------------------------------

    @Test fun theWebClientSendsThePlayerTokenAndPutsTheStreamingTokenOnItsLinks() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.WEB, PlayerJson.PLAYABLE)
        val tokens = SignedFixtures.FakeTokens()
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http, tokens).resolve("vid", listOf(SignedPlayerClient.WEB))
        assertEquals(listOf(SignedFixtures.ACCOUNT), tokens.identities, "bound to the account, not the visitor")
        val sent = http.sent.single()
        assertEquals("player+vid", sent.body["serviceIntegrityDimensions"]!!.jsonObject["poToken"]!!.jsonPrimitive.content)
        assertEquals(SignedFixtures.scope().webClientVersion, sent.headers["X-YouTube-Client-Version"], "the live version")
        val url = playback.streams.videoOnly.first().url
        assertTrue(url.endsWith("&pot=" + java.net.URLEncoder.encode("stream+${SignedFixtures.ACCOUNT}", "UTF-8")), url)
    }

    @Test fun withoutAnAccountIdTheTokenIsBoundToTheVisitor() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.WEB, PlayerJson.PLAYABLE)
        val tokens = SignedFixtures.FakeTokens()
        SignedFixtures.resolver(SignedFixtures.session(), http, tokens, scope = { SignedFixtures.scope(dataSyncId = null) })
            .resolve("vid", listOf(SignedPlayerClient.WEB))
        assertEquals(listOf("VISITOR"), tokens.identities)
    }

    @Test fun aPlatformWithNoMinterSkipsTheWebClientWithoutAsking() = runTest {
        val http = FakeSignedHttp()
        val failure = assertFailsWith<SignedPlaybackFailure> {
            SignedFixtures.resolver(SignedFixtures.session(), http, SignedFixtures.FakeTokens(available = false)).resolve("vid", listOf(SignedPlayerClient.WEB))
        }
        assertEquals(0, http.sent.size)
        assertEquals(listOf("WEB: no token"), failure.reasons)
    }

    @Test fun clientsThatNeedNoTokenMintNone() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val tokens = SignedFixtures.FakeTokens()
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http, tokens).resolve("vid", clients)
        assertTrue(tokens.identities.isEmpty())
        assertNull(http.sent.single().body["serviceIntegrityDimensions"])
        assertFalse("pot=" in playback.streams.videoOnly.first().url)
    }

    // ---- The parse ----------------------------------------------------------------------------------------------------

    @Test fun formatsBecomeStreamsAndWhatCannotPlayIsLeftOut() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val streams = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients).streams
        assertEquals(listOf(18), streams.muxed.map { it.itag })
        assertTrue(streams.muxed.single().hasAudio)
        assertEquals(listOf(137, 399), streams.videoOnly.map { it.itag }, "no OTF, no DRM")
        val hd = streams.videoOnly.first()
        assertEquals("video/mp4", hd.mimeType)
        assertEquals("avc1.640028", hd.codecs)
        assertEquals(1080, hd.height)
        assertEquals(ByteRange(0, 740), hd.init)
        assertEquals(ByteRange(741, 900), hd.index)
        assertEquals(12345L, hd.contentLength)
        assertTrue("n=solved" in hd.url, "the throttling parameter was solved")
        assertTrue(streams.videoOnly[1].url.endsWith("&signature=solved"), "the cipher was solved")
        val audio = streams.audio.single()
        assertEquals("en", audio.language)
        assertEquals(AudioTrackKind.ORIGINAL, audio.trackKind)
        assertEquals(44100, audio.sampleRate)
        assertEquals(125_000L, streams.durationMs)
        assertEquals(1_800_000_000_000L, streams.expiresAtMs)
    }

    @Test fun theMediaFetchesWearTheClientButNeverTheAccount() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val headers = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients).streams.requestHeaders
        assertEquals(SignedPlayerClient.TV_SAMSUNG.userAgent, headers["User-Agent"])
        assertEquals(setOf("User-Agent", "Origin", "Referer"), headers.keys, "no Cookie, no Authorization")
        assertEquals(setOf("User-Agent"), SignedPlayerParser.mediaHeaders(SignedPlayerClient.IOS).keys)
    }

    @Test fun theWatchPageIsReadFromThePlayerAndNextAnswers() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        val next = Json.parseToJsonElement(
            """{"playerOverlays":{"multiMarkersPlayerBarRenderer":{"markersMap":[{"key":"DESCRIPTION_CHAPTERS","value":{"chapters":[{"chapterRenderer":{"title":{"simpleText":"Intro"},"timeRangeStartMillis":0}},{"chapterRenderer":{"title":{"simpleText":"Middle"},"timeRangeStartMillis":60000}}]}}]}},
                "contents":{"owner":{"videoOwnerRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/a","width":88,"height":88}]},"subscriberCountText":{"simpleText":"1.2K subscribers"}}},
                "like":{"segmentedLikeDislikeButtonViewModel":{"inner":{"buttonViewModel":{"title":"345"}}}}}}""",
        ).jsonObject
        val details = SignedPlayerParser.details("vid", playback, next, SignedFixtures.NOW_MS)
        assertEquals("Private film", details.item.title)
        assertEquals("UCme", details.item.channelId)
        assertEquals("https://yt3.ggpht.com/a", details.item.channelAvatarUrl)
        assertEquals(125L, details.item.durationSec)
        assertEquals(7L, details.item.viewCount)
        assertEquals("Mine at 0:30", details.description.text)
        assertEquals(30_000L, details.description.links.single().timestampMs, "a time in the words is a link")
        assertEquals("Film", details.category)
        assertEquals(listOf("one", "two"), details.tags)
        assertEquals(listOf("Intro", "Middle"), details.chapters.map { it.title })
        assertEquals(60_000L, details.chapters[1].startMs)
        assertEquals(345L, details.likeCount)
        assertEquals(1200L, details.subscriberCount)
        assertEquals(StreamKind.VIDEO, details.kind)
        val subtitle = details.subtitles.single()
        assertTrue(subtitle.autoGenerated)
        assertEquals("https://www.youtube.com/api/timedtext?v=x&fmt=vtt", subtitle.url)
        val board = assertNotNull(details.storyboard)
        assertEquals(160, board.frameWidth)
        assertEquals(5, board.columns)
        assertEquals(2000L, board.intervalMs)
        assertEquals(120, board.totalFrames)
        assertEquals(5, board.urls.size, "120 frames at 25 a sheet")
        assertEquals("https://i.ytimg.com/sb/x/1/M0.jpg?a=b&sigh=s2", board.urls.first())
    }

    @Test fun withoutANextAnswerThePageStillPlays() = runTest {
        val http = FakeSignedHttp()
        http.answer(SignedPlayerClient.TV_SAMSUNG, PlayerJson.PLAYABLE)
        val playback = SignedFixtures.resolver(SignedFixtures.session(), http).resolve("vid", clients)
        val details = SignedPlayerParser.details("vid", playback, null, SignedFixtures.NOW_MS)
        assertTrue(details.related.isEmpty() && details.chapters.isEmpty())
        assertNull(details.likeCount)
        assertEquals(3, details.streams.videoOnly.size + details.streams.muxed.size)
    }
}
