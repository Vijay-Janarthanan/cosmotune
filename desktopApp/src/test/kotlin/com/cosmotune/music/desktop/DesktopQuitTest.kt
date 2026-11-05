package com.cosmotune.music.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopQuitTest {
    private val defaultEnd = DesktopQuit.end
    private val defaultForce = DesktopQuit.force
    private val defaultDeadline = DesktopQuit.deadlineMs

    @BeforeTest fun fresh() = DesktopQuit.reset()

    @AfterTest fun restore() {
        DesktopQuit.reset()
        DesktopQuit.end = defaultEnd
        DesktopQuit.force = defaultForce
        DesktopQuit.deadlineMs = defaultDeadline
    }

    @Test fun anOrderlyQuitRunsTheNativeShutdownThenEndsTheProcessOnce() {
        val steps = mutableListOf<String>()
        DesktopQuit.bind { steps += "shutdown" }
        DesktopQuit.end = { steps += "end" }
        DesktopQuit.force = { steps += "FORCED" }
        DesktopQuit.deadlineMs = 5_000
        DesktopQuit.now("test")
        DesktopQuit.now("a second request")
        assertEquals(listOf("shutdown", "end"), steps)
    }

    @Test fun aShutdownThatDoesNotReturnIsEndedByForce() {
        val forced = CountDownLatch(1)
        val release = CountDownLatch(1)
        DesktopQuit.bind { release.await(10, TimeUnit.SECONDS) }
        DesktopQuit.end = {}
        DesktopQuit.force = { forced.countDown() }
        DesktopQuit.deadlineMs = 300
        val caller = Thread { DesktopQuit.now("stalled") }.apply { isDaemon = true; start() }
        assertTrue(forced.await(3, TimeUnit.SECONDS), "the process was not ended when the shutdown stalled")
        release.countDown()
        caller.join(2_000)
    }

    @Test fun aShutdownThatFailsStillEndsTheProcess() {
        val ended = AtomicInteger()
        DesktopQuit.bind { error("native library refused") }
        DesktopQuit.end = { ended.incrementAndGet() }
        DesktopQuit.force = {}
        DesktopQuit.deadlineMs = 5_000
        DesktopQuit.now("failing")
        assertEquals(1, ended.get())
    }

    /** The deadline of a quit that finished must not fire later: in a test run it would end the whole test process. */
    @Test fun aFinishedQuitLeavesNoForcedEndBehind() {
        val forced = AtomicInteger()
        DesktopQuit.bind {}
        DesktopQuit.end = {}
        DesktopQuit.force = { forced.incrementAndGet() }
        DesktopQuit.deadlineMs = 200
        DesktopQuit.now("finished")
        Thread.sleep(700)
        assertEquals(0, forced.get())
    }
}
