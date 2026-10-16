package com.cosmotune.video.select

import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.VideoStream

/**
 * The side-loaded DASH manifest (spec 6.2, 6.4; pure): a static MPD of one period with one video and one audio
 * representation, each with a `SegmentBase` (the index range) and its `Initialization` range, which Media3 plays as
 * `data:application/dash+xml;base64,...` so the subtitles can be side-loaded (the plan's decision W3).
 *
 * Written after NewPipe's manifest creators and LibreTube's: `minBufferTime="PT1.500S"`, the profile
 * `urn:mpeg:dash:profile:full:2011`, the role `urn:mpeg:dash:role:2011` (lower case, the one Media3 reads).
 */
object DashManifest {

    private const val NAMESPACE = "urn:mpeg:dash:schema:mpd:2011"
    private const val PROFILE = "urn:mpeg:dash:profile:full:2011"
    private const val ROLE_SCHEME = "urn:mpeg:dash:role:2011"
    private const val CHANNELS_SCHEME = "urn:mpeg:dash:23003:3:audio_channel_configuration:2011"

    /**
     * The manifest for [video] and [audio] of a video lasting [durationMs]. `mediaPresentationDuration` is
     * `PT<seconds with three decimals>S`; with a [durationMs] of 0 or less (unknown) it is left out, so the player takes
     * the length from the index rather than playing a video of no length. The video carries `mimeType`, `codecs`, `bandwidth`, `width`, `height` and
     * `frameRate`; the audio `mimeType`, `codecs`, `bandwidth`, `audioSamplingRate`, `lang` (when known) and an
     * `AudioChannelConfiguration` with the channel count (when known). An attribute whose value the source did not give
     * (0 or less, or null) is left out. Every address and value is XML-escaped.
     *
     * Whether the streams carry ranges decides, never [VideoStream.delivery]: the NewPipe adapter marks YouTube's adaptive
     * streams PROGRESSIVE, with their init and index ranges, and those are exactly what this is for.
     *
     * @throws IllegalArgumentException when a stream has no init or index range (the selector only calls this when
     *   both have them; streams without ranges are played as separate progressive sources, spec 6.2).
     */
    fun build(video: VideoStream, audio: AudioStream, durationMs: Long): String {
        val videoInit = requireRange(video.init, "video", "init")
        val videoIndex = requireRange(video.index, "video", "index")
        val audioInit = requireRange(audio.init, "audio", "init")
        val audioIndex = requireRange(audio.index, "audio", "index")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<MPD xmlns=\"").append(NAMESPACE).append("\" minBufferTime=\"PT1.500S\" profiles=\"").append(PROFILE)
            append("\" type=\"static\"")
            if (durationMs > 0) append(" mediaPresentationDuration=\"").append(duration(durationMs)).append('"')
            append(">\n")
            append("  <Period>\n")

            append("    <AdaptationSet id=\"0\" mimeType=\"").append(escape(mimeOf(video.mimeType))).append("\" subsegmentAlignment=\"true\">\n")
            appendRole(this, "main")
            append("      <Representation id=\"").append(video.itag).append("\" codecs=\"").append(escape(video.codecs)).append('"')
            if (video.bitrate > 0) append(" bandwidth=\"").append(video.bitrate).append('"')
            if (video.width > 0) append(" width=\"").append(video.width).append('"')
            if (video.height > 0) append(" height=\"").append(video.height).append('"')
            if (video.fps > 0) append(" frameRate=\"").append(video.fps).append('"')
            append(">\n")
            appendLocation(this, video.url, videoInit, videoIndex)
            append("      </Representation>\n")
            append("    </AdaptationSet>\n")

            append("    <AdaptationSet id=\"1\"")
            val lang = audio.language?.trim().orEmpty()
            if (lang.isNotEmpty()) append(" lang=\"").append(escape(lang)).append('"')
            append(" mimeType=\"").append(escape(mimeOf(audio.mimeType))).append("\" subsegmentAlignment=\"true\">\n")
            appendRole(this, roleOf(audio.trackKind))
            append("      <Representation id=\"").append(audio.itag).append("\" codecs=\"").append(escape(audio.codecs)).append('"')
            if (audio.bitrate > 0) append(" bandwidth=\"").append(audio.bitrate).append('"')
            val rate = audio.sampleRate
            if (rate != null && rate > 0) append(" audioSamplingRate=\"").append(rate).append('"')
            append(">\n")
            val channels = audio.channels
            if (channels != null && channels > 0) {
                append("        <AudioChannelConfiguration schemeIdUri=\"").append(CHANNELS_SCHEME).append("\" value=\"").append(channels).append("\"/>\n")
            }
            appendLocation(this, audio.url, audioInit, audioIndex)
            append("      </Representation>\n")
            append("    </AdaptationSet>\n")

            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    private fun appendRole(out: StringBuilder, value: String) {
        out.append("      <Role schemeIdUri=\"").append(ROLE_SCHEME).append("\" value=\"").append(value).append("\"/>\n")
    }

    private fun appendLocation(out: StringBuilder, url: String, init: ByteRange, index: ByteRange) {
        out.append("        <BaseURL>").append(escape(url)).append("</BaseURL>\n")
        out.append("        <SegmentBase indexRange=\"").append(index.start).append('-').append(index.endInclusive).append("\">\n")
        out.append("          <Initialization range=\"").append(init.start).append('-').append(init.endInclusive).append("\"/>\n")
        out.append("        </SegmentBase>\n")
    }

    /** The DASH role of an audio track, as NewPipe's creators map it: original is main, dubbed dub, descriptive description, a secondary track alternate. */
    private fun roleOf(kind: AudioTrackKind): String = when (kind) {
        AudioTrackKind.DUBBED -> "dub"
        AudioTrackKind.DESCRIPTIVE -> "description"
        AudioTrackKind.SECONDARY -> "alternate"
        AudioTrackKind.ORIGINAL, AudioTrackKind.UNKNOWN -> "main"
    }

    /** `PT<seconds>.<milliseconds, three digits>S`. */
    private fun duration(ms: Long): String = "PT${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}S"

    /** The media type without its parameters: some sources give `video/webm; codecs="vp9"`. */
    private fun mimeOf(mimeType: String): String = mimeType.substringBefore(';').trim()

    private fun requireRange(range: ByteRange?, stream: String, which: String): ByteRange =
        requireNotNull(range) { "The $stream stream has no $which range: it cannot be put in a SegmentBase manifest" }

    private fun escape(text: String): String {
        if (text.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return text
        return buildString(text.length + 16) {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
    }
}
