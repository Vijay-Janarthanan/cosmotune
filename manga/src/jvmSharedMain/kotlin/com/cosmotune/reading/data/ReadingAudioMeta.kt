package com.cosmotune.reading.data

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * What an audiobook file says about itself, read from its own bytes: title, author, cover, length and chapters. M4B/M4A files
 * (MP4) give chapters from Nero "chpl" lists or QuickTime chapter tracks; MP3 files give ID3 tags and a length from their Xing
 * header or bit rate. Nothing is decoded; a file that cannot be read gives what could be found.
 */
internal object ReadingAudioMeta {
    data class Chapter(val title: String, val startMs: Long)
    data class Info(val title: String = "", val author: String = "", val album: String = "", val durationMs: Long = 0,
        val chapters: List<Chapter> = emptyList(), val cover: ByteArray? = null)

    /** Audio files an audiobook folder can hold. */
    val extensions = setOf("m4b", "m4a", "mp4", "aac", "mp3", "ogg", "oga", "opus", "flac", "wav")
    private const val MAX_MOOV = 32L * 1024 * 1024

    /** Reads [name]'s details; [open] gives the file from its start each time it is called ([size] bytes long). */
    fun read(name: String, size: Long, open: () -> InputStream): Info = runCatching {
        when (name.substringAfterLast('.').lowercase()) {
            "m4b", "m4a", "mp4", "aac" -> mp4(size, open)
            "mp3" -> open().use { mp3(it, size) }
            else -> Info()
        }
    }.getOrDefault(Info())

    // ---- MP4 ------------------------------------------------------------------------------------------------------------------

    private class Box(val type: String, val body: ByteArray)

