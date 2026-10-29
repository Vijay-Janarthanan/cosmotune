package com.cosmotune.ui

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs a test again, up to [attempts] times in all, when it failed with the one error Compose's own test harness raises when its
 * render loop and the UI thread touch the snapshot observer at the same moment ("Detected multithreaded access to
 * SnapshotStateObserver"). It is a race inside the harness's frame loop, seen on slow macOS runners, and it says nothing about the
 * code under test. Any other failure, an assertion above all, is reported on the first attempt.
 *
 * Put it outermost, around the rules that create the compose environment, so that each attempt starts a fresh one.
 */
class RetryOnComposeHarnessRace(private val attempts: Int = 3) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var attempt = 1
            while (true) {
                try {
                    base.evaluate()
                    return
                } catch (failure: Throwable) {
                    if (attempt >= attempts || !isHarnessRace(failure)) throw failure
                    System.err.println("${description.displayName}: Compose's test harness raced (attempt $attempt of $attempts), running it again")
                    attempt++
                }
            }
        }
    }

    internal companion object {
        const val MARKER = "Detected multithreaded access to SnapshotStateObserver"

        fun isHarnessRace(failure: Throwable): Boolean {
            val seen = HashSet<Throwable>()
            var current: Throwable? = failure
            while (current != null && seen.add(current)) {
                if (current.message?.contains(MARKER) == true) return true
                current.suppressed.forEach { if (isHarnessRace(it)) return true }
                current = current.cause
            }
            return false
        }
    }
}
