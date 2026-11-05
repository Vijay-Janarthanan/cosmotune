package com.cosmotune.video.downloads.desktop

import com.cosmotune.video.downloads.DownloadContainer
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
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
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_compare_ts
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import java.io.File

/**
 * Joins a downloaded video-only file and audio-only file into one file on the desktop without re-encoding (plan task Y-24,
 * step 6), with the FFmpeg the desktop already ships (bytedeco; this module compiles against it, the desktop app brings the
 * natives): each input's stream is copied (`avcodec_parameters_copy`) into a new output stream (`avformat_new_stream`) and the
 * packets are written in time order (`av_interleaved_write_frame`). MP4 for H.264 or AV1 with AAC or Opus (fast start: the
 * index at the front), WebM for VP9 with Opus. Any failure answers false and the caller keeps both files.
 */
object FfmpegRemux {
    /** Joins [video] and [audio] into [output] as [container] (MP4 or WebM); true when the file was written. */
    fun remux(video: File, audio: File, output: File, container: DownloadContainer): Boolean = try {
        output.delete()
        Native.join(video, audio, output, if (container == DownloadContainer.WEBM) "webm" else "mp4")
        output.length() > 0
    } catch (_: Throwable) {
        // a refused stream, a broken file, or natives that would not load: the caller keeps the pair
        output.delete()
        false
    }

    /** Every FFmpeg call, in a class of its own: without FFmpeg's classes or natives only this fails to load, inside [remux]'s guard. */
    private object Native {
        private class Input(val context: AVFormatContext, val index: Int, val stream: AVStream)

        private fun open(file: File, type: Int): Input {
            val context = AVFormatContext(null)
            check(avformat_open_input(context, file.absolutePath, null, null as AVDictionary?) >= 0) { "cannot open ${file.name}" }
            try {
                check(avformat_find_stream_info(context, null as AVDictionary?) >= 0) { "no stream info in ${file.name}" }
                val index = av_find_best_stream(context, type, -1, -1, null as AVCodec?, 0)
                check(index >= 0) { "no stream of type $type in ${file.name}" }
                return Input(context, index, context.streams(index))
            } catch (t: Throwable) {
                avformat_close_input(context)
                throw t
            }
        }

        fun join(video: File, audio: File, output: File, format: String) {
            val inputs = mutableListOf<Input>()
            val out = AVFormatContext(null)
            var opened = false
            val packets = mutableListOf<AVPacket>()
            try {
                inputs += open(video, AVMEDIA_TYPE_VIDEO)
                inputs += open(audio, AVMEDIA_TYPE_AUDIO)
                check(avformat_alloc_output_context2(out, null, format, output.absolutePath) >= 0 && !out.isNull) { "no $format muxer" }
                val streams = inputs.map { input ->
                    val stream = avformat_new_stream(out, null as AVCodec?) ?: error("no output stream")
                    check(avcodec_parameters_copy(stream.codecpar(), input.stream.codecpar()) >= 0) { "cannot copy the stream parameters" }
                    stream.codecpar().codec_tag(0)
                    stream.time_base(input.stream.time_base())
                    stream
                }
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
                        if (packets[i].stream_index() == inputs[i].index) { waiting[i] = true; return }
                        av_packet_unref(packets[i])
                    }
                }
                inputs.indices.forEach(::next)
                while (waiting.any { it }) {
                    val i = when {
                        !waiting[0] -> 1
                        !waiting[1] -> 0
                        av_compare_ts(timeOf(packets[0]), inputs[0].stream.time_base(), timeOf(packets[1]), inputs[1].stream.time_base()) <= 0 -> 0
                        else -> 1
                    }
                    val packet = packets[i]
                    av_packet_rescale_ts(packet, inputs[i].stream.time_base(), streams[i].time_base())
                    packet.stream_index(i)
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

        private fun timeOf(packet: AVPacket): Long = if (packet.dts() != AV_NOPTS_VALUE) packet.dts() else packet.pts()
    }
}
