package com.cosmotune.video.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Cuts stretches out of an MP4 or M4A without re-encoding, on the phone and the desktop alike: it reads every track's samples
 * (from the sample tables, or from the fragments of a file served in pieces), drops the samples inside the cuts (each cut
 * snapped by [SegmentCuts.snap] so the picture starts again on a keyframe), and writes a plain MP4 again: `ftyp`, a new
 * `moov` whose tables list the samples kept (one sample per chunk) and one `mdat` with their bytes in the order they had.
 * Durations in `mvhd`, `tkhd`, `mdhd` and the edit list follow. A layout it does not know answers null and the caller keeps
 * the file as it was.
 */
object Mp4Cut {
    private const val MAX_BOX = 64L shl 20

    /** Writes [input] without [ranges] to [output]; the cuts made, or null when nothing was written. */
    fun cut(input: File, output: File, ranges: List<CutRange>): List<CutRange>? = try {
        output.delete()
        RandomAccessFile(input, "r").use { write(it, output, ranges) }.also { if (it == null) output.delete() }
    } catch (_: Exception) {
        output.delete()
        null
    }

    private class Track(val trak: Mp4Embed.Box, val handler: String, val timescale: Long, val stsd: ByteArray) {
        val offsets = Longs()
        val sizes = Longs()
        val durations = Longs()
        var cto: Longs? = null
        var sync: BooleanArray? = null
        val syncList = ArrayList<Boolean>()
        var cttsVersion = 0
        var keep: BooleanArray = BooleanArray(0)
    }

    private class Longs {
        var a = LongArray(1024)
        var size = 0
        fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
    }

    private fun write(raf: RandomAccessFile, output: File, ranges: List<CutRange>): List<CutRange>? {
        val top = Mp4Embed.top(raf)
        val moovBox = top.singleOrNull { it.type == "moov" } ?: return null
        require(moovBox.size <= MAX_BOX)
        val m = read(raf, moovBox)
        val kids = Mp4Embed.children(m, moovBox.header, m.size)
        val mvhd = kids.first { it.type == "mvhd" }
        val movieTimescale = if (m[mvhd.content.toInt()].toInt() == 1) u32(m, mvhd.content.toInt() + 20) else u32(m, mvhd.content.toInt() + 12)
        val fragmented = top.any { it.type == "moof" }

        val tracks = kids.filter { it.type == "trak" }.map { trak -> track(m, trak) }
        if (fragmented) readFragments(raf, top, m, kids, tracks) else tracks.forEach { readTables(m, it) }
        if (tracks.isEmpty() || tracks.any { it.sizes.size != it.durations.size || it.offsets.size != it.sizes.size }) return null

        // the cuts, snapped on the picture (or the sound when there is no picture)
        val ref = tracks.firstOrNull { it.handler == "vide" } ?: tracks.firstOrNull { it.handler == "soun" } ?: return null
        val refTimes = times(ref)
        val refEnd = (0 until ref.durations.size).sumOf { ref.durations[it] } * 1000 / ref.timescale
        val cuts = SegmentCuts.snap(refTimes, ref.sync, ranges, refEnd)
        if (cuts.isEmpty()) return emptyList()
        for (t in tracks) {
            val times = times(t)
            t.keep = BooleanArray(times.size) { !SegmentCuts.isCut(times[it], cuts) }
        }
        if (ref.keep.none { it }) return null

        // the samples kept, in the order of their bytes in the file
        val order = ArrayList<IntArray>() // track, sample
        tracks.forEachIndexed { ti, t -> for (i in t.keep.indices) if (t.keep[i]) order += intArrayOf(ti, i) }
        order.sortBy { tracks[it[0]].offsets[it[1]] }
        val total = order.sumOf { tracks[it[0]].sizes[it[1]] }
        val wide = total > 0xF0000000L

        val stcoAt = IntArray(tracks.size)
        val newDurations = tracks.map { t -> (t.keep.indices).filter { t.keep[it] }.sumOf { t.durations[it] } }
        val movieDuration = tracks.indices.maxOf { newDurations[it] * movieTimescale / tracks[it].timescale }
        val out = Out()
        out.box("moov") {
            for (kid in kids) when (kid.type) {
                "mvhd" -> bytes(patchDuration(m, kid, movieDuration))
                "trak" -> {
                    val ti = tracks.indexOfFirst { it.trak === kid }
                    trak(m, tracks[ti], newDurations[ti], movieTimescale, wide) { stcoAt[ti] = it }
                }
                "mvex" -> Unit
                else -> bytes(m, kid.offset.toInt(), kid.size.toInt())
            }
        }
        val moov = out.toByteArray()
        val ftyp = top.firstOrNull { it.type == "ftyp" }?.let { read(raf, it) } ?: ByteArray(0)
        val mdatHeader = if (total + 8 > 0xFFFFFFFFL) 16 else 8
        var at = ftyp.size.toLong() + moov.size + mdatHeader
        val next = IntArray(tracks.size)
        for (o in order) {
            val ti = o[0]
            val pos = stcoAt[ti] + next[ti] * (if (wide) 8 else 4)
            if (wide) put64(moov, pos, at) else put32(moov, pos, at)
            next[ti]++
            at += tracks[ti].sizes[o[1]]
        }

        FileOutputStream(output).use { stream ->
            stream.write(ftyp)
            stream.write(moov)
            stream.write(Out().apply { if (mdatHeader == 16) { u32(1); type("mdat"); u64(total + 16) } else { u32(total + 8); type("mdat") } }.toByteArray())
            val channel = stream.channel
            val source = raf.channel
            var i = 0
            while (i < order.size) {
                val start = tracks[order[i][0]].offsets[order[i][1]]
                var end = start + tracks[order[i][0]].sizes[order[i][1]]
                var j = i + 1
                while (j < order.size && tracks[order[j][0]].offsets[order[j][1]] == end) { end += tracks[order[j][0]].sizes[order[j][1]]; j++ }
                var done = 0L
                while (done < end - start) done += source.transferTo(start + done, end - start - done, channel)
                i = j
            }
        }
        check(output.length() == at) { "a short file" }
        return cuts
    }

