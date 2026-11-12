package com.cosmotune.video.signed

import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.potoken.SessionBoundTokens
import com.cosmotune.youtube.account.SessionTransport
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The fake HTTP of the signed player tests: answers by the client a request names, and keeps every request as it was sent. */
internal class FakeSignedHttp : SignedHttp {
    class Sent(val url: String, val headers: Map<String, String>, val body: JsonObject) {
        val client: String get() = body["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content
        val userAgent: String get() = headers.getValue("User-Agent")
    }

    val sent = mutableListOf<Sent>()

    /** What each client (by its user agent) answers, in turn; the last answer repeats. Unscripted clients answer [PlayerJson.REFUSED]. */
    private val script = HashMap<String, ArrayDeque<SignedHttp.Answer>>()

    fun answer(client: SignedPlayerClient, vararg answers: SignedHttp.Answer) {
        script[client.userAgent] = ArrayDeque(answers.toList())
    }

    fun answer(client: SignedPlayerClient, body: String) = answer(client, SignedHttp.Answer(200, emptyList(), body))

    /** How long each client (by its user agent) takes to answer, in the test's virtual time; none by default. */
    private val slow = HashMap<String, Long>()

    fun takes(client: SignedPlayerClient, ms: Long) {
        slow[client.userAgent] = ms
    }

    override suspend fun post(url: String, headers: Map<String, String>, body: String): SignedHttp.Answer {
        sent += Sent(url, headers, Json.parseToJsonElement(body).jsonObject)
        slow[headers.getValue("User-Agent")]?.let { delay(it) }
        val queue = script[headers.getValue("User-Agent")] ?: return SignedHttp.Answer(200, emptyList(), PlayerJson.REFUSED)
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}

/** A solver that needs no player script: a cipher becomes its `url` value with `&signature=solved`, and `n=` is marked as solved. */
internal class FakeCipher(private val fail: Boolean = false) : SignedCipher {
    override suspend fun signatureTimestamp(videoId: String): Int = 20400

    override suspend fun address(videoId: String, url: String?, signatureCipher: String?): String {
        if (fail) error("the script could not be read")
        val plain = url ?: java.net.URLDecoder.decode(signatureCipher!!.substringAfter("url="), "UTF-8") + "&signature=solved"
        return plain.replace("n=throttled", "n=solved")
    }
}

internal object PlayerJson {
    const val REFUSED = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}"""
    const val NO_STREAMS = """{"playabilityStatus":{"status":"OK"},"streamingData":{"serverAbrStreamingUrl":"https://x.googlevideo.com/sabr"}}"""

    /**
     * An answer whose only playable stream is the muxed 360p one (the WEB client's, whose adaptive formats are server-driven and
     * carry no address): it plays, but it has no ladder.
     */
    val MUXED_ONLY = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"title":"Private film","author":"Me","channelId":"UCme","lengthSeconds":"125"},
         "streamingData":{"expiresInSeconds":"21540",
           "formats":[{"itag":18,"url":"https://r1.googlevideo.com/videoplayback?expire=1800000000&itag=18","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","width":640,"height":360,"fps":30,"bitrate":500000,"qualityLabel":"360p"}],
           "adaptiveFormats":[{"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","width":1920,"height":1080,"fps":30,"bitrate":4000000}]}}
    """.trimIndent()

    /** An answer whose client gives plain links, with an OTF format and a DRM format that must be left out. */
    val PLAYABLE = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"title":"Private film","author":"Me","channelId":"UCme","lengthSeconds":"125","viewCount":"7","shortDescription":"Mine at 0:30",
           "keywords":["one","two"],"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/x/hq.jpg","width":480,"height":360}]}},
         "microformat":{"playerMicroformatRenderer":{"category":"Film","publishDate":"2026-01-02"}},
         "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://www.youtube.com/api/timedtext?v=x","languageCode":"en","kind":"asr","name":{"simpleText":"English (auto)"}}]}},
         "storyboards":{"playerStoryboardSpecRenderer":{"spec":"https://i.ytimg.com/sb/x/${'$'}L/${'$'}N.jpg?a=b|48#27#100#10#10#0#default#s1|160#90#120#5#5#2000#M${'$'}M#s2"}},
         "streamingData":{"expiresInSeconds":"21540",
           "formats":[{"itag":18,"url":"https://r1.googlevideo.com/videoplayback?expire=1800000000&itag=18","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","width":640,"height":360,"fps":30,"bitrate":500000,"qualityLabel":"360p"}],
           "adaptiveFormats":[
             {"itag":137,"url":"https://r1.googlevideo.com/videoplayback?expire=1800000000&itag=137&n=throttled","mimeType":"video/mp4; codecs=\"avc1.640028\"","width":1920,"height":1080,"fps":30,"bitrate":4000000,"contentLength":"12345","initRange":{"start":"0","end":"740"},"indexRange":{"start":"741","end":"900"},"qualityLabel":"1080p"},
             {"itag":399,"signatureCipher":"s=abc&sp=sig&url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D1800000000%26itag%3D399","mimeType":"video/mp4; codecs=\"av01.0.08M.08\"","width":1920,"height":1080,"bitrate":3000000},
             {"itag":140,"url":"https://r1.googlevideo.com/videoplayback?expire=1800000000&itag=140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,"audioSampleRate":"44100","audioChannels":2,"audioTrack":{"id":"en.4","displayName":"English original","audioIsDefault":true}},
             {"itag":299,"url":"https://r1.googlevideo.com/otf","type":"FORMAT_STREAM_TYPE_OTF","mimeType":"video/mp4; codecs=\"avc1.64002a\""},
             {"itag":298,"url":"https://r1.googlevideo.com/drm","drmFamilies":["WIDEVINE"],"mimeType":"video/mp4; codecs=\"avc1.64002a\""}
           ]}}
    """.trimIndent()
}

internal object SignedFixtures {
    const val LOGIN = "SID=1; SAPISID=sapi; __Secure-1PAPISID=first; __Secure-3PAPISID=third; __Secure-1PSIDTS=ts0"
    const val NOW_MS = 1_800_000_000_000L
    const val ACCOUNT = "AAAA||BBBB"

    fun scope(dataSyncId: String? = ACCOUNT, pageId: String? = "PAGE1") = Innertube.SignedScope(
        dataSyncId = dataSyncId,
        pageId = pageId,
        authUser = "2",
        visitorData = "VISITOR",
        webClientVersion = "2.20261101.00.00",
        language = "en",
        region = "IN",
    )

    fun session(transport: SessionTransport? = null, signedIn: Boolean = true) = YouTubeSession(
        clock = { NOW_MS },
        background = CoroutineScope(Dispatchers.Unconfined),
        transport = transport,
    ).also { if (signedIn) it.install(LOGIN) }

    /** A minter that says which identity and video each token was for, and counts the identity's. */
    class FakeTokens(var available: Boolean = true) {
        val identities = mutableListOf<String>()

        suspend fun mint(videoId: String, identity: String): SessionBoundTokens.Tokens? {
            if (!available) return null
            identities += identity
            return SessionBoundTokens.Tokens(playerToken = "player+$videoId", streamingToken = "stream+$identity")
        }
    }

    fun resolver(
        session: YouTubeSession,
        http: SignedHttp,
        tokens: FakeTokens = FakeTokens(),
        cipher: SignedCipher = FakeCipher(),
        scope: () -> Innertube.SignedScope = { SignedFixtures.scope() },
        clock: () -> Long = { NOW_MS },
    ) = SignedPlayerResolver(session, { scope() }, http, tokens::mint, cipher, clock)
}
