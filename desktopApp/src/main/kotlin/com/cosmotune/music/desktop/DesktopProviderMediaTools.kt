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
    override fun convertFlac(input: File, output: File, maximumBytes: Long, cancelled: () -> Boolean) {
        val source = probe(input, cancelled)
        require(source.codec in setOf("flac", "alac", "wavpack", "ape", "truehd", "tta") || source.codec.startsWith("pcm_")) { "FLAC encoding requires a verified lossless source" }
        require(source.bitDepth in 1..24) { "FLAC encoding requires a confirmed source depth of at most 24 bits" }
        val bits = if (source.bitDepth in 1..16) 16 else 24
        val pcm = File.createTempFile("encoding-", ".wav", output.parentFile)
        try {
            convertPcm(input, pcm, bits, maximumBytes, cancelled)
            val available = maximumBytes - pcm.length(); require(available > 0)
            encodeFlacWave(pcm, output, available, cancelled)
        } finally { pcm.delete() }
    }
    private fun encodeFlacWave(wave: File, output: File, maximumBytes: Long, cancelled: () -> Boolean) {
        java.io.RandomAccessFile(wave, "r").use { input ->
            val headerBytes = ByteArray(44); input.readFully(headerBytes)
            val header = java.nio.ByteBuffer.wrap(headerBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            require(String(headerBytes, 0, 4) == "RIFF" && String(headerBytes, 36, 4) == "data")
            val channels = header.getShort(22).toInt(); val rate = header.getInt(24); val bits = header.getShort(34).toInt()
            require(channels in 1..8 && rate in 8_000..768_000 && bits in setOf(16, 24))
            val encoder = avcodec_find_encoder(AV_CODEC_ID_FLAC); check(encoder != null && !encoder.isNull)
            val context = avcodec_alloc_context3(encoder); val frame = av_frame_alloc(); val packet = av_packet_alloc()
            val target = AVFormatContext(null as org.bytedeco.javacpp.Pointer?); val io = org.bytedeco.ffmpeg.avformat.AVIOContext(null as org.bytedeco.javacpp.Pointer?)
            var opened = false
            try {
                context.sample_rate(rate).sample_fmt(if (bits == 24) AV_SAMPLE_FMT_S32 else AV_SAMPLE_FMT_S16).bits_per_raw_sample(bits)
                av_channel_layout_default(context.ch_layout(), channels); context.time_base().num(1).den(rate)
                check(avcodec_open2(context, encoder, null as AVDictionary?) >= 0)
                check(avformat_alloc_output_context2(target, null, "flac", output.absolutePath) >= 0 && !target.isNull)
                val stream = avformat_new_stream(target, null); check(stream != null && !stream.isNull)
                check(avcodec_parameters_from_context(stream.codecpar(), context) >= 0); stream.time_base(context.time_base())
                check(avio_open(io, output.absolutePath, AVIO_FLAG_WRITE) >= 0); opened = true; target.pb(io)
                check(avformat_write_header(target, null as AVDictionary?) >= 0)
                val blockSamples = context.frame_size(); require(blockSamples in 1..65_536)
                frame.format(context.sample_fmt()).sample_rate(rate).nb_samples(blockSamples)
                check(av_channel_layout_copy(frame.ch_layout(), context.ch_layout()) >= 0 && av_frame_get_buffer(frame, 0) >= 0)
                var samples = 0L
                fun packets() {
                    while (true) {
                        checkpoint(cancelled); val status = avcodec_receive_packet(context, packet)
                        if (status == AVERROR_EOF || status == AVERROR_EAGAIN()) return
                        check(status >= 0)
                        try { av_packet_rescale_ts(packet, context.time_base(), stream.time_base()); packet.stream_index(0); check(av_interleaved_write_frame(target, packet) >= 0); check(avio_tell(io) <= maximumBytes) }
                        finally { av_packet_unref(packet) }
                    }
                }
                while (input.filePointer < input.length()) {
                    checkpoint(cancelled)
                    val count = minOf(blockSamples.toLong(), (input.length() - input.filePointer) / (channels * bits / 8)).toInt(); require(count > 0)
                    val data = ByteArray(count * channels * bits / 8); input.readFully(data)
                    check(av_frame_make_writable(frame) >= 0); frame.nb_samples(count).pts(samples)
                    val buffer = frame.data(0).capacity(count.toLong() * channels * if (bits == 24) 4 else 2).asByteBuffer().order(java.nio.ByteOrder.nativeOrder())
                    var offset = 0
                    repeat(count * channels) { index ->
                        val low = data[offset++].toInt() and 255; val high = data[offset++].toInt()
                        if (bits == 16) buffer.putShort(index * 2, (low or (high shl 8)).toShort())
                        else { val top = data[offset++].toInt(); buffer.putInt(index * 4, ((low or ((high and 255) shl 8) or (top shl 16)) shl 8)) }
                    }
                    check(avcodec_send_frame(context, frame) >= 0); packets(); samples += count
                }
                require(samples > 0); check(avcodec_send_frame(context, null) >= 0); packets()
                check(av_write_trailer(target) >= 0 && avio_tell(io) <= maximumBytes)
            } finally { av_frame_free(frame); av_packet_free(packet); avcodec_free_context(context); if (opened) avio_closep(io); if (!target.isNull) avformat_free_context(target) }
        }
    }
    override fun convertPcm(input: File, output: File, bits: Int, maximumBytes: Long, cancelled: () -> Boolean) {
        require(bits in setOf(16, 24))
        read(input, null, cancelled) { source, index ->
            val parameters = source.streams(index).codecpar()
            val decoder = avcodec_find_decoder(parameters.codec_id())
            check(decoder != null && !decoder.isNull)
            val context = avcodec_alloc_context3(decoder)
            val packet = av_packet_alloc(); val frame = av_frame_alloc()
            try {
                check(avcodec_parameters_to_context(context, parameters) >= 0 && avcodec_open2(context, decoder, null as AVDictionary?) >= 0)
                val rate = context.sample_rate(); val channels = context.ch_layout().nb_channels()
                require(rate in 8_000..768_000 && channels in 1..8)
                java.io.RandomAccessFile(output, "rw").use { sink ->
                    sink.setLength(0); sink.write(ByteArray(44)); var bytes = 0L
                    fun frames() {
                        while (true) {
                            checkpoint(cancelled)
                            val received = avcodec_receive_frame(context, frame)
                            if (received == AVERROR_EOF || received == AVERROR_EAGAIN()) return
                            check(received >= 0 && frame.sample_rate() == rate && frame.ch_layout().nb_channels() == channels)
                            val format = frame.format(); val packed = av_get_packed_sample_fmt(format)
                            val width = av_get_bytes_per_sample(format); val planar = av_sample_fmt_is_planar(format) != 0
                            require(width in 1..8 && frame.nb_samples() in 1..1_048_576)
                            val planes = (0 until if (planar) channels else 1).map { channel ->
                                frame.extended_data(channel).capacity(frame.nb_samples().toLong() * width * if (planar) 1 else channels).asByteBuffer().order(java.nio.ByteOrder.nativeOrder())
                            }
                            val size = frame.nb_samples().toLong() * channels * (bits / 8)
                            require(size <= maximumBytes - 44 - bytes)
                            val block = ByteArray(size.toInt()); var at = 0
                            for (sample in 0 until frame.nb_samples()) for (channel in 0 until channels) {
                                val buffer = planes[if (planar) channel else 0]
                                val offset = (if (planar) sample else sample * channels + channel) * width
                                val value = when (packed) {
                                    AV_SAMPLE_FMT_U8 -> ((buffer.get(offset).toInt() and 255) - 128) / 128.0
                                    AV_SAMPLE_FMT_S16 -> buffer.getShort(offset) / 32768.0
                                    AV_SAMPLE_FMT_S32 -> buffer.getInt(offset) / 2147483648.0
                                    AV_SAMPLE_FMT_S64 -> buffer.getLong(offset) / 9223372036854775808.0
                                    AV_SAMPLE_FMT_FLT -> buffer.getFloat(offset).toDouble()
                                    AV_SAMPLE_FMT_DBL -> buffer.getDouble(offset)
                                    else -> error("Unsupported provider PCM sample format")
                                }
                                require(value.isFinite())
                                val scale = if (bits == 24) 8_388_608.0 else 32_768.0
                                val integer = kotlin.math.round(value.coerceIn(-1.0, 1.0) * scale).toLong().coerceIn(-scale.toLong(), scale.toLong() - 1).toInt()
                                repeat(bits / 8) { block[at++] = (integer shr (it * 8)).toByte() }
                            }
                            sink.write(block); bytes += size; av_frame_unref(frame)
                        }
                    }
                    while (true) {
                        checkpoint(cancelled)
                        val status = av_read_frame(source, packet)
                        if (status == AVERROR_EOF) break
                        check(status >= 0)
                        try { if (packet.stream_index() == index) { check(avcodec_send_packet(context, packet) >= 0); frames() } }
                        finally { av_packet_unref(packet) }
                    }
                    check(avcodec_send_packet(context, null) >= 0); frames(); require(bytes > 0)
                    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    header.put("RIFF".toByteArray()).putInt((bytes + 36).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
                    header.putInt(rate).putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort()).put("data".toByteArray()).putInt(bytes.toInt())
                    sink.seek(0); sink.write(header.array()); sink.fd.sync()
                }
            } finally { av_frame_free(frame); av_packet_free(packet); avcodec_free_context(context) }
        }
    }
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
            av_dict_set(options, "format_whitelist", "mov,flac,mp3,aac,ogg,wav,aiff,matroska,dsf,iff,mpegts", 0)
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
