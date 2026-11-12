package com.cosmotune.update

/** What asks for an update check: the app starting, the running app's timer, or the app coming back (from the tray, to the front). */
enum class UpdateTrigger { LAUNCH, PERIODIC, RETURNED }

/**
 * When Cosmotune looks for a new release, on every platform: always at launch; while it runs, every [PERIOD_MS]; when it comes back
 * to the person, once [MIN_GAP_MS] has passed. The last attempt counts whether it found an answer or failed (offline, rate limited),
 * so a failing check is not retried in a loop but is tried again later.
 */
object UpdateCheckSchedule {
    const val MIN_GAP_MS = 3_600_000L
    const val PERIOD_MS = 21_600_000L

    fun shouldCheck(nowMs: Long, lastAttemptMs: Long?, trigger: UpdateTrigger): Boolean {
        if (lastAttemptMs == null || trigger == UpdateTrigger.LAUNCH) return true
        val since = nowMs - lastAttemptMs
        return when (trigger) {
            UpdateTrigger.RETURNED -> since >= MIN_GAP_MS
            UpdateTrigger.PERIODIC -> since >= PERIOD_MS
            UpdateTrigger.LAUNCH -> true
        }
    }
}