    private fun read(raf: RandomAccessFile, box: Mp4Embed.Box): ByteArray {
        require(box.size <= MAX_BOX)
        return ByteArray(box.size.toInt()).also { raf.seek(box.offset); raf.readFully(it) }
    }

    private fun child(b: ByteArray, parent: Mp4Embed.Box, type: String): Mp4Embed.Box? =
        Mp4Embed.children(b, parent.content.toInt(), parent.end.toInt()).firstOrNull { it.type == type }

    private fun path(b: ByteArray, from: Mp4Embed.Box, vararg types: String): Mp4Embed.Box? =
        types.fold(from as Mp4Embed.Box?) { box, type -> box?.let { child(b, it, type) } }

    private fun track(m: ByteArray, trak: Mp4Embed.Box): Track {
        val mdhd = path(m, trak, "mdia", "mdhd") ?: error("no mdhd")
        val timescale = if (m[mdhd.content.toInt()].toInt() == 1) u32(m, mdhd.content.toInt() + 20) else u32(m, mdhd.content.toInt() + 12)
        val hdlr = path(m, trak, "mdia", "hdlr") ?: error("no hdlr")
        val stsd = path(m, trak, "mdia", "minf", "stbl", "stsd") ?: error("no stsd")
        require(u32(m, stsd.content.toInt() + 4) == 1L) { "more than one sample description" }
        require(timescale > 0)
        return Track(trak, String(m, hdlr.content.toInt() + 8, 4, Charsets.ISO_8859_1), timescale, m.copyOfRange(stsd.offset.toInt(), stsd.end.toInt()))
    }

