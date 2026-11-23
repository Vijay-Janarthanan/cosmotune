package com.cosmotune.room

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomCoHostTest {
    private fun room(coHosts: String) = RoomMapping.roomOf("ABC123", Json.parseToJsonElement(
        """{"meta":{"createdAt":1,"hostUid":"h","maxMembers":5,"hostOnlyControl":true,"coHosts":$coHosts}}"""), null)!!

    @Test fun aCoHostMayDriveTheMusicWhenOnlyTheHostMay() {
        val room = room("""{"c":true,"gone":false}""")
        assertEquals(setOf("c"), room.meta.coHosts)
        assertTrue(room.meta.canControl("h"))
        assertTrue(room.meta.canControl("c"))
        assertFalse(room.meta.canControl("someone"))
        assertFalse(RoomMapping.snapshotOf(room, "c").hostOnlyControl)
        assertTrue(RoomMapping.snapshotOf(room, "someone").hostOnlyControl)
    }

    @Test fun withoutHostOnlyControlEveryoneMay() {
        val meta = RoomMeta(createdAtMs = 1, hostUid = "h", maxMembers = 5)
        assertTrue(meta.canControl("anyone"))
        assertEquals(emptySet(), room("null").meta.coHosts)
    }
}
