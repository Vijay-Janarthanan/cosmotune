package com.cosmotune.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirebaseClientsTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val config = FirebaseConfig("test-key", "demo", "https://db.example", identityBase = "$base/identity", tokenBase = "$base/token", firestoreBase = "$base/fs")
    private var clock = 1_000_000L
    private val auth = FirebaseAuthClient(config, OkHttpClient(), { clock })

    @After fun stop() = server.shutdown()

    private fun reply(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    private fun ok(vararg pairs: Pair<String, String>) = "{" + pairs.joinToString(",") { "\"${it.first}\":\"${it.second}\"" } + "}"

    // ---- values ----

    @Test fun valuesSurviveTheTypedFormat() {
        val plain = buildJsonObject {
            put("s", "text"); put("n", 42L); put("d", 1.5); put("b", true); put("z", JsonNull)
            put("list", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(2L))))
            put("map", buildJsonObject { put("inner", "x") })
            put("emptyList", JsonArray(emptyList()))
        }
        val typed = FirestoreValues.toFields(plain)
        assertEquals("42", typed["n"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content, "Firestore wants integers as text")
        assertEquals(plain, FirestoreValues.fromFields(typed))
    }

    // ---- authentication ----

    @Test fun aGuestSignsInWithNoInput() = runBlocking<Unit> {
        reply(200, """{"idToken":"id1","refreshToken":"r1","expiresIn":"3600","localId":"guest-1"}""")
        val session = auth.signInAnonymously()
        assertEquals("guest-1", session.uid)
        assertTrue(session.isAnonymous)
        assertEquals(clock + 3_600_000, session.expiresAtMs)
        val request = server.takeRequest()
        assertEquals("/identity/accounts:signUp?key=test-key", request.path)
    }

    @Test fun refreshingKeepsTheProfile() = runBlocking<Unit> {
        reply(200, """{"id_token":"id2","refresh_token":"r2","expires_in":"3600","user_id":"u1"}""")
        val before = CloudSession("u1", "id1", "r1", clock, isAnonymous = false, email = "a@b.c", displayName = "Ann", photoUrl = "p")
        val after = auth.refresh(before)
        assertEquals("id2", after.idToken)
        assertEquals("Ann", after.displayName)
        assertFalse(after.isAnonymous)
        assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=r1"))
    }

    @Test fun googleSignInUpgradesTheGuestInPlace() = runBlocking<Unit> {
        reply(200, """{"idToken":"id3","refreshToken":"r3","expiresIn":"3600","localId":"guest-1","email":"a@b.c","displayName":"Ann"}""")
        val guest = CloudSession("guest-1", "guest-id", "guest-r", clock, isAnonymous = true)
        val result = auth.signInWithGoogle("google-token", guest)
        assertTrue(result.linkedGuest)
        assertEquals("guest-1", result.session.uid)
        assertFalse(result.session.isAnonymous)
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("guest-id", sent["idToken"]!!.jsonPrimitive.content, "the guest's token is what links the two")
        assertTrue(sent["postBody"]!!.jsonPrimitive.content.contains("providerId=google.com"))
    }

    @Test fun aGoogleAccountAlreadyUsedElsewhereIsSignedInWithoutLinking() = runBlocking<Unit> {
        reply(400, """{"error":{"code":400,"message":"FEDERATED_USER_ID_ALREADY_LINKED"}}""")
        reply(200, """{"idToken":"id4","refreshToken":"r4","expiresIn":"3600","localId":"existing-9"}""")
        val result = auth.signInWithGoogle("google-token", CloudSession("guest-1", "g", "r", clock, isAnonymous = true))
        assertFalse(result.linkedGuest)
        assertEquals("existing-9", result.session.uid)
        server.takeRequest()
        assertFalse(Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.containsKey("idToken"))
    }

    @Test fun aRevokedSignInIsRecognised() = runBlocking<Unit> {
        reply(400, """{"error":{"code":400,"message":"INVALID_REFRESH_TOKEN"}}""")
        val failure = assertFailsWith<CloudException> { auth.refresh(CloudSession("u", "i", "r", clock, true)) }
        assertTrue(failure.isAuthLost)
    }

    // ---- the session ----

    @Test fun theSessionStartsAGuestOnlyOnce() = runBlocking<Unit> {
        reply(200, """{"idToken":"id1","refreshToken":"r1","expiresIn":"3600","localId":"guest-1"}""")
        val manager = CloudSessionManager(auth, InMemorySessionStore(), { clock })
        assertEquals("guest-1", manager.ensureSession().uid)
        assertEquals("guest-1", manager.ensureSession().uid) // no second request is queued, so a second sign-up would fail
        assertEquals(1, server.requestCount)
    }

    @Test fun aTokenAboutToLapseIsRenewedBeforeUse() = runBlocking<Unit> {
        val store = InMemorySessionStore(CloudSession("u1", "old", "r1", clock + 60_000, isAnonymous = false))
        reply(200, """{"id_token":"new","refresh_token":"r2","expires_in":"3600","user_id":"u1"}""")
        assertEquals("new", CloudSessionManager(auth, store, { clock }).idToken())
        assertEquals("r2", store.load()!!.refreshToken)
    }

    @Test fun aLostGuestIsReplacedButALostGoogleSignInIsNot() = runBlocking<Unit> {
        val lost = """{"error":{"code":400,"message":"TOKEN_EXPIRED"}}"""
        reply(400, lost)
        reply(200, """{"idToken":"fresh","refreshToken":"r9","expiresIn":"3600","localId":"guest-2"}""")
        val guest = CloudSessionManager(auth, InMemorySessionStore(CloudSession("guest-1", "x", "r", clock, isAnonymous = true)), { clock })
        assertEquals("guest-2", guest.ensureSession().uid)

        reply(400, lost)
        val google = CloudSessionManager(auth, InMemorySessionStore(CloudSession("u1", "x", "r", clock, isAnonymous = false)), { clock })
        assertFailsWith<CloudSignInRequired> { google.ensureSession() }
    }

    // ---- Firestore ----

    private fun firestore() = FirestoreRest(config, OkHttpClient()) { "the-token" }

    @Test fun aCommitCarriesTheServerStampAndThePrecondition() = runBlocking<Unit> {
        reply(200, "{}")
        firestore().commit(listOf(DocWrite("users/u1/library/likes-3", buildJsonObject { put("kind", "likes") }, expectUpdateTime = "2026-11-05T10:00:00Z")))
        val request = server.takeRequest()
        assertEquals("/fs/projects/demo/databases/(default)/documents:commit", request.path)
        assertEquals("Bearer the-token", request.getHeader("Authorization"))
        val write = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["writes"]!!.jsonArray[0].jsonObject
        assertEquals("syncedAt", write["updateTransforms"]!!.jsonArray[0].jsonObject["fieldPath"]!!.jsonPrimitive.content)
        assertEquals("REQUEST_TIME", write["updateTransforms"]!!.jsonArray[0].jsonObject["setToServerValue"]!!.jsonPrimitive.content)
        assertEquals("2026-11-05T10:00:00Z", write["currentDocument"]!!.jsonObject["updateTime"]!!.jsonPrimitive.content)
        assertEquals("projects/demo/databases/(default)/documents/users/u1/library/likes-3", write["update"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun aChangedDocumentIsAConflictAndAnythingElseIsAnError() = runBlocking<Unit> {
        reply(409, """{"error":{"code":409,"message":"too much contention","status":"ABORTED"}}""")
        assertFailsWith<CloudConflict> { firestore().commit(listOf(DocWrite("users/u1/library/a", JsonObject(emptyMap()), mustNotExist = true))) }
        reply(403, """{"error":{"code":403,"message":"denied","status":"PERMISSION_DENIED"}}""")
        val denied = assertFailsWith<CloudException> { firestore().commit(listOf(DocWrite("users/u2/library/a", JsonObject(emptyMap())))) }
        assertEquals("PERMISSION_DENIED", denied.code)
    }

    @Test fun readingAMissingDocumentGivesNull() = runBlocking<Unit> {
        reply(404, """{"error":{"code":404,"message":"not found","status":"NOT_FOUND"}}""")
        assertNull(firestore().get("users/u1/library/none"))
    }

    @Test fun aQueryAsksForTheDocumentsStampedAfterTheMark() = runBlocking<Unit> {
        reply(
            200,
            """[{"document":{"name":"projects/demo/databases/(default)/documents/users/u1/library/likes-1","fields":{"kind":{"stringValue":"likes"},"syncedAt":{"timestampValue":"2026-11-05T10:00:00.5Z"}},"updateTime":"2026-11-05T10:00:00.5Z"}}]""",
        )
        val found = firestore().changedSince("users/u1", "library", sinceMs = 1_000)
        assertEquals(1, found.size)
        assertEquals("users/u1/library/likes-1", found[0].path)
        assertEquals(java.time.Instant.parse("2026-11-05T10:00:00.5Z").toEpochMilli(), found[0].syncedAtMs)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["structuredQuery"]!!.jsonObject
        assertEquals("GREATER_THAN", body["where"]!!.jsonObject["fieldFilter"]!!.jsonObject["op"]!!.jsonPrimitive.content)
        // an empty answer is a list holding only a read time
        reply(200, """[{"readTime":"2026-11-05T10:00:01Z"}]""")
        assertTrue(firestore().changedSince("users/u1", "library", 0).isEmpty())
        assertNotNull(server.takeRequest())
    }
}
