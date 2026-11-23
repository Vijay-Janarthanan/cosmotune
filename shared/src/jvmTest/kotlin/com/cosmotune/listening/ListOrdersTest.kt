package com.cosmotune.listening

import kotlin.test.Test
import kotlin.test.assertEquals

class ListOrdersTest {
    @Test fun eachPageKeepsItsOwnOrder() {
        var orders = ListOrders.with(emptyMap(), "VLPLa", "TITLE")
        orders = ListOrders.with(orders, "MPREb", "DURATION")
        orders = ListOrders.with(orders, "VLPLa", "ARTIST")
        assertEquals(mapOf("MPREb" to "DURATION", "VLPLa" to "ARTIST"), ListOrders.decode(ListOrders.encode(orders)))
        assertEquals(listOf("MPREb", "VLPLa"), orders.keys.toList(), "the most recent last")
        assertEquals(mapOf("VLPLa" to "ARTIST"), ListOrders.with(orders, "MPREb", null))
    }

    @Test fun theOldestAreForgottenPastTheLimit() {
        var orders = emptyMap<String, String>()
        repeat(ListOrders.MAX + 3) { orders = ListOrders.with(orders, "id$it", "TITLE") }
        assertEquals(ListOrders.MAX, orders.size)
        assertEquals("id3", orders.keys.first())
    }
}
