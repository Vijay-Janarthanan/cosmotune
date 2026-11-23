package com.cosmotune.podio

import java.io.File
import java.io.RandomAccessFile

/**
 * Chapters written into a downloaded episode's own ID3v2 tag (CHAP frames, with a TIT2 title inside each). Reads the tag at the
 * start of the file only, never the audio, and never throws: a file with no tag, or a damaged one, has no chapters.
 */
object Id3Chapters {
    private const val MAX_TAG = 8 * 1024 * 1024

    fun readFile(file: File): List<PodcastChapter> = try {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(10)
            input.readFully(header)
            val size = tagSize(header) ?: return emptyList()
            val bytes = ByteArray(10 + minOf(size, MAX_TAG))
            header.copyInto(bytes)
            input.readFully(bytes, 10, bytes.size - 10)
            read(bytes)
        }
    } catch (_: Exception) { emptyList() }

    private fun tagSize(header: ByteArray): Int? {
        if (header.size < 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        if (header[3].toInt() !in 3..4) return null
        return synchsafe(header, 6).takeIf { it in 0..MAX_TAG }
    }

    fun read(bytes: ByteArray): List<PodcastChapter> {
        if (bytes.size < 10 || tagSize(bytes) == null) return emptyList()
        val version = bytes[3].toInt()
        val end = minOf(bytes.size, 10 + synchsafe(bytes, 6))
        var at = 10
        if (bytes[5].toInt() and 0x40 != 0 && at + 4 <= end) {
            at += if (version == 4) synchsafe(bytes, at) else 4 + int(bytes, at)
        }
        val chapters = ArrayList<PodcastChapter>()
        try {
            while (at + 10 <= end && bytes[at].toInt() != 0 && chapters.size < 5_000) {
                val id = String(bytes, at, 4, Charsets.ISO_8859_1)
                val size = if (version == 4) synchsafe(bytes, at + 4) else int(bytes, at + 4)
                val body = at + 10
                if (size < 0 || body + size > end) break
                if (id == "CHAP") chapter(bytes, body, body + size, version)?.let { chapters += it }
                at = body + size
            }
        } catch (_: IndexOutOfBoundsException) {}
        return chapters.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    private fun chapter(bytes: ByteArray, from: Int, end: Int, version: Int): PodcastChapter? {
        var at = from
        while (at < end && bytes[at].toInt() != 0) at++
        at++
        if (at + 16 > end) return null
        val start = int(bytes, at).toLong() and 0xFFFFFFFFL
        if (start > 604_800_000L) return null
        at += 16
        var title: String? = null
        while (at + 10 <= end && bytes[at].toInt() != 0) {
            val id = String(bytes, at, 4, Charsets.ISO_8859_1)
            val size = if (version == 4) synchsafe(bytes, at + 4) else int(bytes, at + 4)
            val body = at + 10
            if (size < 1 || body + size > end) break
            if (id == "TIT2" && title == null) title = text(bytes, body, body + size)
            at = body + size
        }
        return title?.takeIf { it.isNotBlank() }?.let { PodcastChapter(start, it.take(300)) }
    }

    private fun text(bytes: ByteArray, from: Int, end: Int): String {
        val charset = when (bytes[from].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(bytes, from + 1, end - from - 1, charset).trim('\u0000', ' ', '﻿').trim()
    }

    private fun synchsafe(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0x7f shl 21) or (b[at + 1].toInt() and 0x7f shl 14) or (b[at + 2].toInt() and 0x7f shl 7) or (b[at + 3].toInt() and 0x7f)
    private fun int(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff shl 24) or (b[at + 1].toInt() and 0xff shl 16) or (b[at + 2].toInt() and 0xff shl 8) or (b[at + 3].toInt() and 0xff)
}
