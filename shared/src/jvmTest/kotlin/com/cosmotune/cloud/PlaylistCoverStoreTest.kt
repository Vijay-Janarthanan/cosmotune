package com.cosmotune.cloud

import com.cosmotune.music.data.covers.PlaylistCoverStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers kept on the device, read back after a restart, cleared, and carried to the account and back. */
class PlaylistCoverStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    /** The start of a JPEG and some bytes: enough to be taken for one. */
    private fun jpeg(size: Int, fill: Int = 1) = ByteArray(size) { fill.toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }

    private fun fileOf(uri: String) = File(URI(uri))

    @Test fun aPickedCoverIsKeptAndReadBackAfterARestart() {
        val folder = File(temporary.root, "covers")
        val store = PlaylistCoverStore(folder)
        assertTrue(store.set("VLPLmine", jpeg(5_000), jpeg(800)))
        val uri = store.covers.value.getValue("PLmine")
        assertTrue(fileOf(uri).isFile)
        assertEquals(5_000L, fileOf(uri).length(), "the page shows the large copy")
        assertEquals(800, store.smallJpeg("PLmine")?.size)

        val again = PlaylistCoverStore(folder)
        assertEquals(mapOf("PLmine" to uri), again.covers.value)
    }

    @Test fun aNewPictureGetsANewFileSoNothingShowsTheOldOne() {
        val store = PlaylistCoverStore(File(temporary.root, "covers"))
        store.set("PLmine", jpeg(5_000, 1), jpeg(800, 1))
        val first = store.covers.value.getValue("PLmine")
        store.set("PLmine", jpeg(5_000, 2), jpeg(800, 2))
        val second = store.covers.value.getValue("PLmine")
        assertNotEquals(first, second)
        assertFalse(fileOf(first).exists(), "the old picture is deleted")
    }

    @Test fun usingTheAutomaticCoverForgetsThePicture() {
        val store = PlaylistCoverStore(File(temporary.root, "covers"))
        store.set("PLmine", jpeg(5_000), jpeg(800))
        val uri = store.covers.value.getValue("PLmine")
        store.clear("VLPLmine")
        assertNull(store.covers.value["PLmine"])
        assertFalse(fileOf(uri).exists())
        assertNull(store.smallJpeg("PLmine"))
        assertTrue(PlaylistCoverStore(File(temporary.root, "covers")).covers.value.isEmpty())
    }

    @Test fun onlyAPlaylistIdAndARealPictureAreTaken() {
        val store = PlaylistCoverStore(File(temporary.root, "covers"))
        assertFalse(store.set("local:downloads", jpeg(10), jpeg(10)))
        assertFalse(store.set("PLmine", ByteArray(0), jpeg(10)))
        assertFalse(store.set("PLmine", ByteArray(10) { 7 }, jpeg(10)), "not a JPEG")
        assertTrue(store.covers.value.isEmpty())
    }

    @Test fun theAccountGetsTheSmallCopyAndAnotherDeviceShowsIt() {
        val phone = PlaylistCoverStore(File(temporary.root, "phone"))
        phone.set("PLmine", jpeg(5_000), jpeg(900))
        val entries = SyncPlaylistCovers.snapshot(phone.smallPictures())
        assertEquals(setOf("PLmine"), entries.keys)
        assertContentEquals(jpeg(900), SyncPlaylistCovers.jpeg(entries.getValue("PLmine")))

        val desktop = PlaylistCoverStore(File(temporary.root, "desktop"))
        desktop.applyFromCloud(entries.mapValues { SyncPlaylistCovers.jpeg(it.value)!! }, emptySet())
        assertTrue(desktop.covers.value.containsKey("PLmine"))
        assertTrue(desktop.lastCloudWrite === desktop.covers.value, "the sync's own write is told apart from a pick made here")
        // Read back exactly as it came, so the journal does not take it for an edit made on this device.
        assertEquals(entries, SyncPlaylistCovers.snapshot(desktop.smallPictures()))

        desktop.applyFromCloud(emptyMap(), setOf("PLmine"))
        assertTrue(desktop.covers.value.isEmpty())
    }

    @Test fun aCoverTooBigForTheAccountOrABucketStaysOnTheDevice() {
        assertTrue(SyncPlaylistCovers.snapshot(mapOf("PLbig" to jpeg(SyncPlaylistCovers.MAX_PICTURE_BYTES + 1))).isEmpty())
        assertTrue(SyncPlaylistCovers.snapshot(mapOf("local:x" to jpeg(100))).isEmpty())
        // Many covers: no bucket of the account is filled past its budget.
        val many = (1..200).associate { "PL$it" to jpeg(SyncPlaylistCovers.MAX_PICTURE_BYTES) }
        val kept = SyncPlaylistCovers.snapshot(many)
        assertTrue(kept.size in 1 until 200)
        val perBucket = kept.entries.groupBy { CloudSync.bucketName(SyncPlaylistCovers.KIND, it.key) }
            .mapValues { (_, list) -> list.sumOf { it.value.toString().length } }
        assertTrue(perBucket.values.all { it <= SyncPlaylistCovers.BUCKET_BUDGET }, "$perBucket")
        assertEquals(kept, SyncPlaylistCovers.snapshot(many), "the same covers every time")
    }

    @Test fun aBrokenEntryFromTheAccountIsLeftOut() {
        assertNull(SyncPlaylistCovers.jpeg(JsonObject(mapOf("jpeg" to JsonPrimitive("not base64!")))))
        assertNull(SyncPlaylistCovers.jpeg(JsonObject(emptyMap())))
        val store = PlaylistCoverStore(File(temporary.root, "covers"))
        store.applyFromCloud(mapOf("PLx" to ByteArray(3)), emptySet())
        assertTrue(store.covers.value.isEmpty())
    }
}
