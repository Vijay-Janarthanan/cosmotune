package com.cosmotune.podio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodioFeedLoginsTest {
    @Test fun theLoginComesOutOfTheAddress() {
        val (clean, login) = PodioFeedLogins.split("https://ann:p%40ss:word@private.example:8443/feed.xml?key=1")
        assertEquals("https://private.example:8443/feed.xml?key=1", clean)
        assertEquals(PodioFeedLogin("ann", "p@ss:word"), login)
        assertEquals("https://open.example/feed" to null, PodioFeedLogins.split("https://open.example/feed"))
    }

    @Test fun requestsToTheFeedsServerCarryIt() {
        val login = PodioFeedLogin("ann", "secret")
        val logins = mapOf("https://private.example/feed.xml" to login)
        assertEquals(login, PodioFeedLogins.forUrl("https://private.example/feed.xml", logins))
        assertEquals(login, PodioFeedLogins.forUrl("https://private.example/chapters/1.json", logins))
        assertNull(PodioFeedLogins.forUrl("https://cdn.example/1.mp3", logins))
        assertEquals("Basic YW5uOnNlY3JldA==", PodioFeedLogins.header(login))
    }
}
