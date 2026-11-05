package com.cosmotune.manga.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.*

class RequestPacerTest {
    @Test fun aBurstCannotAdmitMoreThanItsWindowAllows() {
        val gate = RequestPacer(2, 40, TimeUnit.MILLISECONDS)
        val start = System.nanoTime()
        gate.acquire(); gate.acquire(); gate.acquire()
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(35))
    }
    @Test fun cancellationDoesNotWaitForTheQuotaToExpire() {
        val gate = RequestPacer(1, 1, TimeUnit.DAYS)
        gate.acquire()
        assertFailsWith<IOException> { gate.acquire { true } }
    }
    @Test fun invalidExtensionQuotasAreRejected() {
        assertFailsWith<IllegalArgumentException> { RequestPacer(0, 1, TimeUnit.SECONDS) }
        assertFailsWith<IllegalArgumentException> { RequestPacer(1, 0, TimeUnit.SECONDS) }
    }
}
