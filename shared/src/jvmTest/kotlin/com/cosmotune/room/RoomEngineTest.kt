package com.cosmotune.room

import com.cosmotune.cloud.CloudException
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.listentogether.ServerClock
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Realtime Database in memory that refuses what the real security rules refuse in the cases the room relies on (sequence numbers, taken seats). */
internal class FakeRtdb : RtdbApi {
    val tree = MutableStateFlow<JsonElement>(JsonNull)
    var serverMs = 1_000_000L
    var beforeUpdate: (() -> Unit)? = null
    private var keys = 0

    private fun resolve(v: JsonElement): JsonElement = when {
        v is JsonObject && v.size == 1 && v[".sv"] != null -> JsonPrimitive(serverMs)
        v is JsonObject -> JsonObject(v.mapValues { resolve(it.value) })
        else -> v
    }

    private fun at(root: JsonElement, seg: List<String>): JsonElement {
        var cur = root
        for (s in seg) cur = (cur as? JsonObject)?.get(s) ?: return JsonNull
        return cur
    }

    private fun write(changes: Map<String, JsonElement>) {
        val old = tree.value
        var next = old
        for ((p, v) in changes) {
            val seg = RtdbTree.segments(p)
            if (seg.size == 3 && seg[0] == "rooms" && (seg[2] == "state" || seg[2] == "queue") && v is JsonObject) {
                val before = ((at(old, seg) as? JsonObject)?.get("seq") as? JsonPrimitive)?.longOrNull ?: 0
                if ((v["seq"] as? JsonPrimitive)?.longOrNull != before + 1) throw CloudException("PERMISSION_DENIED", "Permission denied", 401)
            }
            if (seg.size == 4 && seg[2] == "members" && v is JsonObject) {
                val holder = (at(old, seg) as? JsonObject)?.get("uid")?.let { (it as JsonPrimitive).content }
                if (holder != null && holder != (v["uid"] as JsonPrimitive).content) throw CloudException("PERMISSION_DENIED", "Permission denied", 401)
            }
            next = RtdbTree.set(next, seg, v)
        }
        tree.value = next
    }

    override suspend fun get(path: String) = at(tree.value, RtdbTree.segments(path))
    override suspend fun put(path: String, value: JsonElement): JsonElement = resolve(value).also { write(mapOf(path to it)) }
    override suspend fun update(path: String, values: JsonObject): JsonElement {
        beforeUpdate?.also { beforeUpdate = null }?.invoke()
        write(values.entries.associate { (k, v) -> (path.trim('/') + "/" + k).trim('/') to resolve(v) })
        return values
    }
    override suspend fun delete(path: String) = write(mapOf(path to JsonNull))
    override suspend fun push(path: String, value: JsonElement): String {
        val key = "-N%05d".format(++keys)
        serverMs += 5
        write(mapOf("${path.trim('/')}/$key" to resolve(value)))
        return key
    }
    override fun listen(path: String): Flow<JsonElement> = tree.map { at(it, RtdbTree.segments(path)) }.distinctUntilChanged()
}

private fun track(id: String, fromAutoplay: Boolean = false) = PartyTrack(id, "Title $id", "Artist", "https://img/$id", 200_000, fromAutoplay)

class RoomLogicTest {
    private val base = PartyPlayback(seq = 4, track = track("a"), isPlaying = true, positionMs = 10_000, anchorMs = 100_000)
    private val queue = PartyQueue(seq = 2, index = 1, items = listOf(track("z"), track("a"), track("b"), track("c")))
    private fun run(control: RoomControl, p: PartyPlayback = base, q: PartyQueue = queue, now: Long = 105_000) = RoomLogic.reduce(p, q, control, now, "me", "Me")

    @Test fun positionRunsWhilePlayingAndHoldsBeforeTheAnchor() {
        assertEquals(15_000, RoomLogic.positionAt(base, 105_000))
        assertEquals(10_000, RoomLogic.positionAt(base, 99_000), "before the scheduled start it holds")
        assertEquals(10_000, RoomLogic.positionAt(base.copy(isPlaying = false), 500_000))
    }