    private fun readTables(m: ByteArray, t: Track) {
        val stbl = path(m, t.trak, "mdia", "minf", "stbl") ?: error("no stbl")
        val stsz = child(m, stbl, "stsz") ?: error("no stsz")
        val fixed = u32(m, stsz.content.toInt() + 4)
        val n = u32(m, stsz.content.toInt() + 8).toInt()
        repeat(n) { t.sizes.add(if (fixed != 0L) fixed else u32(m, stsz.content.toInt() + 12 + it * 4)) }
        val stts = child(m, stbl, "stts") ?: error("no stts")
        for (e in 0 until u32(m, stts.content.toInt() + 4).toInt()) {
            val at = stts.content.toInt() + 8 + e * 8
            repeat(u32(m, at).toInt()) { t.durations.add(u32(m, at + 4)) }
        }
        child(m, stbl, "ctts")?.let { ctts ->
            t.cttsVersion = m[ctts.content.toInt()].toInt()
            val cto = Longs()
            for (e in 0 until u32(m, ctts.content.toInt() + 4).toInt()) {
                val at = ctts.content.toInt() + 8 + e * 8
                repeat(u32(m, at).toInt()) { cto.add(u32(m, at + 4).toInt().toLong()) }
            }
            require(cto.size == n)
            t.cto = cto
        }
        child(m, stbl, "stss")?.let { stss ->
            val sync = BooleanArray(n)
            for (e in 0 until u32(m, stss.content.toInt() + 4).toInt()) {
                val index = u32(m, stss.content.toInt() + 8 + e * 4).toInt() - 1
                if (index in 0 until n) sync[index] = true
            }
            t.sync = sync
        }
        val stco = child(m, stbl, "stco")
        val co64 = child(m, stbl, "co64")
        val chunks = when {
            stco != null -> LongArray(u32(m, stco.content.toInt() + 4).toInt()) { u32(m, stco.content.toInt() + 8 + it * 4) }
            co64 != null -> LongArray(u32(m, co64.content.toInt() + 4).toInt()) { u64(m, co64.content.toInt() + 8 + it * 8) }
            else -> error("no chunk offsets")
        }
        val stsc = child(m, stbl, "stsc") ?: error("no stsc")
        val entries = (0 until u32(m, stsc.content.toInt() + 4).toInt()).map { e ->
            val at = stsc.content.toInt() + 8 + e * 12
            require(u32(m, at + 8) == 1L) { "another sample description" }
            u32(m, at).toInt() to u32(m, at + 4).toInt()
        }
        var sample = 0
        for (c in chunks.indices) {
            val perChunk = entries.lastOrNull { it.first <= c + 1 }?.second ?: error("a chunk without samples")
            var offset = chunks[c]
            repeat(perChunk) {
                if (sample < n) { t.offsets.add(offset); offset += t.sizes[sample]; sample++ }
            }
        }
        require(sample == n) { "the chunks do not hold every sample" }
    }

