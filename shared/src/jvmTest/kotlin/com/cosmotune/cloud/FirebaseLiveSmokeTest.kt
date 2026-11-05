package com.cosmotune.cloud

import com.cosmotune.music.data.Http
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Talks to the real Firebase project with a throwaway guest account that is deleted at the end. It only runs when
 * `COSMOTUNE_FIREBASE_LIVE=1` is set, so ordinary test runs and CI never touch the network or the project.
 */
class FirebaseLiveSmokeTest {
    @Test fun aGuestSignsInSyncsTwoDevicesIsRefusedOthersDataAndDeletesItself() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_FIREBASE_LIVE") == "1")
        val config = CosmotuneFirebase.config
        val auth = FirebaseAuthClient(config, Http.client)
        var session = auth.signInAnonymously()
        try {
            val manager = CloudSessionManager(auth, InMemorySessionStore(session))
            val docs = FirestoreRest(config, Http.client) { manager.idToken() }
            fun data(n: Int): JsonObject = buildJsonObject { put("title", "Song $n"); put("n", n) }

            // two devices, same account
            val a = FakeLibrary(mapOf("likes" to listOf(SyncEntry("track-a", 1000, data = data(1)), SyncEntry("track-b", 1000, data = data(2)))))
            val b = FakeLibrary()
            val reportA = CloudSync(docs, { session.uid }, a, InMemorySyncMarks(), listOf("likes")).run()
            val reportB = CloudSync(docs, { session.uid }, b, InMemorySyncMarks(), listOf("likes")).run()
            assertEquals(2, reportA.sent)
            assertEquals(setOf("track-a", "track-b"), b.ids("likes"))
            println("live: device A sent ${reportA.sent} in ${reportA.documentsWritten} write(s); device B received ${reportB.received} after ${reportB.documentsRead} read(s)")

            // the rules: someone else's documents are out of reach
            val denied = assertFailsWith<CloudException> { docs.get("users/some-other-user/library/likes-0") }
            assertEquals("PERMISSION_DENIED", denied.code)

            // a token renewed from the refresh token keeps working
            session = auth.refresh(session)
            assertEquals(reportA.documentsWritten, docs.listIds("users/${session.uid}/library").size)

            CloudSync(docs, { session.uid }, a, InMemorySyncMarks(), listOf("likes")).deleteAllCloudData()
            assertEquals(0, docs.listIds("users/${session.uid}/library").size)
        } finally {
            auth.deleteAccount(session)
        }
    }
}
