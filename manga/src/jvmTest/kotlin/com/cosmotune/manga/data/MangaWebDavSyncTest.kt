package com.cosmotune.manga.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MangaWebDavSyncTest {
    private fun o(v: String) = JsonObject(mapOf("v" to JsonPrimitive(v)))

    @Test fun theFirstSyncJoinsBothSides() {
        val merge = MangaWebDavSync.merge(local = mapOf("series:a" to o("1")), remote = mapOf("series:b" to o("2")), base = emptyMap())
        assertEquals(setOf("series:a", "series:b"), merge.result.keys)
        assertEquals(mapOf("series:b" to o("2")), merge.upserts)
        assertTrue(merge.deletes.isEmpty())
    }

    @Test fun aChangeOnOneSideWins() {
        val base = mapOf("read:a" to o("1"), "read:b" to o("1"))
        val merge = MangaWebDavSync.merge(local = mapOf("read:a" to o("2"), "read:b" to o("1")), remote = mapOf("read:a" to o("1"), "read:b" to o("3")), base = base)
        assertEquals(mapOf("read:a" to o("2"), "read:b" to o("3")), merge.result)
        assertEquals(mapOf("read:b" to o("3")), merge.upserts)
    }

    @Test fun aSeriesRemovedOnEitherSideGoes() {
        val base = mapOf("series:a" to o("1"), "series:b" to o("1"))
        val merge = MangaWebDavSync.merge(local = mapOf("series:a" to o("1")), remote = mapOf("series:a" to o("1"), "series:b" to o("1")), base = base)
        assertEquals(setOf("series:a"), merge.result.keys)
        val other = MangaWebDavSync.merge(local = mapOf("series:a" to o("1"), "series:b" to o("1")), remote = mapOf("series:b" to o("1")), base = base)
        assertEquals(setOf("series:b"), other.result.keys)
        assertEquals(setOf("series:a"), other.deletes)
    }

    @Test fun whenBothChangedThisDeviceWins() {
        val merge = MangaWebDavSync.merge(local = mapOf("place:a" to o("L")), remote = mapOf("place:a" to o("R")), base = mapOf("place:a" to o("B")))
        assertEquals(mapOf("place:a" to o("L")), merge.result)
        assertTrue(merge.upserts.isEmpty())
    }

    @Test fun theFileReadsBackWhatWasWritten() {
        val items = mapOf("series:a" to o("1"))
        assertEquals(items, MangaWebDavSync.decode(MangaWebDavSync.encode(items, 5L)))
        assertEquals(emptyMap(), MangaWebDavSync.decode(""))
    }

    @Test fun theFileAddressEndsInTheSyncFile() {
        assertEquals("https://dav.example/remote.php/dav/files/me/cosmotune-manga.json", MangaWebDavSync.fileUrl("https://dav.example/remote.php/dav/files/me"))
        assertEquals("https://dav.example/sync/cosmotune-manga.json", MangaWebDavSync.fileUrl(" https://dav.example/sync/ "))
        assertEquals("https://dav.example/x.json", MangaWebDavSync.fileUrl("https://dav.example/x.json"))
    }
}
