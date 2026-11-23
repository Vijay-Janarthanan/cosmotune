package com.cosmotune.video.library

/**
 * Settings, Library, Automatic backup: every day or every week the whole library (the same file as Export) is written to the
 * backups folder, and only the newest [KEEP] backups are kept there.
 */
object AutoBackup {
    const val KEEP = 5
    const val PREFIX = "cosmotune-video-backup-"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** The choices in days: off, every day, every week. */
    val CHOICES = listOf(0, 1, 7)

    fun isDue(everyDays: Int, lastMs: Long, nowMs: Long): Boolean = everyDays > 0 && nowMs - lastMs >= everyDays * DAY_MS - DAY_MS / 24

    /** The file for the backup made on [isoDate] ("2026-11-23"). */
    fun fileName(isoDate: String): String = "$PREFIX$isoDate.json"

    /** Of the files in the folder, the old backups to delete so [KEEP] remain (other files are never touched). */
    fun toDelete(names: List<String>): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sortedDescending().drop(KEEP)
}
