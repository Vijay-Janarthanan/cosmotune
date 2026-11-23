package com.cosmotune.video.downloads.desktop

import com.cosmotune.video.downloads.DownloadContainer
import com.cosmotune.video.downloads.CutRange
import com.cosmotune.video.downloads.EmbedDetails
import com.cosmotune.video.downloads.SegmentCuts
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVChapter
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_SUBTITLE
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_compare_ts
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.ffmpeg.global.avutil.av_mallocz
import org.bytedeco.ffmpeg.global.avutil.av_rescale_q
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.util.Locale

/**
 * Joins a downloaded video-only file and audio-only file into one file on the desktop without re-encoding (plan task Y-24,
 * step 6), with the FFmpeg the desktop already ships (bytedeco; this module compiles against it, the desktop app brings the
 * natives): each input's stream is copied (`avcodec_parameters_copy`) into a new output stream (`avformat_new_stream`) and the
 * packets are written in time order (`av_interleaved_write_frame`). MP4 for H.264 or AV1 with AAC or Opus (fast start: the
 * index at the front), WebM for VP9 with Opus. Any failure answers false and the caller keeps both files. [embed] copies a
 * WebM the same way with the title, channel, upload day, description and chapters inside and a WebVTT file as its subtitle
 * track, and [cut] copies one without stretches (each cut ends on a keyframe; MP4 files are written by `Mp4Embed` and
 * `Mp4Cut` instead).
 */
object FfmpegRemux {
    /** Joins [video] and [audio] into [output] as [container] (MP4 or WebM); true when the file was written. */
    fun remux(video: File, audio: File, output: File, container: DownloadContainer): Boolean = guarded(output) {
        val format = if (container == DownloadContainer.WEBM) "webm" else "mp4"
        Native.copy(listOf(Pick(video, AVMEDIA_TYPE_VIDEO), Pick(audio, AVMEDIA_TYPE_AUDIO)), output, format, Extras())
    }

    /** Writes the WebM [input] with [details] and the WebVTT [subtitles] (when given) inside to [output]; true when written. */
    fun embed(input: File, subtitles: File?, output: File, details: EmbedDetails): Boolean = guarded(output) {
        val metadata = buildMap {
            put("title", details.title)
            details.channel?.let { put("artist", it) }
            details.date?.let { put("date", it) }
            details.description?.let { put("description", it) }
            details.url?.let { put("comment", it) }
        }
        val picks = listOfNotNull(Pick(input, null), subtitles?.let { Pick(it, AVMEDIA_TYPE_SUBTITLE) })
        val sorted = details.chapters.sortedBy { it.startMs }
        val chapters = sorted.mapIndexed { i, chapter -> ChapterSpan(chapter.title, chapter.startMs, sorted.getOrNull(i + 1)?.startMs) }
        Native.copy(picks, output, "webm", Extras(metadata, chapters, details.subtitles?.language))
    }

    /** Writes the WebM [input] without [ranges] to [output]; the cuts made (empty: none fell in the file), or null when it failed. */
    fun cut(input: File, output: File, ranges: List<CutRange>): List<CutRange>? = try {
        output.delete()
        val (times, sync, endMs) = Native.scan(input)
        val cuts = SegmentCuts.snap(times, sync, ranges, endMs)
        if (cuts.isNotEmpty()) {
            Native.copy(listOf(Pick(input, null)), output, "webm", Extras(cuts = cuts))
            check(output.length() > 0)
        }
        cuts
    } catch (_: Throwable) {
        output.delete()
        null
    }

    private inline fun guarded(output: File, block: () -> Unit): Boolean = try {
        output.delete()
        block()
        output.length() > 0
    } catch (_: Throwable) {
        // a refused stream, a broken file, or natives that would not load: the caller keeps what it had
        output.delete()
        false
    }

    /** A file and the streams taken from it: the best one of [type], or (null) every picture, sound and subtitle stream. */
    private class Pick(val file: File, val type: Int?)

    /** A chapter in milliseconds; [endMs] null runs to the end of the file. */
    private class ChapterSpan(val title: String, val startMs: Long, val endMs: Long?)

    /** The file's tags, its chapters and the subtitle track's language tag. */
    private class Extras(
        val metadata: Map<String, String> = emptyMap(),
        val chapters: List<ChapterSpan> = emptyList(),
        val subtitleLanguage: String? = null,
        val cuts: List<CutRange> = emptyList(),
    )

    /** Every FFmpeg call, in a class of its own: without FFmpeg's classes or natives only this fails to load, inside the guard. */
    private object Native {
        /** An open input and its chosen streams: input stream index to output stream index. */
        private class Input(val context: AVFormatContext, val streams: Map<Int, Int>)

