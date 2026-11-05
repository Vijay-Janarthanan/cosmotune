package com.cosmotune.desktop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {
    @Test fun repeatedLaunchesActivateOneOwner() = withDirectory { directory ->
        val activations = AtomicInteger()
        DesktopSingleInstance.acquire(directory) { activations.incrementAndGet() }!!.use {
            repeat(4) { assertNull(DesktopSingleInstance.acquire(directory) { error("Second owner") }) }
            assertEquals(4, activations.get())
        }
    }

    @Test fun concurrentLaunchesElectOneOwner() = withDirectory { directory ->
        val start = CountDownLatch(1)
        val activations = AtomicInteger()
        val executor = Executors.newFixedThreadPool(6)
        val owners = mutableListOf<DesktopSingleInstance>()
        try {
            val launches = (1..6).map {
                executor.submit<DesktopSingleInstance?> {
                    start.await()
                    DesktopSingleInstance.acquire(directory) { activations.incrementAndGet() }
                }
            }
            start.countDown()
            launches.forEach { it.get(15, TimeUnit.SECONDS)?.let(owners::add) }
            assertEquals(1, owners.size)
            assertEquals(5, activations.get())
        } finally {
            owners.forEach { it.close() }
            executor.shutdownNow()
        }
    }

    @Test fun closedOwnerCanBeReplacedDespiteStaleEndpoint() = withDirectory { directory ->
        val first = assertNotNull(DesktopSingleInstance.acquire(directory) {})
        first.close()
        first.close()
        // These files survive crashes; ownership comes from the OS lock, not their existence.
        assertTrue(Files.exists(directory.resolve("instance.lock")))
        Files.writeString(directory.resolve("instance.endpoint"), "invalid stale contents")
        assertNotNull(DesktopSingleInstance.acquire(directory) {}).use { }
    }

    @Test fun activationFailureDoesNotDisableListener() = withDirectory { directory ->
        val calls = AtomicInteger()
        DesktopSingleInstance.acquire(directory) {
            if (calls.incrementAndGet() == 1) error("Window is not ready")
        }!!.use {
            assertNull(DesktopSingleInstance.acquire(directory) { error("Second owner") })
            assertEquals(2, calls.get()) // The secondary retries until the owner acknowledges it.
        }
    }

    private fun withDirectory(block: (java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("cosmotune-instance-test")
        try { block(directory) } finally { directory.toFile().deleteRecursively() }
    }
}
