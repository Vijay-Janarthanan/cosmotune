package com.cosmotune

/**
 * Compares release versions for both update checkers (`AppUpdateChecker` on Android, `DesktopUpdateChecker` on the
 * desktop): numeric and dot by dot ("1.10" outranks "1.9"), with a "-suffix" build ("1.0.0-beta2", "1.0.0-alpha1")
 * counted as older than the plain release of the same numbers, since it was made on the way to that release.
 * Without that rule a beta and the release it led to would compare equal and testers would never be offered the
 * real build. Pure Kotlin, so `ReleaseVersionTest` runs it on the desktop JVM.
 */
object ReleaseVersion {

    private class Parsed(val parts: List<Int>, val preRelease: Boolean)

    private fun parse(raw: String): Parsed {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return Parsed(base.split('.').map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /** Whether [latest] is newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.preRelease && !l.preRelease
    }
}