    @Test fun pauseKeepsThePlaceAndPlayResumesAheadOfNow() {
        val paused = run(RoomControl.Pause())!!.playback
        assertEquals(5, paused.seq)
        assertFalse(paused.isPlaying)
        assertEquals(15_000, paused.positionMs)
        val resumed = run(RoomControl.Play(), paused, now = 200_000)!!.playback
        assertEquals(200_000 + RoomLogic.PLAY_LEAD_MS, resumed.anchorMs)
        assertEquals(15_000, resumed.positionMs)
    }

    @Test fun nextStartsTheFollowingTrackAndStopsAtTheEnd() {
        val next = run(RoomControl.Next)!!
        assertEquals("b", next.playback.track!!.videoId)
        assertEquals(2, next.playback.queueIndex)
        assertEquals(0, next.playback.positionMs)
        assertEquals("me", next.playback.startedBy)
        assertNull(run(RoomControl.Next, q = queue.copy(index = 3)))
    }

    @Test fun aLiveStationHasNoPositionToSeekTo() {
        val radio = base.copy(track = PartyTrack("123e4567-e89b-12d3-a456-426614174000", "Radio X", "Chennai", null, null, kind = PartyTrack.KIND_RADIO, url = "https://stream.example.org/live.mp3"))
        assertNull(run(RoomControl.Seek(60_000), p = radio))
        assertTrue(radio.track!!.isLive)
        assertEquals("podcast", RoomMapping.trackOf(RoomMapping.trackJson(PartyTrack("e1", "Episode", "Show", null, 1_000, kind = "podcast", url = "https://cdn.example.org/e1.mp3")))!!.kind)
    }

    @Test fun aWatchItemKeepsItsKeyAndReleaseHint() {
        val item = PartyTrack("e:tmdb:1399:1:2", "Game of Thrones", "S1 · E2", null, 3_360_000, kind = PartyTrack.KIND_WATCH,
            watch = com.cosmotune.music.data.listentogether.PartyWatch(tmdb = 1399, imdb = "tt0944947", season = 1, episode = 2, size = 2_254_857_830, name = "GoT.S01E02.mkv"))
        val json = RoomMapping.trackJson(item)
        val read = RoomMapping.trackOf(json)!!
        assertEquals(item, read)
        assertTrue(!read.needsUpdate)
        assertTrue(PartyTrack("c:iptv:BBCOne.uk", "BBC One", kind = PartyTrack.KIND_WATCH).isLive)
        assertNull(RoomMapping.trackJson(item.copy(kind = PartyTrack.KIND_MUSIC))["watch"], "only a Watch item carries a watch object")
    }

    @Test fun aLinkThatIsNotHttpsIsNeverTrustedAndAnUnknownKindIsNeverMusic() {
        val bad = kotlinx.serialization.json.buildJsonObject {
            put("videoId", "x"); put("title", "t"); put("kind", "movie"); put("url", "http://evil.example/x.mp3")
        }
        val read = RoomMapping.trackOf(bad)!!
        // A kind from a newer app is kept, so no player here takes it for a song; the room says to update.
        assertEquals("movie", read.kind)
        assertTrue(read.needsUpdate)
        assertNull(read.url)
        assertNull(RoomMapping.trackJson(read.copy(url = "http://evil.example/x.mp3"))["url"])
    }

    @Test fun nextAfterDoesNothingOnceTheRoomHasMovedOn() {
        assertEquals("b", run(RoomControl.NextAfter(expectedSeq = 4))!!.playback.track!!.videoId)
        assertNull(run(RoomControl.NextAfter(expectedSeq = 3)), "the room is at 4: someone else already moved on")
    }

    @Test fun previousRestartsALongSongAndGoesBackFromTheStartOfOne() {
        assertEquals(0, run(RoomControl.Previous)!!.playback.positionMs, "15 s in: restart")
        val early = run(RoomControl.Previous, p = base.copy(positionMs = 500, anchorMs = 105_000))!!
        assertEquals("z", early.playback.track!!.videoId)
    }

