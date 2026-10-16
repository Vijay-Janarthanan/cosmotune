package com.cosmotune.video.segments

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The SponsorBlock and DeArrow hash prefix (spec 2.3, 7.1): the first characters of the lower-case hex SHA-256 of the video id. */
class HashPrefixTest {

    private fun reference(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun theDefaultIsFourCharactersOfTheLowerCaseHex() {
        assertEquals("5f6b", sha256Prefix("dQw4w9WgXcQ"))
        assertEquals(4, sha256Prefix("anything").length)
    }

    @Test
    fun itMatchesMessageDigestForManyIdsAndLengths() {
        for (id in listOf("dQw4w9WgXcQ", "jNQXAC9IVRw", "fakevideo01", "-_-_-_-_-_-", "abc", "")) {
            val full = reference(id)
            for (n in listOf(1, 2, 3, 4, 5, 8, 31, 32, 33, 63, 64)) {
                assertEquals(full.take(n), sha256Prefix(id, n), "id '$id', $n characters")
            }
        }
    }

    @Test
    fun theKnownSha256VectorsHold() {
        // FIPS 180-2 test vector for "abc"; the other three were computed with hashlib.
        assertEquals("ba7816bf8f01cfea", sha256Prefix("abc", 16))
        assertEquals("5f6b0b4e201f2a7e", sha256Prefix("dQw4w9WgXcQ", 16))
        assertEquals("eebf47f0", sha256Prefix("fakevideo01", 8))
        assertEquals("67454704342df24de2d91fae262fc75b3c9735d45135a6273239d4e68037d15c", sha256Prefix("jNQXAC9IVRw", 64))
    }

    @Test
    fun theAnswerIsLowerCaseHexOnly() {
        for (i in 0 until 200) {
            val p = sha256Prefix("video$i", 32)
            assertTrue(p.all { it in '0'..'9' || it in 'a'..'f' }, p)
        }
    }

    @Test
    fun theTextIsHashedAsUtf8() {
        assertEquals(reference("café").take(8), sha256Prefix("café", 8))
    }

    @Test
    fun aLengthOutsideTheDigestIsRefused() {
        assertFailsWith<IllegalArgumentException> { sha256Prefix("abc", 0) }
        assertFailsWith<IllegalArgumentException> { sha256Prefix("abc", -4) }
        assertFailsWith<IllegalArgumentException> { sha256Prefix("abc", 65) }
    }
}
