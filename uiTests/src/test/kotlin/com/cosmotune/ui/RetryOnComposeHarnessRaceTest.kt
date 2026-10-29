package com.cosmotune.ui

import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryOnComposeHarnessRaceTest {
    private val description = Description.createTestDescription("Sample", "case")

    private fun run(rule: RetryOnComposeHarnessRace, body: (attempt: Int) -> Unit): Int {
        var attempts = 0
        rule.apply(object : Statement() { override fun evaluate() { attempts++; body(attempts) } }, description).evaluate()
        return attempts
    }

    private fun race() = IllegalArgumentException("${RetryOnComposeHarnessRace.MARKER}: previousThreadId=41")

    @Test fun theHarnessRaceIsRunAgainUntilItPasses() {
        assertEquals(3, run(RetryOnComposeHarnessRace(3)) { if (it < 3) throw race() })
    }

    @Test fun theRaceIsFoundBehindAWrappingException() {
        assertEquals(2, run(RetryOnComposeHarnessRace(3)) { if (it < 2) throw RuntimeException("render loop", race()) })
    }

    @Test fun aRaceThatKeepsHappeningIsReportedAfterTheLastAttempt() {
        var attempts = 0
        val failure = assertFailsWith<IllegalArgumentException> { run(RetryOnComposeHarnessRace(2)) { attempts++; throw race() } }
        assertTrue(failure.message!!.contains(RetryOnComposeHarnessRace.MARKER))
        assertEquals(2, attempts)
    }

    @Test fun anAssertionFailureIsNeverRetried() {
        var attempts = 0
        assertFailsWith<AssertionError> { run(RetryOnComposeHarnessRace(3)) { attempts++; throw AssertionError("zoom is wrong") } }
        assertEquals(1, attempts)
    }
}
