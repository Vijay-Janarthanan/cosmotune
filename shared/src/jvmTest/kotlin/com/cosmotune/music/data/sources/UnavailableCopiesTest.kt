package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnavailableCopiesTest {
    private var now = 0L
    private val copies = UnavailableCopies(ttlMs = 1_000L, now = { now })
    private val standIn = Song("jiosaavn:abc", "Kaala Megam Megam", "Ravi", null, durationText = "5:05")

    @Test fun aGoneCopyIsRememberedForAWhile() {
        assertFalse(copies.isGone("dead"))
        copies.markGone("dead")
        assertTrue(copies.isGone("dead"))
        now = 999L
        assertTrue(copies.isGone("dead"))
        now = 1_000L
        assertFalse(copies.isGone("dead"))
    }

    @Test fun theStandInThatPlayedIsAskedFirstNextTime() {
        copies.markGone("dead")
        assertNull(copies.standInFor("dead"))
        copies.rememberStandIn("dead", standIn)
        assertEquals(standIn, copies.standInFor("dead"))
        now = 2_000L
        assertNull(copies.standInFor("dead"))
        assertFalse(copies.isGone("dead"))
    }

    @Test fun aStandInThatFailsIsForgotten() {
        copies.markGone("dead")
        copies.rememberStandIn("dead", standIn)
        copies.forgetStandIn("dead")
        assertNull(copies.standInFor("dead"))
        assertTrue(copies.isGone("dead"))
    }

    @Test fun theListStaysSmall() {
        val small = UnavailableCopies(ttlMs = 1_000L, now = { now }, capacity = 2)
        small.markGone("a"); now = 1; small.markGone("b"); now = 2; small.markGone("c")
        assertFalse(small.isGone("a"))
        assertTrue(small.isGone("b"))
        assertTrue(small.isGone("c"))
    }
}
