package com.cosmotune.listening

import java.time.Instant
import java.time.ZoneId

/**
 * Backups made on a schedule into a folder the listener chose (Settings, Your data, Back up automatically): daily or weekly,
 * named by the day they were made, and only the newest [KEEP] of them kept, so the folder never fills up. Files the listener put
 * there themselves are never touched: only names this made ([isOurs]) are counted or removed.
 */
object AutoBackup {
    /** The choices, in days between backups; 0 is off. */
    val CHOICES = listOf(0, 1, 7)
    const val KEEP = 5
    private const val PREFIX = "cosmotune-auto-backup-"
    private val NAME = Regex("${PREFIX}\\d{4}-\\d{2}-\\d{2}\\.json")

    /** Whether a backup is due: on, and [everyDays] have passed since [lastMs] (never made counts as due). */
    fun due(everyDays: Int, lastMs: Long, nowMs: Long): Boolean =
        everyDays > 0 && (lastMs <= 0 || nowMs - lastMs >= everyDays * DAY_MS - SLACK_MS)

    fun fileName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        PREFIX + Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate() + ".json"

    fun isOurs(name: String): Boolean = NAME.matches(name)

    /** The backups to remove from a folder holding [names] once a new one is written: all of ours past the newest [KEEP]. */
    fun toRemove(names: List<String>): List<String> = names.filter(::isOurs).sortedDescending().drop(KEEP)

    /** "Every day" / "Every week" / "Off", for the settings line. */
    fun label(everyDays: Int): String = when (everyDays) {
        0 -> "Off"
        1 -> "Every day"
        7 -> "Every week"
        else -> "Every $everyDays days"
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
    /** A daily job that runs a little early still counts. */
    private const val SLACK_MS = 2L * 60 * 60 * 1000
}