    @Test fun queueAddPlacesAfterTheCurrentOrAtTheEndAndSkipsSuggestionsAlreadyWaiting() {
        val next = run(RoomControl.QueueAdd(listOf(track("n")), playNext = true))!!.queue!!
        assertEquals(listOf("z", "a", "n", "b", "c"), next.items.map { it.videoId })
        val withSuggestion = queue.copy(items = queue.items + track("s", fromAutoplay = true))
        assertNull(run(RoomControl.QueueAdd(listOf(track("s", fromAutoplay = true))), q = withSuggestion), "already waiting")
        assertEquals(5, run(RoomControl.QueueAdd(listOf(track("s", fromAutoplay = true))))!!.queue!!.items.size)
    }

    @Test fun theQueueIsCappedAndTheOldestHistoryGoesFirst() {
        val many = (0 until 40).map { track("t$it") }
        val (bounded, index) = RoomLogic.bound(many, 10)
        assertEquals(RoomLogic.MAX_QUEUE, bounded.size)
        assertEquals("t10", bounded[index].videoId, "the playing track is still the same one")
    }

    @Test fun movingByTrackIdSurvivesAShiftedIndex() {
        // the index in the request is stale (it names "b"), but the id names "c", which is what moves
        val moved = run(RoomControl.QueueMove(fromIndex = 2, toIndex = 2, videoId = "c"))!!.queue!!
        assertEquals(listOf("z", "a", "c", "b"), moved.items.map { it.videoId })
    }

    @Test fun setTrackPutsAnUnknownTrackRightAfterTheCurrentOne() {
        val result = run(RoomControl.SetTrack(track("new")))!!
        assertEquals(listOf("z", "a", "new", "b", "c"), result.queue!!.items.map { it.videoId })
        assertEquals(2, result.playback.queueIndex)
        assertEquals("new", result.playback.track!!.videoId)
    }

    @Test fun codesAreReadableAndTypingIsForgiving() {
        repeat(50) { assertTrue(RoomCode.isValid(RoomCode.generate())) }
        assertEquals("ABC234", RoomCode.normalize(" abc-234 "))
        assertFalse(RoomCode.isValid("ABC0O1"))
    }
}

class RtdbWireTest {
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    @Test fun listsComeBackAsNumberedObjects() {
        val array = JsonArray(listOf(JsonNull, obj("uid" to JsonPrimitive("a")), obj("uid" to JsonPrimitive("b"))))
        val normal = RtdbTree.normalize(array) as JsonObject
        assertEquals(setOf("1", "2"), normal.keys)
    }

    @Test fun putReplacesAndPatchMergesIntoTheTree() {
        var tree: JsonElement = RtdbTree.set(JsonNull, emptyList(), obj("a" to obj("x" to JsonPrimitive(1))))
        tree = RtdbTree.merge(tree, listOf("a"), obj("y" to JsonPrimitive(2)))
        assertEquals(obj("a" to obj("x" to JsonPrimitive(1), "y" to JsonPrimitive(2))), tree)
        tree = RtdbTree.set(tree, listOf("a", "x"), JsonNull)
        assertEquals(obj("a" to obj("y" to JsonPrimitive(2))), tree)
        assertEquals(JsonNull, RtdbTree.set(tree, listOf("a"), JsonNull), "an emptied tree is gone")
    }

    @Test fun eventsAreCompleteAtABlankLine() {
        val parser = SseParser()
        assertNull(parser.feed("event: put"))
        assertNull(parser.feed("""data: {"path":"/","data":null}"""))
        val event = parser.feed("")!!
        assertEquals("put", event.type)
        assertEquals("""{"path":"/","data":null}""", event.data)
        assertNull(parser.feed(""), "a stray blank line is nothing")
    }