        private val COPIED = setOf(AVMEDIA_TYPE_VIDEO, AVMEDIA_TYPE_AUDIO, AVMEDIA_TYPE_SUBTITLE)

        private fun open(pick: Pick, firstOut: Int): Input {
            val context = AVFormatContext(null)
            check(avformat_open_input(context, pick.file.absolutePath, null, null as AVDictionary?) >= 0) { "cannot open ${pick.file.name}" }
            try {
                check(avformat_find_stream_info(context, null as AVDictionary?) >= 0) { "no stream info in ${pick.file.name}" }
                val all = 0 until context.nb_streams()
                val chosen = when (pick.type) {
                    null -> all.filter { context.streams(it).codecpar().codec_type() in COPIED }
                    AVMEDIA_TYPE_SUBTITLE -> all.filter { context.streams(it).codecpar().codec_type() == AVMEDIA_TYPE_SUBTITLE }.take(1)
                    else -> listOf(av_find_best_stream(context, pick.type, -1, -1, null as AVCodec?, 0)).filter { it >= 0 }
                }
                check(chosen.isNotEmpty()) { "nothing to copy in ${pick.file.name}" }
                return Input(context, chosen.withIndex().associate { (i, stream) -> stream to firstOut + i })
            } catch (t: Throwable) {
                avformat_close_input(context)
                throw t
            }
        }

        /** The times (ms) of the picture's packets (the sound's when there is no picture), which are keyframes, and the length. */
        fun scan(file: File): Triple<LongArray, BooleanArray, Long> {
            val input = open(Pick(file, AVMEDIA_TYPE_VIDEO).takeIf { hasVideo(file) } ?: Pick(file, AVMEDIA_TYPE_AUDIO), 0)
            val packet = av_packet_alloc() ?: error("no packet")
            try {
                val index = input.streams.keys.single()
                val base = input.context.streams(index).time_base()
                val times = ArrayList<Long>()
                val sync = ArrayList<Boolean>()
                while (av_read_frame(input.context, packet) >= 0) {
                    if (packet.stream_index() == index && timeOf(packet) != AV_NOPTS_VALUE) {
                        times += av_rescale_q(timeOf(packet), base, MS)
                        sync += (packet.flags() and AV_PKT_FLAG_KEY) != 0
                    }
                    av_packet_unref(packet)
                }
                val order = times.indices.sortedBy { times[it] }
                return Triple(LongArray(order.size) { times[order[it]] }, BooleanArray(order.size) { sync[order[it]] }, durationMs(input.context))
            } finally {
                av_packet_free(packet)
                avformat_close_input(input.context)
            }
        }

        private fun hasVideo(file: File): Boolean = try {
            avformat_close_input(open(Pick(file, AVMEDIA_TYPE_VIDEO), 0).context)
            true
        } catch (_: IllegalStateException) {
            false
        }

        private val MS = av_make_q(1, 1000)

