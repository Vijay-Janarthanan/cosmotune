// Scoped, local-file audio operations. The Kotlin host validates paths, descriptors and quotas.
#include <jni.h>
#include <stdio.h>
#include <string>
#include <vector>
#include <cmath>
#include <cstring>
#include <cerrno>
#include <algorithm>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
}

namespace {
struct Cancel {
  JNIEnv* env; jobject value; jmethodID method;
  bool stopped() { return env->CallBooleanMethod(value, method) || env->ExceptionCheck(); }
  static int interrupt(void* opaque) { return static_cast<Cancel*>(opaque)->stopped() ? 1 : 0; }
};
struct Utf {
  JNIEnv* env; jstring value; const char* data;
  Utf(JNIEnv* e, jstring v) : env(e), value(v), data(v ? e->GetStringUTFChars(v, nullptr) : nullptr) {}
  ~Utf() { if (data) env->ReleaseStringUTFChars(value, data); }
};
void failure(JNIEnv* env) {
  if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Provider audio processing failed or was cancelled");
}
struct Input {
  AVFormatContext* context = nullptr;
  int index = -1;
  ~Input() { if (context) avformat_close_input(&context); }
  bool open(const char* path, const char* key, Cancel* cancel) {
    if (cancel->stopped()) return false;
    context = avformat_alloc_context(); if (!context) return false;
    context->interrupt_callback = {Cancel::interrupt, cancel};
    AVDictionary* opts = nullptr;
    av_dict_set(&opts, "protocol_whitelist", "file", 0);
    av_dict_set(&opts, "format_whitelist", "mov,flac,mp3,aac,ogg,wav,aiff,matroska,dsf,iff,mpegts", 0);
    av_dict_set(&opts, "probesize", "4194304", 0);
    av_dict_set(&opts, "analyzeduration", "3000000", 0);
    if (key) av_dict_set(&opts, "decryption_key", key, 0);
    int result = avformat_open_input(&context, path, nullptr, &opts);
    av_dict_free(&opts);
    if (result < 0 || avformat_find_stream_info(context, nullptr) < 0 || cancel->stopped()) return false;
    index = av_find_best_stream(context, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    return index >= 0;
  }
};
struct Output {
  AVFormatContext* context = nullptr;
  ~Output() { if (context) { if (context->pb) avio_closep(&context->pb); avformat_free_context(context); } }
};
bool decodes(const char* path, Cancel* cancel) {
  Input input; if (!input.open(path, nullptr, cancel)) return false;
  AVCodecParameters* parameters = input.context->streams[input.index]->codecpar;
  const AVCodec* codec = avcodec_find_decoder(parameters->codec_id); if (!codec) return false;
  AVCodecContext* context = avcodec_alloc_context3(codec);
  AVPacket* packet = av_packet_alloc(); AVFrame* frame = av_frame_alloc();
  bool decoded = false;
  if (context && packet && frame && avcodec_parameters_to_context(context, parameters) >= 0 && avcodec_open2(context, codec, nullptr) >= 0) {
    for (int i = 0; i < 64 && !decoded && !cancel->stopped(); i++) {
      if (av_read_frame(input.context, packet) < 0) break;
      if (packet->stream_index == input.index) {
        if (avcodec_send_packet(context, packet) < 0) { av_packet_unref(packet); break; }
        decoded = avcodec_receive_frame(context, frame) >= 0 && frame->nb_samples > 0;
      }
      av_packet_unref(packet);
    }
  }
  av_frame_free(&frame); av_packet_free(&packet); avcodec_free_context(&context);
  return decoded;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_cosmotune_providers_AndroidProviderMediaTools_probeNative(JNIEnv* env, jobject, jstring path, jobject stopped) {
  Utf input(env, path);
  Cancel cancel{env, stopped, env->GetMethodID(env->GetObjectClass(stopped), "getAsBoolean", "()Z")};
  Input media;
  if (!media.open(input.data, nullptr, &cancel)) { failure(env); return nullptr; }
  AVStream* stream = media.context->streams[media.index]; AVCodecParameters* codec = stream->codecpar;
  int64_t duration = stream->duration > 0 ? (int64_t)(stream->duration * av_q2d(stream->time_base) * 1000) : media.context->duration > 0 ? media.context->duration / 1000 : 0;
  char json[512];
  snprintf(json, sizeof(json), "{\"codec\":\"%s\",\"rate\":%d,\"channels\":%d,\"depth\":%d,\"duration\":%lld,\"bitrate\":%lld}",
    avcodec_get_name(codec->codec_id), codec->sample_rate, codec->ch_layout.nb_channels,
    codec->bits_per_raw_sample ? codec->bits_per_raw_sample : codec->bits_per_coded_sample,
    (long long)duration, (long long)(codec->bit_rate > 0 ? codec->bit_rate : media.context->bit_rate));
  return env->NewStringUTF(json);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cosmotune_providers_AndroidProviderMediaTools_remuxNative(JNIEnv* env, jobject, jstring path, jstring destination,
  jstring key, jlong maximum, jobject stopped) {
  Utf input(env, path), output(env, destination), secret(env, key);
  Cancel cancel{env, stopped, env->GetMethodID(env->GetObjectClass(stopped), "getAsBoolean", "()Z")};
  Input media; Output target;
  auto run = [&]() -> bool {
    if (maximum <= 0 || !media.open(input.data, secret.data, &cancel)) return false;
    // .m4a normally selects FFmpeg's separate "ipod" muxer. The compact build
    // ships the MP4 muxer, which produces the same ISO-BMFF audio container.
    std::string name(output.data);
    const char* format = name.size() >= 4 && name.compare(name.size() - 4, 4, ".m4a") == 0 ? "mp4" : nullptr;
    if (avformat_alloc_output_context2(&target.context, nullptr, format, output.data) < 0 || !target.context) return false;
    AVStream* from = media.context->streams[media.index]; AVStream* to = avformat_new_stream(target.context, nullptr);
    if (!to || avcodec_parameters_copy(to->codecpar, from->codecpar) < 0) return false;
    to->codecpar->codec_tag = 0; to->time_base = from->time_base;
    if (avio_open(&target.context->pb, output.data, AVIO_FLAG_WRITE) < 0 || avformat_write_header(target.context, nullptr) < 0) return false;
    AVPacket* packet = av_packet_alloc(); if (!packet) return false;
    int status = 0, count = 0;
    while (!cancel.stopped() && (status = av_read_frame(media.context, packet)) >= 0) {
      if (packet->stream_index == media.index) {
        av_packet_rescale_ts(packet, from->time_base, to->time_base); packet->stream_index = 0; packet->pos = -1;
        status = av_interleaved_write_frame(target.context, packet);
        count++;
      }
      av_packet_unref(packet);
      if (status < 0 || avio_tell(target.context->pb) > maximum) { status = AVERROR(EIO); break; }
    }
    av_packet_free(&packet);
    return !cancel.stopped() && status == AVERROR_EOF && count > 0 && av_write_trailer(target.context) >= 0 && avio_tell(target.context->pb) <= maximum;
  };
  bool ok = run();
  if (target.context && target.context->pb) avio_closep(&target.context->pb);
  if (!ok || !decodes(output.data, &cancel)) failure(env);
}

// The bundled media decoder's format-only converter. It never changes rate or layout.
bool convertFrame(const AVFrame*, AVSampleFormat, int, AVSampleFormat, uint8_t*);

extern "C" JNIEXPORT void JNICALL
Java_com_cosmotune_providers_AndroidProviderMediaTools_convertPcmNative(JNIEnv* env, jobject, jstring path,
  jstring destination, jint bits, jlong maximum, jobject stopped) {
  Utf input(env, path), output(env, destination);
  Cancel cancel{env, stopped, env->GetMethodID(env->GetObjectClass(stopped), "getAsBoolean", "()Z")};
  Input media;
  AVCodecContext* decoder = nullptr; AVPacket* packet = nullptr; AVFrame* frame = nullptr; FILE* sink = nullptr;
  auto run = [&]() -> bool {
    if ((bits != 16 && bits != 24) || maximum <= 44 || !media.open(input.data, nullptr, &cancel)) return false;
    const AVCodecParameters* parameters = media.context->streams[media.index]->codecpar;
    const AVCodec* codec = avcodec_find_decoder(parameters->codec_id); if (!codec) return false;
    decoder = avcodec_alloc_context3(codec); packet = av_packet_alloc(); frame = av_frame_alloc();
    if (!decoder || !packet || !frame || avcodec_parameters_to_context(decoder, parameters) < 0 || avcodec_open2(decoder, codec, nullptr) < 0) return false;
    const int rate = decoder->sample_rate, channels = decoder->ch_layout.nb_channels;
    if (rate < 8000 || rate > 768000 || channels < 1 || channels > 8) return false;
    sink = fopen(output.data, "wb+"); if (!sink) return false;
    uint8_t header[44] = {}; if (fwrite(header, 1, 44, sink) != 44) return false;
    int64_t bytes = 0;
    auto frames = [&]() -> bool {
      while (!cancel.stopped()) {
        int received = avcodec_receive_frame(decoder, frame);
        if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) return true;
        if (received < 0 || frame->sample_rate != rate || frame->ch_layout.nb_channels != channels || frame->nb_samples <= 0 || frame->nb_samples > 1048576) return false;
        const int64_t count = (int64_t)frame->nb_samples * channels;
        const int64_t size = count * (bits / 8);
        if (size > maximum - 44 - bytes) return false;
        std::vector<float> values(count);
        if (!convertFrame(frame, (AVSampleFormat)frame->format, channels, AV_SAMPLE_FMT_FLT, (uint8_t*)values.data())) return false;
        std::vector<uint8_t> block(size);
        const double scale = bits == 24 ? 8388608.0 : 32768.0;
        for (int64_t i = 0; i < count; ++i) {
          if (!std::isfinite(values[i])) return false;
          double sample = fmax(-1.0, fmin(1.0, (double)values[i]));
          int32_t value = (int32_t)fmax(-scale, fmin(scale - 1, round(sample * scale)));
          for (int j = 0; j < bits / 8; ++j) block[i * (bits / 8) + j] = (uint8_t)((uint32_t)value >> (j * 8));
        }
        if (fwrite(block.data(), 1, block.size(), sink) != block.size()) return false;
        bytes += size; av_frame_unref(frame);
      }
      return false;
    };
    int status;
    while (!cancel.stopped() && (status = av_read_frame(media.context, packet)) >= 0) {
      bool ok = packet->stream_index != media.index || (avcodec_send_packet(decoder, packet) >= 0 && frames());
      av_packet_unref(packet); if (!ok) return false;
    }
    if (cancel.stopped() || status != AVERROR_EOF || avcodec_send_packet(decoder, nullptr) < 0 || !frames() || bytes <= 0) return false;
    auto word = [&](int offset, uint32_t value, int width) { for (int i = 0; i < width; ++i) header[offset + i] = (uint8_t)(value >> (i * 8)); };
    memcpy(header, "RIFF", 4); word(4, bytes + 36, 4); memcpy(header + 8, "WAVEfmt ", 8); word(16, 16, 4); word(20, 1, 2);
    word(22, channels, 2); word(24, rate, 4); word(28, rate * channels * bits / 8, 4); word(32, channels * bits / 8, 2); word(34, bits, 2);
    memcpy(header + 36, "data", 4); word(40, bytes, 4);
    return fseek(sink, 0, SEEK_SET) == 0 && fwrite(header, 1, 44, sink) == 44 && fflush(sink) == 0;
  };
  bool ok = run();
  if (sink) fclose(sink);
  av_frame_free(&frame); av_packet_free(&packet); avcodec_free_context(&decoder);
  if (!ok) failure(env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cosmotune_providers_AndroidProviderMediaTools_encodeFlacNative(JNIEnv* env, jobject, jstring path,
  jstring destination, jlong maximum, jobject stopped) {
  Utf input(env, path), output(env, destination);
  Cancel cancel{env, stopped, env->GetMethodID(env->GetObjectClass(stopped), "getAsBoolean", "()Z")};
  FILE* source = nullptr; AVCodecContext* encoder = nullptr; AVFrame* frame = nullptr; AVPacket* packet = nullptr; Output target;
  auto run = [&]() -> bool {
    if (cancel.stopped() || maximum <= 0) return false;
    source = fopen(input.data, "rb"); if (!source) return false;
    uint8_t header[44]; if (fread(header, 1, 44, source) != 44 || memcmp(header, "RIFF", 4) || memcmp(header + 36, "data", 4)) return false;
    auto word = [&](int offset, int size) { uint32_t value = 0; for (int i = 0; i < size; ++i) value |= (uint32_t)header[offset + i] << (i * 8); return value; };
    const int channels = word(22, 2), rate = word(24, 4), bits = word(34, 2);
    int64_t remaining = word(40, 4);
    if (channels < 1 || channels > 8 || rate < 8000 || rate > 768000 || (bits != 16 && bits != 24) || remaining <= 0) return false;
    const AVCodec* codec = avcodec_find_encoder(AV_CODEC_ID_FLAC); if (!codec) return false;
    encoder = avcodec_alloc_context3(codec); frame = av_frame_alloc(); packet = av_packet_alloc();
    if (!encoder || !frame || !packet) return false;
    encoder->sample_rate = rate; encoder->sample_fmt = bits == 24 ? AV_SAMPLE_FMT_S32 : AV_SAMPLE_FMT_S16; encoder->bits_per_raw_sample = bits;
    encoder->time_base = {1, rate}; av_channel_layout_default(&encoder->ch_layout, channels);
    if (avcodec_open2(encoder, codec, nullptr) < 0 || avformat_alloc_output_context2(&target.context, nullptr, "flac", output.data) < 0 || !target.context) return false;
    AVStream* stream = avformat_new_stream(target.context, nullptr);
    if (!stream || avcodec_parameters_from_context(stream->codecpar, encoder) < 0) return false;
    stream->time_base = encoder->time_base;
    if (avio_open(&target.context->pb, output.data, AVIO_FLAG_WRITE) < 0 || avformat_write_header(target.context, nullptr) < 0) return false;
    const int block = encoder->frame_size; if (block <= 0 || block > 65536) return false;
    frame->format = encoder->sample_fmt; frame->sample_rate = rate; frame->nb_samples = block;
    if (av_channel_layout_copy(&frame->ch_layout, &encoder->ch_layout) < 0 || av_frame_get_buffer(frame, 0) < 0) return false;
    auto packets = [&]() -> bool {
      while (!cancel.stopped()) {
        int status = avcodec_receive_packet(encoder, packet);
        if (status == AVERROR_EOF || status == AVERROR(EAGAIN)) return true;
        if (status < 0) return false;
        av_packet_rescale_ts(packet, encoder->time_base, stream->time_base); packet->stream_index = 0;
        status = av_interleaved_write_frame(target.context, packet); av_packet_unref(packet);
        if (status < 0 || avio_tell(target.context->pb) > maximum) return false;
      }
      return false;
    };
    int64_t samples = 0;
    while (remaining > 0 && !cancel.stopped()) {
      int count = (int)std::min<int64_t>(block, remaining / (channels * bits / 8)); if (count <= 0) return false;
      std::vector<uint8_t> data(count * channels * bits / 8);
      if (fread(data.data(), 1, data.size(), source) != data.size() || av_frame_make_writable(frame) < 0) return false;
      frame->nb_samples = count; frame->pts = samples;
      for (int i = 0; i < count * channels; ++i) {
        const uint8_t* from = data.data() + i * (bits / 8);
        if (bits == 16) ((int16_t*)frame->data[0])[i] = (int16_t)((uint16_t)from[0] | (uint16_t)from[1] << 8);
        else ((int32_t*)frame->data[0])[i] = (int32_t)((uint32_t)from[0] << 8 | (uint32_t)from[1] << 16 | (uint32_t)from[2] << 24);
      }
      if (avcodec_send_frame(encoder, frame) < 0 || !packets()) return false;
      samples += count; remaining -= data.size();
    }
    return !cancel.stopped() && samples > 0 && avcodec_send_frame(encoder, nullptr) >= 0 && packets() && av_write_trailer(target.context) >= 0 && avio_tell(target.context->pb) <= maximum;
  };
  bool ok = run(); if (source) fclose(source);
  if (target.context && target.context->pb) avio_closep(&target.context->pb);
  av_frame_free(&frame); av_packet_free(&packet); avcodec_free_context(&encoder);
  if (!ok) failure(env);
}
