package com.cosmotune.music.desktop

import com.cosmotune.providers.ProviderMediaInfo
import com.cosmotune.providers.ProviderMediaTools
import java.io.File
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*

/** Uses the FFmpeg libraries already shipped with desktop, without a shell or external executable. */
internal object DesktopProviderMediaTools : ProviderMediaTools {
    private fun checkpoint(cancelled: () -> Boolean) {
        check(!cancelled() && !Thread.currentThread().isInterrupted) { "Provider audio processing cancelled" }
    }
    private fun <T> read(input: File, key: String?, cancelled: () -> Boolean, operation: (AVFormatContext, Int) -> T): T {
        checkpoint(cancelled)
        val context = avformat_alloc_context()
        val interrupt = object : org.bytedeco.ffmpeg.avformat.AVIOInterruptCB.Callback_Pointer() {
            override fun call(opaque: org.bytedeco.javacpp.Pointer?): Int =
                if (cancelled() || Thread.currentThread().isInterrupted) 1 else 0
        }
        context.interrupt_callback().callback(interrupt)
        val options = AVDictionary(null as org.bytedeco.javacpp.Pointer?)
        try {
            av_dict_set(options, "protocol_whitelist", "file", 0)
            av_dict_set(options, "format_whitelist", "mov,flac,mp3,aac,ogg,wav,aiff,matroska,dsf,iff", 0)
            av_dict_set(options, "probesize", "4194304", 0)
            av_dict_set(options, "analyzeduration", "3000000", 0)
            if (key != null) av_dict_set(options, "decryption_key", key, 0)
            check(avformat_open_input(context, input.absolutePath, null as AVInputFormat?, options) >= 0) { "Provider audio could not be opened" }
            check(avformat_find_stream_info(context, null as org.bytedeco.javacpp.PointerPointer<*>?) >= 0) { "Provider audio has no stream information" }
            checkpoint(cancelled)
            val index = av_find_best_stream(context, AVMEDIA_TYPE_AUDIO, -1, -1, null as org.bytedeco.javacpp.PointerPointer<*>?, 0)
            check(index >= 0) { "Provider file has no audio stream" }
            return operation(context, index)
        } finally { av_dict_free(options); if (!context.isNull) avformat_close_input(context); interrupt.close() }
    }

    override fun probe(input: File, cancelled: () -> Boolean): ProviderMediaInfo = read(input, null, cancelled) { context, index ->
        val stream = context.streams(index); val codec = stream.codecpar()
        val duration = if (stream.duration() > 0) (stream.duration() * av_q2d(stream.time_base()) * 1000).toLong()
            else (context.duration() / 1000).coerceAtLeast(0)
        ProviderMediaInfo(avcodec_get_name(codec.codec_id()).string, codec.sample_rate(), codec.ch_layout().nb_channels(),
            codec.bits_per_raw_sample().takeIf { it > 0 } ?: codec.bits_per_coded_sample(), duration,
            codec.bit_rate().takeIf { it > 0 } ?: context.bit_rate(), context.iformat().name().string)
    }

    override fun remux(input: File, output: File, key: String?, maximumBytes: Long, cancelled: () -> Boolean) {
        read(input, key, cancelled) { source, index ->
            val target = AVFormatContext(null as org.bytedeco.javacpp.Pointer?)
            val io = org.bytedeco.ffmpeg.avformat.AVIOContext(null as org.bytedeco.javacpp.Pointer?)
            val packet = av_packet_alloc()
            var opened = false
            try {
                check(avformat_alloc_output_context2(target, null, null, output.absolutePath) >= 0 && !target.isNull) { "Unsupported output container" }
                val from = source.streams(index)
                val to = avformat_new_stream(target, null)
                check(to != null && !to.isNull && avcodec_parameters_copy(to.codecpar(), from.codecpar()) >= 0)
                to.codecpar().codec_tag(0); to.time_base(from.time_base())
                check(avio_open(io, output.absolutePath, AVIO_FLAG_WRITE) >= 0); target.pb(io); opened = true
                check(avformat_write_header(target, null as AVDictionary?) >= 0) { "Provider audio cannot use this container" }
                var count = 0
                while (true) {
                    checkpoint(cancelled)
                    val status = av_read_frame(source, packet)
                    if (status == AVERROR_EOF) break
                    check(status >= 0) { "Provider audio packet is invalid" }
                    try {
                        if (packet.stream_index() != index) continue
                        av_packet_rescale_ts(packet, from.time_base(), to.time_base())
                        packet.stream_index(0); packet.pos(-1)
                        check(av_interleaved_write_frame(target, packet) >= 0) { "Provider audio could not be prepared" }
                        check(avio_tell(io) <= maximumBytes) { "Provider audio exceeds workspace quota" }
                        count++
                    } finally { av_packet_unref(packet) }
                }
                check(count > 0 && av_write_trailer(target) >= 0)
                check(avio_tell(io) <= maximumBytes) { "Provider audio exceeds workspace quota" }
            } finally {
                av_packet_free(packet)
                if (!target.isNull) { if (opened) avio_closep(io); avformat_free_context(target) }
            }
        }
        // A wrong key can still produce a well-formed container. Require real decoded samples.
        read(output, null, cancelled) { source, index ->
            val decoder = avcodec_find_decoder(source.streams(index).codecpar().codec_id())
            check(decoder != null && !decoder.isNull) { "Provider audio decoder unavailable" }
            val context = avcodec_alloc_context3(decoder)
            val packet = av_packet_alloc(); val frame = av_frame_alloc()
            try {
                check(avcodec_parameters_to_context(context, source.streams(index).codecpar()) >= 0 && avcodec_open2(context, decoder, null as AVDictionary?) >= 0)
                var decoded = false
                repeat(64) {
                    if (!decoded) {
                        checkpoint(cancelled)
                        check(av_read_frame(source, packet) >= 0) { "Provider audio has no decoded samples" }
                        try {
                            if (packet.stream_index() == index) {
                                check(avcodec_send_packet(context, packet) >= 0) { "Provider audio packet cannot be decoded" }
                                decoded = avcodec_receive_frame(context, frame) >= 0 && frame.nb_samples() > 0
                            }
                        } finally { av_packet_unref(packet) }
                    }
                }
                check(decoded) { "Provider audio has no decoded samples" }
            } finally { av_frame_free(frame); av_packet_free(packet); avcodec_free_context(context) }
        }
    }
}