        fun copy(picks: List<Pick>, output: File, format: String, extras: Extras) {
            val inputs = mutableListOf<Input>()
            val out = AVFormatContext(null)
            var opened = false
            val packets = mutableListOf<AVPacket>()
            try {
                for (pick in picks) inputs += open(pick, inputs.sumOf { it.streams.size })
                check(avformat_alloc_output_context2(out, null, format, output.absolutePath) >= 0 && !out.isNull) { "no $format muxer" }
                // output stream n is the n-th chosen stream, in the order of the inputs
                val sources = inputs.flatMap { input -> input.streams.keys.map { input.context.streams(it) } }
                val streams = sources.map { source ->
                    val stream = avformat_new_stream(out, null as AVCodec?) ?: error("no output stream")
                    check(avcodec_parameters_copy(stream.codecpar(), source.codecpar()) >= 0) { "cannot copy the stream parameters" }
                    stream.codecpar().codec_tag(0)
                    stream.time_base(source.time_base())
                    val language = extras.subtitleLanguage?.takeIf { it.isNotBlank() }
                    if (source.codecpar().codec_type() == AVMEDIA_TYPE_SUBTITLE && language != null) {
                        stream.metadata(dictionary(mapOf("language" to Locale.forLanguageTag(language).isO3Language.ifEmpty { "und" })))
                    }
                    stream
                }
                if (extras.metadata.isNotEmpty()) out.metadata(dictionary(extras.metadata))
                if (extras.chapters.isNotEmpty()) addChapters(out, extras.chapters, durationMs(inputs.first().context))
                if ((out.oformat().flags() and AVFMT_NOFILE) == 0) {
                    val io = AVIOContext(null)
                    check(avio_open(io, output.absolutePath, AVIO_FLAG_WRITE) >= 0) { "cannot write ${output.name}" }
                    out.pb(io)
                    opened = true
                }
                val options = AVDictionary(null)
                if (format == "mp4") av_dict_set(options, "movflags", "+faststart", 0)
                val header = avformat_write_header(out, options)
                av_dict_free(options)
                check(header >= 0) { "the $format muxer refused the streams" }

                // one packet waiting per input; always write the one whose time comes first
                repeat(inputs.size) { packets += av_packet_alloc() ?: error("no packet") }
                val waiting = BooleanArray(inputs.size)
                fun next(i: Int) {
                    while (true) {
                        if (av_read_frame(inputs[i].context, packets[i]) < 0) { waiting[i] = false; return }
                        if (packets[i].stream_index() in inputs[i].streams) { waiting[i] = true; return }
                        av_packet_unref(packets[i])
                    }
                }
                inputs.indices.forEach(::next)
                while (waiting.any { it }) {
                    var i = -1
                    for (j in inputs.indices) {
                        if (!waiting[j]) continue
                        if (i < 0 || av_compare_ts(timeOf(packets[j]), timeBase(inputs[j], packets[j]), timeOf(packets[i]), timeBase(inputs[i], packets[i])) < 0) i = j
                    }
                    val packet = packets[i]
                    if (extras.cuts.isNotEmpty() && timeOf(packet) != AV_NOPTS_VALUE) {
                        // a packet inside a cut goes; the ones after it move back by what was cut before them
                        val base = timeBase(inputs[i], packet)
                        val ms = av_rescale_q(timeOf(packet), base, MS)
                        if (SegmentCuts.isCut(ms, extras.cuts)) {
                            av_packet_unref(packet)
                            next(i)
                            continue
                        }
                        val shift = av_rescale_q(ms - SegmentCuts.map(ms, extras.cuts), MS, base)
                        if (packet.pts() != AV_NOPTS_VALUE) packet.pts(packet.pts() - shift)
                        if (packet.dts() != AV_NOPTS_VALUE) packet.dts(packet.dts() - shift)
                    }
                    val target = inputs[i].streams.getValue(packet.stream_index())
                    av_packet_rescale_ts(packet, timeBase(inputs[i], packet), streams[target].time_base())
                    packet.stream_index(target)
                    packet.pos(-1)
                    check(av_interleaved_write_frame(out, packet) >= 0) { "cannot write a packet" }
                    next(i)
                }
                check(av_write_trailer(out) >= 0) { "cannot finish ${output.name}" }
            } finally {
                packets.forEach { av_packet_free(it) }
                if (!out.isNull) {
                    if (opened) avio_closep(out.pb())
                    avformat_free_context(out)
                }
                inputs.forEach { avformat_close_input(it.context) }
            }
        }

        private fun dictionary(values: Map<String, String>): AVDictionary {
            val dictionary = AVDictionary(null)
            values.forEach { (key, value) -> av_dict_set(dictionary, key, value, 0) }
            return dictionary
        }

        /** The chapters in milliseconds, in FFmpeg's own memory: the context frees them, their titles and the list with itself. */
        private fun addChapters(out: AVFormatContext, chapters: List<ChapterSpan>, durationMs: Long) {
            val list = chapters.filter { it.startMs >= 0 }.take(255)
            if (list.isEmpty()) return
            val array = av_mallocz(list.size.toLong() * 8) ?: error("no memory")
            out.chapters(PointerPointer<AVChapter>(array))
            for ((i, chapter) in list.withIndex()) {
                val c = AVChapter(av_mallocz(Loader.sizeof(AVChapter::class.java).toLong()) ?: error("no memory"))
                c.id(i.toLong())
                c.time_base(av_make_q(1, 1000))
                c.start(chapter.startMs)
                c.end(maxOf(chapter.endMs ?: durationMs, chapter.startMs + 1))
                c.metadata(dictionary(mapOf("title" to chapter.title)))
                out.chapters(i, c)
                out.nb_chapters(i + 1)
            }
        }

        private fun durationMs(context: AVFormatContext): Long = context.duration().takeIf { it > 0 }?.let { it / 1000 } ?: 0

        private fun timeBase(input: Input, packet: AVPacket) = input.context.streams(packet.stream_index()).time_base()

        private fun timeOf(packet: AVPacket): Long = if (packet.dts() != AV_NOPTS_VALUE) packet.dts() else packet.pts()
    }
}