    @Test fun aLiveStreamBuildsTheTreeFromPutAndPatchEvents() = runBlocking<Unit> {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "event: put\ndata: {\"path\":\"/\",\"data\":{\"state\":{\"seq\":1}}}\n\n" +
                        "event: keep-alive\ndata: null\n\n" +
                        "event: patch\ndata: {\"path\":\"/state\",\"data\":{\"seq\":2}}\n\n" +
                        "event: put\ndata: {\"path\":\"/chat/m1\",\"data\":{\"text\":\"hi\"}}\n\n",
                ),
            )
            val rtdb = RtdbRest(server.url("/").toString(), OkHttpClient()) { "token-1" }
            val trees = mutableListOf<JsonElement>()
            rtdb.listen("rooms/ABC234").collect { trees += it }
            assertEquals(3, trees.size, "the keep-alive builds nothing")
            assertEquals(JsonPrimitive(2), trees[1].jsonObject["state"]!!.jsonObject["seq"])
            assertEquals("hi", (trees[2].jsonObject["chat"]!!.jsonObject["m1"]!!.jsonObject["text"] as JsonPrimitive).content)
            val request = server.takeRequest()
            assertEquals("/rooms/ABC234.json?auth=token-1", request.path)
            assertEquals("text/event-stream", request.getHeader("Accept"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun aRefusedReadIsAPermissionError() = runBlocking<Unit> {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Permission denied"}"""))
            val failure = assertFailsWith<CloudException> { RtdbRest(server.url("/").toString(), OkHttpClient()) { "t" }.get("rooms/X") }
            assertEquals("PERMISSION_DENIED", failure.code)
        } finally {
            server.shutdown()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomClientTest {
    private fun TestScope.client(db: FakeRtdb, uid: String, seed: Long = 1) = RoomClient(
        rtdb = db,
        uid = { uid },
        profile = { RoomProfile("Name $uid", null, "device-$uid") },
        clock = ServerClock { testScheduler.currentTime },
        scope = backgroundScope,
        wallNowMs = { db.serverMs },
        random = java.util.Random(seed),
    )

    @Test fun aRoomIsMadeJoinedAndSeenByBoth() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create(maxMembers = 3).getOrThrow()
        runCurrent()
        assertEquals(RoomConnection.LIVE, host.connection.value)
        assertEquals(listOf("host"), host.room.value!!.members.map { it.uid })
        assertTrue(host.room.value!!.members.single().isHost)

        val guest = client(db, "guest")
        assertEquals(listOf("Name host"), guest.peek(code.lowercase()).getOrThrow()!!.memberNames)
        guest.join(code).getOrThrow()
        runCurrent()
        assertEquals(setOf("host", "guest"), host.room.value!!.members.map { it.uid }.toSet())
        assertEquals(1, guest.room.value!!.member("guest")!!.seat)
    }

    @Test fun aFullRoomRefusesAndAReturningDeviceTakesItsSeatBack() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create(maxMembers = 2).getOrThrow()
        val one = client(db, "one").also { it.join(code).getOrThrow() }
        val late = client(db, "late")
        val refused = late.join(code).exceptionOrNull() as RoomException
        assertEquals(RoomException.Reason.FULL, refused.reason)
        runCurrent()
        assertEquals(1, one.room.value!!.member("one")!!.seat)
    }

    @Test fun anyMemberCanControlAndTheSequenceAdvancesByOne() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create().getOrThrow()
        val guest = client(db, "guest").also { it.join(code).getOrThrow() }
        runCurrent()
        guest.control(RoomControl.SetTrack(track("a"))).getOrThrow()
        runCurrent()
        host.control(RoomControl.Pause()).getOrThrow()
        runCurrent()
        assertEquals(2, host.room.value!!.playback.seq)
        assertEquals("a", guest.room.value!!.playback.track!!.videoId)
        assertFalse(guest.room.value!!.playback.isPlaying)
        assertEquals("guest", guest.room.value!!.playback.startedBy)
    }

    @Test fun aWriteThatLostTheRaceIsRetriedAgainstTheNewerState() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create().getOrThrow()
        val guest = client(db, "guest").also { it.join(code).getOrThrow() }
        runCurrent()
        // the guest's pause sneaks in after the host has worked out its own next state
        db.beforeUpdate = {
            db.tree.value = RtdbTree.set(db.tree.value, listOf("rooms", code, "state"), RoomMapping.playbackJson(PartyPlayback(seq = 1, isPlaying = false, updatedBy = "guest")))
        }
        val result = async { host.control(RoomControl.Play()) }
        advanceTimeBy(1_000)
        runCurrent()
        result.await().getOrThrow()
        runCurrent()
        assertEquals(2, host.room.value!!.playback.seq)
        assertTrue(host.room.value!!.playback.isPlaying)
    }

    @Test fun hostOnlyControlRefusesMembersButNotTheHost() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create().getOrThrow()
        val guest = client(db, "guest").also { it.join(code).getOrThrow() }
        host.setHostOnlyControl(true).getOrThrow()
        runCurrent()
        assertEquals(RoomException.Reason.HOST_ONLY, (guest.control(RoomControl.Pause()).exceptionOrNull() as RoomException).reason)
        host.control(RoomControl.SetTrack(track("a"))).getOrThrow()
    }

    @Test fun chatAndRequestsReachEveryone() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create().getOrThrow()
        val guest = client(db, "guest").also { it.join(code).getOrThrow() }
        runCurrent()
        guest.sendChat("hello").getOrThrow()
        guest.askToPlay(track("again"), "play this again").getOrThrow()
        runCurrent()
        val chat = host.room.value!!.chat
        assertEquals(listOf("text", "request"), chat.map { it.kind })
        assertEquals("again", chat[1].ref!!.videoId)
        assertEquals("play this again", chat[1].text)
    }

    @Test fun theHostHandsOverAndTheLastPersonOutTakesTheRoomDown() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create().getOrThrow()
        val guest = client(db, "guest").also { it.join(code).getOrThrow() }
        runCurrent()
        host.leave()
        runCurrent()
        assertEquals("guest", guest.room.value!!.meta.hostUid)
        assertEquals(listOf("guest"), guest.room.value!!.members.map { it.uid })
        guest.leave()
        assertEquals(JsonNull, db.get("rooms/$code"))
        assertEquals(JsonNull, db.get("userRoom/guest"))
    }

    @Test fun aRemovedMemberFindsOutAndTheMicStateIsShared() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create().getOrThrow()
        val guest = client(db, "guest").also { it.join(code).getOrThrow() }
        runCurrent()
        guest.setMicLive(true).getOrThrow()
        runCurrent()
        assertTrue(host.room.value!!.member("guest")!!.micLive)
        host.remove("guest").getOrThrow()
        runCurrent()
        assertEquals(RoomConnection.REMOVED, guest.connection.value)
        assertNotNull(host.room.value)
    }

    @Test fun theActiveRoomEndsWhenYouAreRemovedWhileTheLastRoomIsKeptForTheScreen() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        val code = host.create().getOrThrow()
        val guest = client(db, "guest").also { it.join(code).getOrThrow() }
        runCurrent()
        assertNotNull(guest.activeRoom.value)
        host.remove("guest").getOrThrow()
        runCurrent()
        assertEquals(RoomConnection.REMOVED, guest.connection.value)
        assertNotNull(guest.room.value, "the screen still shows what it last saw")
        assertNull(guest.activeRoom.value, "voice and the like stop")
    }

    @Test fun theSharedPlayheadIsReadFromTheServerClock() = runTest {
        val db = FakeRtdb()
        val host = client(db, "host")
        host.create().getOrThrow()
        runCurrent()
        host.control(RoomControl.SetTrack(track("a"), positionMs = 20_000)).getOrThrow()
        runCurrent()
        val position = host.positionMs()!!
        assertTrue(position >= 20_000 - RoomLogic.PLAY_LEAD_MS - 1, "position=$position")
        assertEquals(host.room.value!!.playback.track!!.videoId, host.snapshot.value!!.playback.track!!.videoId)
    }
}