    private fun readFragments(raf: RandomAccessFile, top: List<Mp4Embed.Box>, m: ByteArray, kids: List<Mp4Embed.Box>, tracks: List<Track>) {
        val ids = tracks.map { t ->
            val tkhd = child(m, t.trak, "tkhd")!!
            if (m[tkhd.content.toInt()].toInt() == 1) u32(m, tkhd.content.toInt() + 20) else u32(m, tkhd.content.toInt() + 12)
        }
        // trex: the defaults of each track (duration, size, flags)
        val defaults = HashMap<Long, LongArray>()
        kids.firstOrNull { it.type == "mvex" }?.let { mvex ->
            Mp4Embed.children(m, mvex.content.toInt(), mvex.end.toInt()).filter { it.type == "trex" }.forEach { trex ->
                val at = trex.content.toInt()
                defaults[u32(m, at + 4)] = longArrayOf(u32(m, at + 12), u32(m, at + 16), u32(m, at + 20))
            }
        }
        tracks.forEach { it.cto = Longs() }
        for (moof in top.filter { it.type == "moof" }) {
            val b = read(raf, moof)
            for (traf in Mp4Embed.children(b, moof.header, b.size).filter { it.type == "traf" }) {
                val tfhd = child(b, traf, "tfhd") ?: error("no tfhd")
                var at = tfhd.content.toInt()
                val flags = u32(b, at) and 0xFFFFFF
                val id = u32(b, at + 4)
                at += 8
                val ti = ids.indexOf(id).takeIf { it >= 0 } ?: error("a fragment of no track")
                val t = tracks[ti]
                val d = defaults[id] ?: longArrayOf(0, 0, 0)
                var base = moof.offset
                if (flags and 0x1 != 0L) { base = u64(b, at); at += 8 }
                if (flags and 0x2 != 0L) { require(u32(b, at) == 1L); at += 4 }
                var dDuration = d[0]
                var dSize = d[1]
                var dFlags = d[2]
                if (flags and 0x8 != 0L) { dDuration = u32(b, at); at += 4 }
                if (flags and 0x10 != 0L) { dSize = u32(b, at); at += 4 }
                if (flags and 0x20 != 0L) { dFlags = u32(b, at) }
                var data = base
                for (trun in Mp4Embed.children(b, traf.content.toInt(), traf.end.toInt()).filter { it.type == "trun" }) {
                    var p = trun.content.toInt()
                    val version = b[p].toInt()
                    val tf = u32(b, p) and 0xFFFFFF
                    val count = u32(b, p + 4).toInt()
                    p += 8
                    if (tf and 0x1 != 0L) { data = base + u32(b, p).toInt(); p += 4 }
                    var first: Long? = null
                    if (tf and 0x4 != 0L) { first = u32(b, p); p += 4 }
                    repeat(count) { k ->
                        val duration = if (tf and 0x100 != 0L) u32(b, p).also { p += 4 } else dDuration
                        val size = if (tf and 0x200 != 0L) u32(b, p).also { p += 4 } else dSize
                        val sampleFlags = if (tf and 0x400 != 0L) u32(b, p).also { p += 4 } else if (k == 0 && first != null) first else dFlags
                        val cto = if (tf and 0x800 != 0L) (if (version == 0) u32(b, p) else u32(b, p).toInt().toLong()).also { p += 4 } else 0L
                        t.offsets.add(data)
                        t.sizes.add(size)
                        t.durations.add(duration)
                        t.cto!!.add(cto)
                        t.syncList += (sampleFlags and 0x10000L) == 0L
                        data += size
                    }
                }
            }
        }
        for (t in tracks) {
            t.sync = if (t.syncList.all { it }) null else t.syncList.toBooleanArray()
            if ((0 until t.cto!!.size).all { t.cto!![it] == 0L }) t.cto = null
            if (t.cto != null && (0 until t.cto!!.size).any { t.cto!![it] < 0 }) t.cttsVersion = 1
        }
    }

    private fun times(t: Track): LongArray {
        var at = 0L
        return LongArray(t.durations.size) { i -> (at * 1000 / t.timescale).also { at += t.durations[i] } }
    }

    // ---- writing ------------------------------------------------------------------------------------------------------

    /** A copy of an `mvhd` or `mdhd` with its duration set (after the times and the timescale). */
    private fun patchDuration(m: ByteArray, box: Mp4Embed.Box, duration: Long): ByteArray {
        val b = m.copyOfRange(box.offset.toInt(), box.end.toInt())
        val content = box.header
        if (b[content].toInt() == 1) put64(b, content + 24, duration) else put32(b, content + 16, duration.coerceAtMost(0xFFFFFFFFL))
        return b
    }

    private fun Out.trak(m: ByteArray, t: Track, duration: Long, movieTimescale: Long, wide: Boolean, stcoAt: (Int) -> Unit) {
        val movieDuration = duration * movieTimescale / t.timescale
        box("trak") {
            for (c in Mp4Embed.children(m, t.trak.content.toInt(), t.trak.end.toInt())) when (c.type) {
                // tkhd: duration after creation, modification, id and a reserved word
                "tkhd" -> {
                    val b = m.copyOfRange(c.offset.toInt(), c.end.toInt())
                    if (b[c.header].toInt() == 1) put64(b, c.header + 28, movieDuration) else put32(b, c.header + 20, movieDuration.coerceAtMost(0xFFFFFFFFL))
                    bytes(b)
                }
                "edts" -> bytes(edts(m, c, movieDuration))
                "mdia" -> box("mdia") {
                    for (d in Mp4Embed.children(m, c.content.toInt(), c.end.toInt())) when (d.type) {
                        "mdhd" -> bytes(patchDuration(m, d, duration))
                        "minf" -> box("minf") {
                            for (e in Mp4Embed.children(m, d.content.toInt(), d.end.toInt())) {
                                if (e.type == "stbl") stbl(t, wide, stcoAt) else bytes(m, e.offset.toInt(), e.size.toInt())
                            }
                        }
                        else -> bytes(m, d.offset.toInt(), d.size.toInt())
                    }
                }
                else -> bytes(m, c.offset.toInt(), c.size.toInt())
            }
        }
    }

