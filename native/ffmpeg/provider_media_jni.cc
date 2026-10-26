// Scoped, local-file audio operations. The Kotlin host validates paths, descriptors and quotas.
#include <jni.h>
#include <stdio.h>
#include <string>
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
    av_dict_set(&opts, "format_whitelist", "mov,flac,mp3,aac,ogg,wav,aiff,matroska,dsf,iff", 0);
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