    private fun boxes(data: ByteArray, from: Int = 0, to: Int = data.size): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(data, at)
            val type = String(data, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L && at + 16 <= to) { size = u64(data, at + 8); header = 16 }
            else if (size == 0L) size = (to - at).toLong()
            if (size < header || at + size > to) break
            out += Box(type, data.copyOfRange(at + header, (at + size).toInt()))
            at += size.toInt()
        }
        return out
    }
    private fun List<Box>.find(type: String) = firstOrNull { it.type == type }
    private fun Box.children(skip: Int = 0) = boxes(body, skip)

    private fun mp4(size: Long, open: () -> InputStream): Info {
        // The top level: find moov wherever it is (before or after the audio) without reading the audio.
        var moov: ByteArray? = null
        open().use { input ->
            val data = DataInputStream(input)
            var at = 0L
            while (at + 8 <= size && moov == null) {
                var length = data.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also(data::readFully).toString(Charsets.ISO_8859_1)
                var header = 8L
                if (length == 1L) { length = data.readLong(); header = 16 } else if (length == 0L) length = size - at
                if (length < header) break
                if (type == "moov") {
                    require(length - header <= MAX_MOOV) { "moov too large" }
                    moov = ByteArray((length - header).toInt()).also(data::readFully)
                } else skipFully(data, length - header)
                at += length
            }
        }
        val root = Box("moov", moov ?: return Info())
        val parts = root.children()
        val mvhd = parts.find("mvhd")?.body
        val durationMs = mvhd?.let { body ->
            if (body[0].toInt() == 1) u64(body, 24).toDouble() / u32(body, 20).coerceAtLeast(1) * 1000
            else u32(body, 16).toDouble() / u32(body, 12).coerceAtLeast(1) * 1000
        }?.toLong() ?: 0L
        val udta = parts.find("udta")?.children()
        var chapters = udta?.find("chpl")?.let(::chpl).orEmpty()
        if (chapters.isEmpty()) chapters = quickTimeChapters(parts, open)
        val ilst = udta?.find("meta")?.children(4)?.find("ilst")?.children()
        fun text(type: String) = ilst?.find(type)?.children()?.find("data")?.body?.let { if (it.size > 8) String(it, 8, it.size - 8, Charsets.UTF_8).trim() else null }.orEmpty()
        val cover = ilst?.find("covr")?.children()?.find("data")?.body?.takeIf { it.size > 8 }?.let { it.copyOfRange(8, it.size) }
        return Info(text("©nam"), text("©ART").ifBlank { text("aART") }, text("©alb"), durationMs, chapters.sortedBy { it.startMs }, cover)
    }

    /** Nero chapters: a version, flags, (version 1) four reserved bytes, a count, then each start (in 100 ns) and its title. */
    private fun chpl(box: Box): List<Chapter> {
        val body = box.body
        if (body.size < 5) return emptyList()
        var at = 4 + if (body[0].toInt() != 0) 4 else 0
        val count = body[at].toInt() and 0xFF; at++
        val out = ArrayList<Chapter>()
        repeat(count) {
            if (at + 9 > body.size) return out
            val start = u64(body, at) / 10_000; at += 8
            val length = body[at].toInt() and 0xFF; at++
            if (at + length > body.size) return out
            out += Chapter(String(body, at, length, Charsets.UTF_8).trim(), start); at += length
        }
        return out
    }

    /** QuickTime chapters: the audio track points (tref/chap) at a text track whose samples are the titles, timed by its stts. */
    private fun quickTimeChapters(moov: List<Box>, open: () -> InputStream): List<Chapter> {
        val tracks = moov.filter { it.type == "trak" }.map { it.children() }
        val chapterIds = tracks.mapNotNull { it.find("tref")?.children()?.find("chap")?.body }.flatMap { body -> (0 until body.size / 4).map { u32(body, it * 4) } }.toSet()
        val track = tracks.firstOrNull { trak -> trak.find("tkhd")?.body?.let { b -> u32(b, if (b[0].toInt() == 1) 20 else 12) } in chapterIds } ?: return emptyList()
        val mdia = track.find("mdia")?.children() ?: return emptyList()
        val mdhd = mdia.find("mdhd")?.body ?: return emptyList()
        val scale = u32(mdhd, if (mdhd[0].toInt() == 1) 20 else 12).coerceAtLeast(1)
        val stbl = mdia.find("minf")?.children()?.find("stbl")?.children() ?: return emptyList()
        val stts = stbl.find("stts")?.body ?: return emptyList()
        val starts = ArrayList<Long>()
        var time = 0L
        for (entry in 0 until u32(stts, 4).toInt()) {
            val count = u32(stts, 8 + entry * 8); val delta = u32(stts, 12 + entry * 8)
            repeat(count.toInt().coerceAtMost(10_000)) { starts += time * 1000 / scale; time += delta }
        }
        val stsz = stbl.find("stsz")?.body ?: return emptyList()
        val fixed = u32(stsz, 4); val sampleCount = u32(stsz, 8).toInt().coerceAtMost(10_000)
        val sizes = (0 until sampleCount).map { if (fixed > 0) fixed else u32(stsz, 12 + it * 4) }
        val offsets = sampleOffsets(stbl, sizes) ?: return emptyList()
        // The titles live with the audio data: one pass through the file, in order, for the few bytes each one is.
        val order = offsets.indices.sortedBy { offsets[it] }
        val titles = arrayOfNulls<String>(offsets.size)
        open().use { input ->
            var at = 0L
            for (index in order) {
                val offset = offsets[index]; val length = sizes[index].toInt().coerceIn(0, 1024)
                if (offset < at || length < 2) continue
                skipFully(input, offset - at)
                val sample = ByteArray(length).also { DataInputStream(input).readFully(it) }
                at = offset + length
                val textLength = ((sample[0].toInt() and 0xFF) shl 8) or (sample[1].toInt() and 0xFF)
                titles[index] = String(sample, 2, textLength.coerceAtMost(length - 2), Charsets.UTF_8).trim()
            }
        }
        return offsets.indices.mapNotNull { i -> titles[i]?.let { Chapter(it, starts.getOrElse(i) { 0L }) } }
    }

    private fun sampleOffsets(stbl: List<Box>, sizes: List<Long>): List<Long>? {
        val chunks = stbl.find("stco")?.body?.let { b -> (0 until u32(b, 4).toInt()).map { u32(b, 8 + it * 4) } }
            ?: stbl.find("co64")?.body?.let { b -> (0 until u32(b, 4).toInt()).map { u64(b, 8 + it * 8) } } ?: return null
        val stsc = stbl.find("stsc")?.body ?: return null
        val runs = (0 until u32(stsc, 4).toInt()).map { Pair(u32(stsc, 8 + it * 12).toInt(), u32(stsc, 12 + it * 12).toInt()) }
        val out = ArrayList<Long>()
        var sample = 0
        for (chunk in chunks.indices) {
            val perChunk = runs.lastOrNull { it.first <= chunk + 1 }?.second ?: 1
            var offset = chunks[chunk]
            repeat(perChunk) { if (sample < sizes.size) { out += offset; offset += sizes[sample]; sample++ } }
        }
        return out
    }

    // ---- MP3 ------------------------------------------------------------------------------------------------------------------

    private fun mp3(input: InputStream, size: Long): Info {
        val head = input.readNBytes(256 * 1024)
        var title = ""; var author = ""; var album = ""; var cover: ByteArray? = null
        var audioStart = 0
        if (head.size >= 10 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            val major = head[3].toInt()
            val tagSize = syncsafe(head, 6)
            audioStart = 10 + tagSize
            var at = 10
            val end = minOf(audioStart, head.size)
            while (at + 10 <= end) {
                val id = String(head, at, 4, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                val length = if (major >= 4) syncsafe(head, at + 4) else u32(head, at + 4).toInt()
                if (length <= 0 || at + 10 + length > end) break
                val body = head.copyOfRange(at + 10, at + 10 + length)
                when (id) {
                    "TIT2" -> title = id3Text(body); "TPE1" -> author = id3Text(body); "TALB" -> album = id3Text(body)
                    "APIC" -> cover = apic(body)
                }
                at += 10 + length
            }
        }
        return Info(title, author, album, mp3Duration(head, audioStart, size), cover = cover)
    }

    /** The length from the first frame: its Xing/Info frame count, else the file size at its bit rate. */
    internal fun mp3Duration(head: ByteArray, from: Int, size: Long): Long {
        var at = from.coerceAtLeast(0)
        while (at + 4 <= head.size) {
            if ((head[at].toInt() and 0xFF) == 0xFF && (head[at + 1].toInt() and 0xE0) == 0xE0) break
            at++
        }
        if (at + 4 > head.size) return 0
        val b1 = head[at + 1].toInt() and 0xFF; val b2 = head[at + 2].toInt() and 0xFF; val b3 = head[at + 3].toInt() and 0xFF
        val version = (b1 shr 3) and 3 // 3: MPEG-1, 2: MPEG-2, 0: MPEG-2.5
        val bitrateIndex = b2 shr 4; val rateIndex = (b2 shr 2) and 3
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return 0
        val mpeg1 = version == 3
        val bitrate = (if (mpeg1) intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            else intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160))[bitrateIndex] * 1000
        val rate = intArrayOf(44100, 48000, 32000)[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val mono = (b3 shr 6) == 3
        val side = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        val xing = at + 4 + side
        if (xing + 12 <= head.size) {
            val tag = String(head, xing, 4, Charsets.ISO_8859_1)
            if ((tag == "Xing" || tag == "Info") && (u32(head, xing + 4) and 1L) != 0L) {
                val frames = u32(head, xing + 8)
                return frames * (if (mpeg1) 1152 else 576) * 1000 / rate
            }
        }
        return ((size - at).coerceAtLeast(0) * 8 * 1000) / bitrate
    }

    private fun id3Text(body: ByteArray): String {
        if (body.isEmpty()) return ""
        val charset = when (body[0].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        return String(body, 1, body.size - 1, charset).trim('\u0000', ' ', '﻿')
    }

    private fun apic(body: ByteArray): ByteArray? {
        if (body.size < 4) return null
        val wide = body[0].toInt() == 1 || body[0].toInt() == 2
        var at = 1
        while (at < body.size && body[at].toInt() != 0) at++ // MIME type
        at += 2 // its end and the picture type
        if (wide) { while (at + 1 < body.size && !(body[at].toInt() == 0 && body[at + 1].toInt() == 0)) at += 2; at += 2 }
        else { while (at < body.size && body[at].toInt() != 0) at++; at++ }
        return body.takeIf { at < it.size }?.copyOfRange(at, body.size)
    }

    // ---- Bytes ----------------------------------------------------------------------------------------------------------------

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) { if (input.read() < 0) throw EOFException(); left-- } else left -= skipped
        }
    }
    private fun u32(b: ByteArray, at: Int): Long = if (at + 4 > b.size) 0 else
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)
}