    /** The edit list with each edit that shows media running for the new length (empty edits keep theirs). */
    private fun edts(m: ByteArray, edts: Mp4Embed.Box, movieDuration: Long): ByteArray {
        val b = m.copyOfRange(edts.offset.toInt(), edts.end.toInt())
        val elst = Mp4Embed.children(b, edts.header, b.size).firstOrNull { it.type == "elst" } ?: return b
        val at = elst.content.toInt()
        val v1 = b[at].toInt() == 1
        val count = u32(b, at + 4).toInt()
        val playing = (0 until count).filter { i -> val e = at + 8 + i * (if (v1) 20 else 12); (if (v1) u64(b, e + 8) else u32(b, e + 4).toInt().toLong()) != -1L }
        val empty = (0 until count).filterNot { it in playing }.sumOf { i -> val e = at + 8 + i * (if (v1) 20 else 12); if (v1) u64(b, e) else u32(b, e) }
        for (i in playing) {
            val e = at + 8 + i * (if (v1) 20 else 12)
            val length = ((movieDuration - empty) / playing.size).coerceAtLeast(0)
            if (v1) put64(b, e, length) else put32(b, e, length.coerceAtMost(0xFFFFFFFFL))
        }
        return b
    }

    private fun Out.stbl(t: Track, big: Boolean, stcoAt: (Int) -> Unit) {
        val kept = t.keep.indices.filter { t.keep[it] }
        box("stbl") {
            bytes(t.stsd)
            fullBox("stts", 0) { runs(kept.map { t.durations[it] }) }
            t.cto?.let { cto -> fullBox("ctts", t.cttsVersion) { runs(kept.map { cto[it] and 0xFFFFFFFFL }) } }
            t.sync?.let { sync ->
                val numbers = kept.withIndex().filter { sync[it.value] }.map { it.index + 1L }
                fullBox("stss", 0) { u32(numbers.size.toLong()); numbers.forEach { u32(it) } }
            }
            fullBox("stsc", 0) { u32(1); u32(1); u32(1); u32(1) }
            val sizes = kept.map { t.sizes[it] }
            fullBox("stsz", 0) {
                if (sizes.isNotEmpty() && sizes.all { it == sizes[0] }) { u32(sizes[0]); u32(sizes.size.toLong()) }
                else { u32(0); u32(sizes.size.toLong()); sizes.forEach { u32(it) } }
            }
            fullBox(if (big) "co64" else "stco", 0) {
                u32(kept.size.toLong())
                stcoAt(size())
                repeat(kept.size) { if (big) u64(0) else u32(0) }
            }
        }
    }

    private fun Out.runs(values: List<Long>) {
        val runs = ArrayList<LongArray>()
        for (v in values) {
            val last = runs.lastOrNull()
            if (last != null && last[1] == v) last[0]++ else runs += longArrayOf(1, v)
        }
        u32(runs.size.toLong())
        runs.forEach { u32(it[0]); u32(it[1]) }
    }

    private class Out : ByteArrayOutputStream() {
        fun u32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) write(((v shr s) and 0xFF).toInt()) }
        fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
        fun type(t: String) = write(t.toByteArray(Charsets.ISO_8859_1))
        fun bytes(b: ByteArray) = write(b)
        fun bytes(b: ByteArray, from: Int, length: Int) = write(b, from, length)

        fun box(type: String, body: Out.() -> Unit) {
            val start = count
            u32(0)
            type(type)
            body()
            val size = (count - start).toLong()
            for ((i, s) in intArrayOf(24, 16, 8, 0).withIndex()) buf[start + i] = ((size shr s) and 0xFF).toByte()
        }

        fun fullBox(type: String, version: Int, body: Out.() -> Unit) = box(type) { u32(version.toLong() shl 24); body() }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun put32(b: ByteArray, at: Int, v: Long) { for ((i, s) in intArrayOf(24, 16, 8, 0).withIndex()) b[at + i] = ((v shr s) and 0xFF).toByte() }

    private fun put64(b: ByteArray, at: Int, v: Long) { put32(b, at, v ushr 32); put32(b, at + 4, v and 0xFFFFFFFFL) }
}
