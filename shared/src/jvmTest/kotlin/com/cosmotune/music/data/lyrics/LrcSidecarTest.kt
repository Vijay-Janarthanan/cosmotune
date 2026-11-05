package com.cosmotune.music.data.lyrics

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class LrcSidecarTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sameNameLrcBesideAudioIsReadWithoutItsByteOrderMark() {
        val audio = temporary.newFile("01 Kannalane.flac")
        File(temporary.root, "01 Kannalane.lrc").writeText("﻿[ar:Chitra]\n[00:12.30]Kannalane\n")
        assertEquals("[ar:Chitra]\n[00:12.30]Kannalane\n", LrcSidecar.read(audio))
        assertEquals("[00:12.30]Kannalane", LrcSidecar.read(audio)!!.lines()[1])
    }

    @Test fun metadataOnlyEmptyOversizedAndUnrelatedFilesAreIgnored() {
        val audio = temporary.newFile("song.mp3")
        assertNull(LrcSidecar.read(audio))
        File(temporary.root, "song.lrc").writeText("[ar:Someone]\n[ti:Title]\n")
        assertNull(LrcSidecar.read(audio))
        File(temporary.root, "song.lrc").writeText("")
        assertNull(LrcSidecar.read(audio))
        File(temporary.root, "song.lrc").writeBytes(ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() })
        assertNull(LrcSidecar.read(audio))
        File(temporary.root, "other.lrc").writeText("[00:01.00]Other")
        assertNull(LrcSidecar.read(temporary.newFile("third.flac")))
        assertNull(LrcSidecar.fileFor(File(temporary.root, "playlist.m3u8")))
        File(temporary.root, "plain.lrc").writeText("Unsynced first line\nSecond line")
        assertEquals("Unsynced first line\nSecond line", LrcSidecar.read(temporary.newFile("plain.ogg")))
    }
}
