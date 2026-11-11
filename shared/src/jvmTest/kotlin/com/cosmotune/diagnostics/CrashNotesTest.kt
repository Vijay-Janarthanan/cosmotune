package com.cosmotune.diagnostics

import java.nio.file.Files
import kotlin.test.*

class CrashNotesTest {
    @Test fun theNoteKeepsTypesAndFramesButNeverMessagesAndStaysOnTheDevice() {
        val dir = Files.createTempDirectory("crash-notes").toFile()
        try {
            CrashNotes.configure(dir)
            val failure = IllegalStateException("token=secret https://media.example.org/song.flac",
                java.io.IOException("Authorization: Bearer private"))
            CrashNotes.record(failure)
            val note = assertNotNull(CrashNotes.note.value)
            assertTrue("java.lang.IllegalStateException" in note && "java.io.IOException" in note)
            assertTrue("  at " in note)
            for (secret in listOf("secret", "media.example.org", "Bearer", "private")) assertFalse(secret in note, secret)
            assertTrue(note.length <= 16_384)
            // It survives a restart, and is gone once deleted.
            CrashNotes.configure(dir)
            assertEquals(note, CrashNotes.note.value)
            CrashNotes.clear()
            assertNull(CrashNotes.note.value)
            assertFalse(dir.resolve("last-crash.txt").exists())
        } finally { CrashNotes.clear(); dir.deleteRecursively() }
    }

    @Test fun aCauseCycleEnds() {
        val dir = Files.createTempDirectory("crash-cycle").toFile()
        try {
            CrashNotes.configure(dir)
            val a = RuntimeException("a"); val b = RuntimeException("b", a)
            a.initCause(b)
            CrashNotes.record(a)
            assertEquals(2, CrashNotes.note.value!!.lines().count { it == "java.lang.RuntimeException" })
        } finally { CrashNotes.clear(); dir.deleteRecursively() }
    }
}
