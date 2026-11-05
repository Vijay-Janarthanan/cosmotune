package com.cosmotune.music.data.sources

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProviderTransferGateTest {
    @Test fun capacityAndStartSpacingAreIndependentForEachProvider() = runTest {
        val gate = ProviderTransferGate(2, 500) { testScheduler.currentTime }
        val starts = mutableListOf<Pair<String, Long>>()
        var firstActive = 0
        var maximum = 0
        val jobs = (1..5).map {
            launch { gate.transfer("first") {
                starts += "first" to testScheduler.currentTime
                firstActive++; maximum = maxOf(maximum, firstActive)
                delay(1_200); firstActive--
            } }
        }
        launch { gate.transfer("other") { starts += "other" to testScheduler.currentTime } }
        jobs.joinAll()
        assertEquals(2, maximum)
        assertEquals(0L, starts.first { it.first == "other" }.second)
        val times = starts.filter { it.first == "first" }.map { it.second }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 500 })
    }

    @Test fun aCancelledWaiterCannotConsumeCapacityOrDelayAnotherProvider() = runTest {
        val gate = ProviderTransferGate(1, 500) { testScheduler.currentTime }
        val held = launch { gate.transfer("first") { delay(2_000) } }
        val cancelled = launch { gate.transfer("first") { fail("Cancelled waiter ran") } }
        yield(); cancelled.cancelAndJoin(); held.join()
        var finished = false
        gate.transfer("first") { finished = true }
        assertTrue(finished)
    }
}
