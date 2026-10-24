package com.cosmotune.music.data.downloads

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadRegistryRepairTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun file(path: String, bytes: Int = 100) = File(temporary.root, path).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes) { 1 }) }

    @Test fun movedFilesAreFoundAndAmbiguousOrMissingOnesAreLeftForTheListener() {
        val moved = file("Music/Rahman/Kannalane.flac")
        file("Music/A/Twice.mp3"); file("Music/B/twice.MP3") // two candidates: not guessed
        val claimed = file("Downloads/Claimed.m4a")
        file("Music/empty.ogg", bytes = 0) // an empty file is not a copy
        file("Music/notes/Kannalane.flac.txt")
        val missing = listOf(
            DownloadRegistryRepair.Missing("a", "kannalane.FLAC"),
            DownloadRegistryRepair.Missing("b", "Twice.mp3"),
            DownloadRegistryRepair.Missing("c", "Gone.opus"),
            DownloadRegistryRepair.Missing("d", "Claimed.m4a"),
            DownloadRegistryRepair.Missing("e", "empty.ogg"),
        )
        val plan = DownloadRegistryRepair.plan(missing,
            DownloadRegistryRepair.candidatesUnder(listOf(File(temporary.root, "Music"), File(temporary.root, "Downloads"), File(temporary.root, "Music"))),
            claimed = setOf(claimed.canonicalPath))
        assertEquals(mapOf("a" to moved.canonicalPath), plan.relocated.mapValues { it.value.canonicalPath })
        assertEquals(listOf("b"), plan.ambiguous)
        assertEquals(setOf("c", "d", "e"), plan.unresolved.toSet())
    }

    @Test fun twoRecordsWantingOneFileGetNeitherAndTheWalkIsBounded() {
        file("Music/Song.flac")
        val plan = DownloadRegistryRepair.plan(
            listOf(DownloadRegistryRepair.Missing("x", "Song.flac"), DownloadRegistryRepair.Missing("y", "song.flac")),
            DownloadRegistryRepair.candidatesUnder(listOf(temporary.root)), emptySet())
        assertTrue(plan.relocated.isEmpty()); assertEquals(setOf("x", "y"), plan.ambiguous.toSet())
        repeat(5) { file("Many/$it.mp3") }
        assertEquals(3, DownloadRegistryRepair.candidatesUnder(listOf(File(temporary.root, "Many")), maxFiles = 3).count())
        assertTrue(DownloadRegistryRepair.plan(emptyList(), emptySequence(), emptySet()).relocated.isEmpty())
    }
}
